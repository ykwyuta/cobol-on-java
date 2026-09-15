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
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.spring.boot4.db2.SpringManagedSqlExecutor;
import dev.cobolonjava.spring.boot4.db2.SpringManagedUnitOfWorkPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** STRICT の task 境界 (暫定判断 P-143)。 */
@Tag("V1")
class SpringStrictTaskBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T01:00:00Z");
    private static final ConversationId CONVERSATION = new ConversationId("conversation_strict01");

    private JdbcTransactionManager transactionManager;
    private JdbcTemplate jdbc;
    private JdbcConversationStore store;
    private SpringStrictTaskBoundaryFactory factory;

    private TestDatabase database;

    /** 試験する database。既定は H2。実 Db2 の試験はここを替える。 */
    TestDatabase openDatabase() {
        return TestDatabase.h2("strict");
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
        transactionManager = new JdbcTransactionManager(dataSource);
        store = new JdbcConversationStore(dataSource, transactionManager);
        factory = new SpringStrictTaskBoundaryFactory(() -> new SpringManagedUnitOfWorkPort(dataSource,
                transactionManager), new SpringManagedSqlExecutor(dataSource), store);
    }

    private CicsTaskCoordinator coordinator(CicsTaskProgramPort program) {
        return new CicsTaskCoordinator(new CicsTransactionRegistry(List.of(new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("TX01PGM"), Duration.ofSeconds(5), 16, 0, 0, 0, true))),
                store, factory, program, new CicsTaskPolicy(Duration.ofMinutes(5), Duration.ofSeconds(30)),
                () -> CONVERSATION, Clock.fixed(NOW, ZoneOffset.UTC), store);
    }

    /** 境界が session に見せる Db2 の UOW で、業務の表へ行を足す。 */
    private void insert(SyncpointPort syncpoints, int id) {
        RuntimeServices.Builder services = RuntimeServices.builder();
        ((CicsTaskServices) syncpoints).contribute(services);
        services.build().require(Db2Execution.class).runtime()
                .inUnitOfWork(() -> jdbc.update("INSERT INTO ACCOUNT (ID) VALUES (?)", id));
    }

    private int accounts(int id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ACCOUNT WHERE ID = ?", Integer.class, id);
    }

    @Test
    @DisplayName("業務の更新と次の会話を同じUOWでcommitし、同じ冪等キーの再送はtaskを動かさない")
    void commitsBusinessUpdateAndConversationTogether() {
        CicsTaskCoordinator coordinator = coordinator((definition, input, task, syncpoints) -> {
            insert(syncpoints, 1);
            return new TaskCompletion(Optional.of(TransId.of("TX01")), CicsPayload.ofCommarea(new byte[] {1}));
        });
        CicsTaskRequest request = new CicsTaskRequest("TX01", "owner", CicsPayload.ofCommarea(new byte[] {0}),
                Optional.empty(), new IdempotencyKey("strict-key-0001"));

        CicsTaskReply reply = coordinator.launch(request);
        assertEquals(1, accounts(1));
        assertTrue(store.load(CONVERSATION, NOW).isPresent());

        // 再送で task が動けば主キーの重複で失敗する。覚えた応答が返る
        assertEquals(reply.taskId(), coordinator.launch(request).taskId());
        assertEquals(1, accounts(1));
    }

    @Test
    @DisplayName("次の会話を保存できなければ、業務の更新もcommitしない")
    void rollsBackBusinessUpdateWhenConversationCannotBeSaved() {
        store.create(new ConversationEnvelope(CONVERSATION, 0, "owner", TransId.of("TX01"),
                CicsPayload.ofCommarea(new byte[] {3}), NOW.plusSeconds(60), new IdempotencyKey("strict-key-0000"),
                Optional.empty()), NOW);
        TransactionTemplate separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        CicsTaskCoordinator coordinator = coordinator((definition, input, task, syncpoints) -> {
            insert(syncpoints, 2);
            // 会話を他の JVM が消した状況を作る
            separate.executeWithoutResult(status -> jdbc.update("DELETE FROM COBOL_CONVERSATION"));
            return new TaskCompletion(Optional.of(TransId.of("TX01")), CicsPayload.ofCommarea(new byte[] {2}));
        });

        CicsTaskCommitException failure = assertThrows(CicsTaskCommitException.class, () -> coordinator.launch(
                new CicsTaskRequest("TX01", "owner", CicsPayload.empty(),
                        Optional.of(new ConversationReference(CONVERSATION, 0)), new IdempotencyKey("strict-key-0002"))));
        assertEquals(CommitFailureState.NOT_COMMITTED, failure.state());
        assertEquals(0, accounts(2));
    }

    @Test
    @DisplayName("SYNCPOINTまでの更新はcommitし、そのあとのABENDはそこからの更新だけをrollbackする")
    void syncpointCommitsEarlierWork() {
        CicsTaskCoordinator coordinator = coordinator((definition, input, task, syncpoints) -> {
            insert(syncpoints, 3);
            syncpoints.syncpoint(SyncpointAction.COMMIT, task);
            insert(syncpoints, 4);
            throw new IllegalStateException("task failed");
        });

        assertThrows(IllegalStateException.class, () -> coordinator.launch(new CicsTaskRequest("TX01", "owner",
                CicsPayload.empty(), Optional.empty(), new IdempotencyKey("strict-key-0003"))));
        assertEquals(1, accounts(3));
        assertEquals(0, accounts(4));
    }
}
