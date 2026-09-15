package dev.cobolonjava.spring.boot4.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.CicsOutcomeStorePort;
import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskCommitException;
import dev.cobolonjava.cics.CicsTaskId;
import dev.cobolonjava.cics.CicsTaskReply;
import dev.cobolonjava.cics.ConversationClaimStatus;
import dev.cobolonjava.cics.ConversationEnvelope;
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.ConversationLease;
import dev.cobolonjava.cics.ConversationMutationResult;
import dev.cobolonjava.cics.IdempotencyKey;
import dev.cobolonjava.cics.TransId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** 会話と冪等キーの結果の JDBC の置き場 (暫定判断 P-143)。 */
@Tag("V1")
class JdbcConversationStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-15T01:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final String FINGERPRINT = "a".repeat(64);

    private JdbcConversationStore store;
    private TestDatabase database;

    /** 試験する database。既定は H2。実 Db2 の試験はここを替える。 */
    TestDatabase openDatabase() {
        return TestDatabase.h2("store");
    }

    @BeforeEach
    void setUp() {
        database = openDatabase();
        DataSource dataSource = database.dataSource();
        store = new JdbcConversationStore(dataSource, new JdbcTransactionManager(dataSource));
    }

    @org.junit.jupiter.api.AfterEach
    void closeDatabase() {
        database.close();
    }

    @Test
    @DisplayName("同じ版の会話を別々のconnectionから同時にclaimしても1件だけが取れる")
    void claimsConversationOnceUnderConcurrency() throws Exception {
        store.create(initial(), NOW);
        int contenders = 8;
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(contenders);
        try {
            java.util.List<java.util.concurrent.Future<ConversationClaimStatus>> results = new java.util.ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                results.add(executor.submit(() -> {
                    go.await();
                    return store.claim(initial().id(), 0, "owner", LEASE, NOW).status();
                }));
            }
            go.countDown();
            int claimed = 0;
            for (java.util.concurrent.Future<ConversationClaimStatus> result : results) {
                ConversationClaimStatus status = result.get();
                claimed += status == ConversationClaimStatus.CLAIMED ? 1 : 0;
                assertTrue(status == ConversationClaimStatus.CLAIMED || status == ConversationClaimStatus.ALREADY_LEASED,
                        status.name());
            }
            assertEquals(1, claimed);
        } finally {
            executor.shutdownNow();
        }
    }

    private static ConversationEnvelope initial() {
        return new ConversationEnvelope(new ConversationId("conversation_jdbc01"), 0, "owner", TransId.of("TX01"),
                CicsPayload.ofCommarea(new byte[] {1}), NOW.plusSeconds(60), new IdempotencyKey("request-0001"),
                Optional.empty());
    }

    @Test
    @DisplayName("会話は作成・claim・版を進める保存・release・完了を、owner・版・leaseの条件つきで行う")
    void runsConversationLifecycle() {
        ConversationId id = initial().id();
        assertEquals(ConversationMutationResult.CREATED, store.create(initial(), NOW));
        assertEquals(ConversationMutationResult.ALREADY_EXISTS, store.create(initial(), NOW));
        assertEquals(ConversationClaimStatus.OWNER_MISMATCH, store.claim(id, 0, "other", LEASE, NOW).status());
        assertEquals(ConversationClaimStatus.VERSION_CONFLICT, store.claim(id, 1, "owner", LEASE, NOW).status());

        ConversationLease lease = store.claim(id, 0, "owner", LEASE, NOW).lease().orElseThrow();
        assertEquals(ConversationClaimStatus.ALREADY_LEASED, store.claim(id, 0, "owner", LEASE, NOW).status());
        ConversationEnvelope next = lease.envelope().next(TransId.of("TX01"), CicsPayload.ofCommarea(new byte[] {2}),
                NOW.plusSeconds(60), new IdempotencyKey("request-0002"), Optional.of("task_1"));
        assertEquals(ConversationMutationResult.SAVED, store.save(lease, next, NOW));
        assertEquals(ConversationMutationResult.LEASE_MISMATCH, store.save(lease, next, NOW));
        ConversationEnvelope loaded = store.load(id, NOW).orElseThrow();
        assertEquals(1, loaded.version());
        assertArrayEquals(new byte[] {2}, loaded.payload().commarea());

        ConversationLease second = store.claim(id, 1, "owner", LEASE, NOW).lease().orElseThrow();
        assertEquals(ConversationMutationResult.RELEASED, store.release(second, NOW));
        ConversationLease third = store.claim(id, 1, "owner", LEASE, NOW).lease().orElseThrow();
        assertEquals(ConversationMutationResult.COMPLETED, store.complete(third, NOW));
        assertTrue(store.load(id, NOW).isEmpty());
        assertEquals(ConversationMutationResult.NOT_FOUND, store.complete(third, NOW));
    }

    @Test
    @DisplayName("期限の切れた会話のclaimはEXPIREDで、行を消す")
    void expiresConversations() {
        store.create(initial(), NOW);
        assertEquals(ConversationClaimStatus.EXPIRED,
                store.claim(initial().id(), 0, "owner", LEASE, NOW.plusSeconds(120)).status());
        assertTrue(store.load(initial().id(), NOW).isEmpty());
    }

    @Test
    @DisplayName("sessionが消えたときのdiscardはleaseの無い会話だけを消し、taskが動いている会話は残す")
    void discardsOnlyUnleasedConversations() {
        ConversationId id = initial().id();
        store.create(initial(), NOW);
        ConversationLease lease = store.claim(id, 0, "owner", LEASE, NOW).lease().orElseThrow();

        assertEquals(ConversationMutationResult.LEASE_MISMATCH, store.discard(id, NOW));
        assertEquals(ConversationMutationResult.RELEASED, store.release(lease, NOW));
        assertEquals(ConversationMutationResult.COMPLETED, store.discard(id, NOW));
        assertTrue(store.load(id, NOW).isEmpty());
        assertEquals(ConversationMutationResult.NOT_FOUND, store.discard(id, NOW));

        store.create(initial(), NOW);
        assertEquals(ConversationMutationResult.EXPIRED, store.discard(id, NOW.plusSeconds(60)));
    }

    @Test
    @DisplayName("冪等キーは予約・記録で再送に応答を返し、違う要約はMISMATCH、記録していない予約はreleaseで外れる")
    void storesIdempotentOutcomes() {
        IdempotencyKey key = new IdempotencyKey("client-key-0001");
        assertEquals(CicsOutcomeStorePort.Status.RESERVED, store.reserve("owner", key, FINGERPRINT, LEASE, NOW).status());
        assertEquals(CicsOutcomeStorePort.Status.IN_PROGRESS,
                store.reserve("owner", key, FINGERPRINT, LEASE, NOW).status());
        assertEquals(CicsOutcomeStorePort.Status.MISMATCH,
                store.reserve("owner", key, "b".repeat(64), LEASE, NOW).status());

        CicsTaskReply reply = new CicsTaskReply(new CicsTaskId("task_jdbc"), TransId.of("TX01"),
                CicsPayload.ofCommarea(new byte[] {7}), Optional.empty());
        store.record("owner", key, reply, NOW.plusSeconds(300), NOW);
        CicsOutcomeStorePort.Reservation replay = store.reserve("owner", key, FINGERPRINT, LEASE, NOW);
        assertEquals(CicsOutcomeStorePort.Status.REPLAY, replay.status());
        assertEquals(reply.taskId(), replay.reply().orElseThrow().taskId());
        assertThrows(CicsTaskCommitException.class, () -> store.record("owner", key, reply, NOW.plusSeconds(300), NOW));

        IdempotencyKey released = new IdempotencyKey("client-key-0002");
        store.reserve("owner", released, FINGERPRINT, LEASE, NOW);
        store.release("owner", released, NOW);
        assertEquals(CicsOutcomeStorePort.Status.RESERVED,
                store.reserve("owner", released, FINGERPRINT, LEASE, NOW).status());

        assertTrue(store.purgeExpired(NOW.plusSeconds(3600)) >= 2);
        assertEquals(CicsOutcomeStorePort.Status.RESERVED, store.reserve("owner", key, FINGERPRINT, LEASE, NOW).status());
    }
}
