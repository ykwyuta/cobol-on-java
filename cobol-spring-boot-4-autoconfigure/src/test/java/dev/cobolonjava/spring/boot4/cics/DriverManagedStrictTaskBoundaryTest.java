package dev.cobolonjava.spring.boot4.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskCommitException;
import dev.cobolonjava.cics.CicsTaskCoordinator;
import dev.cobolonjava.cics.CicsTaskPolicy;
import dev.cobolonjava.cics.CicsTaskProgramPort;
import dev.cobolonjava.cics.CicsTaskReply;
import dev.cobolonjava.cics.CicsTaskRequest;
import dev.cobolonjava.cics.CicsTaskServices;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CicsTransactionRegistry;
import dev.cobolonjava.cics.CommitFailureState;
import dev.cobolonjava.cics.ConversationEnvelope;
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.ConversationReference;
import dev.cobolonjava.cics.IdempotencyKey;
import dev.cobolonjava.cics.SyncpointAction;
import dev.cobolonjava.cics.SyncpointPort;
import dev.cobolonjava.cics.TaskCompletion;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.db2.Db2Execution;
import dev.cobolonjava.db2.jdbc.Db2NativeConnectionLease;
import dev.cobolonjava.db2.jdbc.Db2NativeConnectionProvider;
import dev.cobolonjava.db2.jdbc.DriverManagerDb2NativeConnectionProvider;
import dev.cobolonjava.db2.jdbc.DriverManagedUnitOfWorks;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** DB2_DRIVER_MANAGED_HOLD の STRICT の task 境界 (暫定判断 P-143)。 */
@Tag("V1")
class DriverManagedStrictTaskBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T01:00:00Z");
    private static final ConversationId CONVERSATION = new ConversationId("conversation_native01");

    private JdbcTemplate jdbc;
    private JdbcConversationStore store;
    private CountingProvider connections;
    private DriverManagedStrictTaskBoundaryFactory factory;

    /** lease を取った回数を数える。task ごとに 1 本だけ専有することを見る。 */
    private static final class CountingProvider implements Db2NativeConnectionProvider {

        private final DriverManagerDb2NativeConnectionProvider delegate;
        private int acquired;

        private CountingProvider(String url, Properties credentials) {
            this.delegate = new DriverManagerDb2NativeConnectionProvider(url, credentials);
        }

        @Override
        public Db2NativeConnectionLease acquire() throws SQLException {
            acquired++;
            return delegate.acquire();
        }
    }

    private TestDatabase database;

    /** 試験する database。既定は H2。実 Db2 の試験はここを替える (native lease は JCC の DriverManager の connection)。 */
    TestDatabase openDatabase() {
        return TestDatabase.h2("native-strict");
    }

    @org.junit.jupiter.api.AfterEach
    void closeDatabase() {
        database.close();
    }

    @BeforeEach
    void setUp() {
        database = openDatabase();
        DataSource dataSource = database.dataSource();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE ACCOUNT (ID INT NOT NULL PRIMARY KEY)");
        store = new JdbcConversationStore(dataSource, new JdbcTransactionManager(dataSource));
        connections = new CountingProvider(database.url(), database.credentials());
        factory = new DriverManagedStrictTaskBoundaryFactory(connections, store);
    }

    private CicsTaskCoordinator coordinator(CicsTaskProgramPort program) {
        return new CicsTaskCoordinator(new CicsTransactionRegistry(List.of(new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("TX01PGM"), Duration.ofSeconds(5), 16, 0, 0, 0, true))),
                store, factory, program, new CicsTaskPolicy(Duration.ofMinutes(5), Duration.ofSeconds(30)),
                () -> CONVERSATION, Clock.fixed(NOW, ZoneOffset.UTC), store);
    }

    /** 境界が session に見せる Db2 の UOW の native lease で、業務の表へ行を足す。 */
    private static void insert(SyncpointPort syncpoints, int id) {
        RuntimeServices.Builder services = RuntimeServices.builder();
        ((CicsTaskServices) syncpoints).contribute(services);
        services.build().require(Db2Execution.class).runtime().withUnitOfWork(unit -> {
            try (PreparedStatement statement = DriverManagedUnitOfWorks.connection(unit)
                    .prepareStatement("INSERT INTO ACCOUNT (ID) VALUES (?)")) {
                statement.setInt(1, id);
                statement.executeUpdate();
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private int accounts(int id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT WHERE ID = ?", Integer.class, id);
    }

    @Test
    @DisplayName("業務の更新と次の会話を同じnative leaseでcommitし、同じ冪等キーの再送はtaskを動かさない")
    void commitsBusinessUpdateAndConversationOnTheLease() {
        CicsTaskCoordinator coordinator = coordinator((definition, input, task, syncpoints) -> {
            insert(syncpoints, 1);
            // commit の前は、別の connection から業務の行も会話も見えない
            assertEquals(0, accounts(1));
            return new TaskCompletion(Optional.of(TransId.of("TX01")), CicsPayload.ofCommarea(new byte[] {1}));
        });
        CicsTaskRequest request = new CicsTaskRequest("TX01", "owner", CicsPayload.ofCommarea(new byte[] {0}),
                Optional.empty(), new IdempotencyKey("native-key-0001"));

        CicsTaskReply reply = coordinator.launch(request);
        assertEquals(1, accounts(1));
        assertTrue(store.load(CONVERSATION, NOW).isPresent());
        assertEquals(1, connections.acquired);

        // 再送で task が動けば主キーの重複で失敗する。覚えた応答が返り、lease も取らない
        assertEquals(reply.taskId(), coordinator.launch(request).taskId());
        assertEquals(1, accounts(1));
        assertEquals(1, connections.acquired);
    }

    @Test
    @DisplayName("次の会話を保存できなければ、leaseの上の業務の更新もcommitしない")
    void rollsBackBusinessUpdateWhenConversationCannotBeSaved() {
        store.create(new ConversationEnvelope(CONVERSATION, 0, "owner", TransId.of("TX01"),
                CicsPayload.ofCommarea(new byte[] {3}), NOW.plusSeconds(60), new IdempotencyKey("native-key-0000"),
                Optional.empty()), NOW);
        CicsTaskCoordinator coordinator = coordinator((definition, input, task, syncpoints) -> {
            insert(syncpoints, 2);
            // 会話を他の JVM が消した状況を作る (DataSource の connection は autoCommit)
            jdbc.update("DELETE FROM COBOL_CONVERSATION");
            return new TaskCompletion(Optional.of(TransId.of("TX01")), CicsPayload.ofCommarea(new byte[] {2}));
        });

        CicsTaskCommitException failure = assertThrows(CicsTaskCommitException.class, () -> coordinator.launch(
                new CicsTaskRequest("TX01", "owner", CicsPayload.empty(),
                        Optional.of(new ConversationReference(CONVERSATION, 0)), new IdempotencyKey("native-key-0002"))));
        assertEquals(CommitFailureState.NOT_COMMITTED, failure.state());
        assertEquals(0, accounts(2));
    }

    @Test
    @DisplayName("SYNCPOINTのあとも同じleaseを使い、そのあとのABENDはそこからの更新だけをrollbackする")
    void syncpointKeepsTheLease() {
        CicsTaskCoordinator coordinator = coordinator((definition, input, task, syncpoints) -> {
            insert(syncpoints, 3);
            syncpoints.syncpoint(SyncpointAction.COMMIT, task);
            insert(syncpoints, 4);
            throw new IllegalStateException("task failed");
        });

        assertThrows(IllegalStateException.class, () -> coordinator.launch(new CicsTaskRequest("TX01", "owner",
                CicsPayload.empty(), Optional.empty(), new IdempotencyKey("native-key-0003"))));
        assertEquals(1, accounts(3));
        assertEquals(0, accounts(4));
        assertEquals(1, connections.acquired);
    }
}
