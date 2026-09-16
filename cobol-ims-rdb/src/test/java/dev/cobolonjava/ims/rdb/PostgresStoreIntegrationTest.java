package dev.cobolonjava.ims.rdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.db.Segment;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import dev.cobolonjava.ims.dbd.DbdParser;
import dev.cobolonjava.ims.store.DatabaseConflictException;
import dev.cobolonjava.ims.store.QueueLease;
import dev.cobolonjava.ims.store.QueueLeaseException;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 実 PostgreSQL を使う試験 (設計 78 §3.2、ADR-0013、暫定判断 P-160)。
 *
 * <p>{@code infra/postgres} の compose で起動し、{@code POSTGRES_IT_ENABLED=true} を設定したときだけ流れる。
 * Db2・RabbitMQ の実機試験と同じ構えである。設定が無ければスキップし、失敗にはしない。
 *
 * <p>{@link ImsSchema} は PostgreSQL に {@code BYTEA} と {@code COLLATE "C"} を使う。この枝は H2 の試験では
 * 通らないので、生バイトの往復・{@code SELECT ... FOR UPDATE} による根の排他・一意制約の競合を実物で確かめる。
 * 試験ごとに使い捨ての schema を作り、終われば消す。
 */
@Tag("V1")
@EnabledIfEnvironmentVariable(named = "POSTGRES_IT_ENABLED", matches = "(?i)true")
class PostgresStoreIntegrationTest {

    private static final CodePage EBCDIC = CodePages.DEFAULT;

    private static final DatabaseDefinition DBD = DbdParser.parse(String.join("\n",
            card("         DBD   NAME=BANKDB,ACCESS=HDAM"),
            card("         SEGM  NAME=CUST,PARENT=0,BYTES=4"),
            card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1"),
            card("         SEGM  NAME=ACCT,PARENT=CUST,BYTES=4"),
            card("         FIELD NAME=(ACCTNO,SEQ,U),BYTES=4,START=1"),
            card("         DBDGEN")) + "\n");

    private final String schema = "ims_it_" + UUID.randomUUID().toString().replace("-", "");

    private static String card(String text) {
        return text + " ".repeat(72 - text.length());
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String url() {
        return "jdbc:postgresql://" + environment("POSTGRES_HOST", "localhost")
                + ":" + environment("POSTGRES_PORT", "5432")
                + "/" + environment("POSTGRES_DB", "imsdb");
    }

    private static Connection open(String currentSchema) throws SQLException {
        String suffix = currentSchema == null ? "" : "?currentSchema=" + currentSchema;
        return DriverManager.getConnection(url() + suffix,
                environment("POSTGRES_USER", "cobol"), environment("POSTGRES_PASSWORD", "change-me"));
    }

    @BeforeEach
    void createSchema() throws SQLException {
        try (Connection connection = open(null); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    @AfterEach
    void dropSchema() throws SQLException {
        try (Connection connection = open(null); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private JdbcDatabaseStore store() throws SQLException {
        return new JdbcDatabaseStore(open(schema));
    }

    private static List<String> texts(HierarchicalDatabase database) {
        return database.hierarchicalOrder().stream().map(Segment::data).map(EBCDIC::decode).toList();
    }

    @Test
    @DisplayName("生バイトのまま書いて読み直せる。階層の順も保たれる")
    void segmentsSurviveAWriteAndRead() throws SQLException {
        try (JdbcDatabaseStore store = store()) {
            HierarchicalDatabase database = store.open(DBD);
            Segment first = database.insert(null, DBD.root(), EBCDIC.encode("0001"));
            database.insert(first, DBD.segment("ACCT"), EBCDIC.encode("A001"));
            database.insert(null, DBD.root(), EBCDIC.encode("0002"));
            store.commit(List.of(database));
        }
        try (JdbcDatabaseStore store = store()) {
            HierarchicalDatabase read = store.open(DBD);
            assertEquals(List.of("0001", "A001", "0002"), texts(read));
            // BYTEA に入れた値がそのまま戻るか (符号つきの byte も含めて)
            assertArrayEquals(EBCDIC.encode("0001"), read.roots().get(0).data());
        }
    }

    @Test
    @DisplayName("遅れた更新は、根の版で競合として止まる (SELECT ... FOR UPDATE)")
    void aStaleUpdateIsRefused() throws SQLException {
        try (JdbcDatabaseStore first = store(); JdbcDatabaseStore second = store()) {
            HierarchicalDatabase one = first.open(DBD);
            HierarchicalDatabase two = second.open(DBD);

            one.insert(null, DBD.root(), EBCDIC.encode("0001"));
            first.commit(List.of(one));

            two.insert(null, DBD.root(), EBCDIC.encode("0001"));
            assertThrows(DatabaseConflictException.class, () -> second.commit(List.of(two)));
        }
    }

    @Test
    @DisplayName("処理済みの電文と検査点が、業務の更新と同じトランザクションで残る")
    void theInboxAndCheckpointsAreWrittenWithTheUpdate() throws SQLException {
        try (JdbcDatabaseStore store = store()) {
            HierarchicalDatabase database = store.open(DBD);
            assertFalse(store.inbox().seen("ID:1"));

            database.insert(null, DBD.root(), EBCDIC.encode("0001"));
            store.inbox().record("ID:1");
            store.checkpoints().record("BANKPSB", "CHKP0001", List.of(EBCDIC.encode("00000042")));
            store.commit(List.of(database));

            assertTrue(store.inbox().seen("ID:1"));
        }
        try (JdbcDatabaseStore store = store()) {
            assertTrue(store.inbox().seen("ID:1"));
            assertArrayEquals(EBCDIC.encode("00000042"),
                    store.checkpoints().load("BANKPSB", "CHKP0001").get(0));
        }
    }

    @Test
    @DisplayName("取引コードの借用は 1 つの領域だけが取れる")
    void onlyOneRegionTakesTheQueueLease() throws SQLException {
        try (JdbcDatabaseStore first = store(); JdbcDatabaseStore second = store()) {
            try (QueueLease.Held held = first.queueLease().acquire("IBLOGIN1", "region-A")) {
                assertTrue(held != null);
                assertThrows(QueueLeaseException.class,
                        () -> second.queueLease().acquire("IBLOGIN1", "region-B"));
            }
            // 返したあとは借りられる
            second.queueLease().acquire("IBLOGIN1", "region-B").close();
        }
    }
}
