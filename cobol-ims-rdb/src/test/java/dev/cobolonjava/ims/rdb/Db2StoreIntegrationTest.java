package dev.cobolonjava.ims.rdb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 実 Db2 を使う試験 (設計 78 §3.2、ADR-0013、暫定判断 P-160)。
 *
 * <p>{@code infra/db2} の compose で起動し、{@code DB2_IT_ENABLED=true} を設定したときだけ流れる。
 * PostgreSQL・RabbitMQ の実機試験と同じ構えである。設定が無ければスキップし、失敗にはしない。
 *
 * <p>Db2 は IMS の資産がいちばん移りやすい RDB である。{@link ImsSchema} の Db2 の枝と、
 * {@code SELECT ... FOR UPDATE} による根の排他、一意制約の競合を実物で確かめる。試験ごとに
 * 使い捨ての schema を作り、終われば表ごと消す。
 */
@Tag("V1")
@EnabledIfEnvironmentVariable(named = "DB2_IT_ENABLED", matches = "(?i)true")
class Db2StoreIntegrationTest {

    private static final CodePage EBCDIC = CodePages.DEFAULT;

    /** 使い捨ての schema に作る表。片付けるときに名前で消す。 */
    private static final List<String> TABLES = List.of(
            "IMS_SEGMENT_STORE", "IMS_ROOT_INDEX", "IMS_MESSAGE_INBOX",
            "IMS_CHECKPOINT", "IMS_QUEUE_LEASE", "IMS_ROOT_LOCK");

    private static final DatabaseDefinition DBD = DbdParser.parse(String.join("\n",
            card("         DBD   NAME=BANKDB,ACCESS=HDAM"),
            card("         SEGM  NAME=CUST,PARENT=0,BYTES=4"),
            card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1"),
            card("         SEGM  NAME=ACCT,PARENT=CUST,BYTES=4"),
            card("         FIELD NAME=(ACCTNO,SEQ,U),BYTES=4,START=1"),
            card("         DBDGEN")) + "\n");

    private final String schema = "IMSIT" + UUID.randomUUID().toString()
            .replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);

    private static String card(String text) {
        return text + " ".repeat(72 - text.length());
    }

    private static String value(String name, String fallback) {
        String found = System.getenv(name);
        return found == null || found.isBlank() ? fallback : found;
    }

    private static Connection open() throws SQLException {
        String url = "jdbc:db2://" + value("DB2_HOST", "localhost") + ":"
                + value("DB2_PORT", "50000") + "/" + value("DB2_DATABASE", "COBOLDB");
        return DriverManager.getConnection(url, value("DB2_USER", "db2inst1"),
                value("DB2_PASSWORD", "change-me"));
    }

    @BeforeEach
    void createSchema() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    @AfterEach
    void dropSchema() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            for (String table : TABLES) {
                try {
                    statement.execute("DROP TABLE " + schema + "." + table);
                } catch (SQLException ignored) {
                    // 作られなかった表もある。片付けが試験の結果を塗り替えないようにする
                }
            }
            statement.execute("DROP SCHEMA " + schema + " RESTRICT");
        }
    }

    /** 使い捨ての schema に向けた置き場。URL の書式差を避けて、接続してから切り替える。 */
    private JdbcDatabaseStore store() throws SQLException {
        Connection connection = open();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET CURRENT SCHEMA " + schema);
        }
        return new JdbcDatabaseStore(connection);
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
                assertNotNull(held);
                assertThrows(QueueLeaseException.class,
                        () -> second.queueLease().acquire("IBLOGIN1", "region-B"));
            }
            second.queueLease().acquire("IBLOGIN1", "region-B").close();
        }
    }
}
