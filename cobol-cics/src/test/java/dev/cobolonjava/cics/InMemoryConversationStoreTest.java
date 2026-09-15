package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class InMemoryConversationStoreTest {

    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    @Test
    @DisplayName("version zeroを作成しcopy分離されたenvelopeを読み出す")
    void createsAndLoadsConversationData() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        byte[] commarea = {1, 2};
        ConversationEnvelope initial = envelope(0, NOW.plusSeconds(60), commarea);

        assertEquals(ConversationMutationResult.CREATED, store.create(initial, NOW));
        assertEquals(ConversationMutationResult.ALREADY_EXISTS, store.create(initial, NOW));
        commarea[0] = 9;
        byte[] returned = store.load(initial.id(), NOW).orElseThrow().payload().commarea();
        returned[1] = 9;

        assertArrayEquals(new byte[] {1, 2},
                store.load(initial.id(), NOW).orElseThrow().payload().commarea());
    }

    @Test
    @DisplayName("ownerとversion一致時だけclaimしactive lease中の二重実行を拒否する")
    void claimsOnlyExpectedOwnerAndVersion() {
        InMemoryConversationStore store = createdStore();
        ConversationId id = id();

        assertEquals(ConversationClaimStatus.OWNER_MISMATCH,
                store.claim(id, 0, "other", Duration.ofSeconds(5), NOW).status());
        assertEquals(ConversationClaimStatus.VERSION_CONFLICT,
                store.claim(id, 1, "owner", Duration.ofSeconds(5), NOW).status());
        ConversationClaimResult first = claim(store, NOW);
        assertEquals(ConversationClaimStatus.ALREADY_LEASED,
                store.claim(id, 0, "owner", Duration.ofSeconds(5), NOW).status());
        assertTrue(first.lease().isPresent());
    }

    @Test
    @DisplayName("claimした版を一つだけ進めてleaseを解放する")
    void savesExactlyOneSuccessorVersion() {
        InMemoryConversationStore store = createdStore();
        ConversationLease lease = claim(store, NOW).lease().orElseThrow();
        ConversationEnvelope next = lease.envelope().next(
                TransId.of("NXT2"), CicsPayload.ofCommarea(new byte[] {7}),
                NOW.plusSeconds(120), new IdempotencyKey("request-0002"), Optional.of("OK"));

        assertEquals(ConversationMutationResult.SAVED, store.save(lease, next, NOW));
        assertEquals(1, store.load(id(), NOW).orElseThrow().version());
        assertEquals(ConversationMutationResult.LEASE_MISMATCH, store.save(lease, next, NOW));
        assertEquals(ConversationClaimStatus.CLAIMED,
                store.claim(id(), 1, "owner", Duration.ofSeconds(5), NOW).status());
    }

    @Test
    @DisplayName("saveは会話ID、owner、版の飛び越し、期限切れを拒否する")
    void rejectsInvalidSuccessors() {
        InMemoryConversationStore store = createdStore();
        ConversationLease lease = claim(store, NOW).lease().orElseThrow();

        assertThrows(IllegalArgumentException.class,
                () -> store.save(lease, envelope(2, NOW.plusSeconds(5), new byte[0]), NOW));
        ConversationEnvelope expired = lease.envelope().next(
                TransId.of("NXT2"), CicsPayload.empty(), NOW,
                new IdempotencyKey("request-0002"), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> store.save(lease, expired, NOW));
    }

    @Test
    @DisplayName("RETURN without TRANSID相当のcompleteで会話を削除する")
    void completesConversation() {
        InMemoryConversationStore store = createdStore();
        ConversationLease lease = claim(store, NOW).lease().orElseThrow();

        assertEquals(ConversationMutationResult.COMPLETED, store.complete(lease, NOW));
        assertTrue(store.load(id(), NOW).isEmpty());
        assertEquals(ConversationMutationResult.NOT_FOUND, store.complete(lease, NOW));
    }

    @Test
    @DisplayName("task失敗時はreleaseして同じ版を再claimできる")
    void releasesConversationWithoutAdvancingVersion() {
        InMemoryConversationStore store = createdStore();
        ConversationLease lease = claim(store, NOW).lease().orElseThrow();

        assertEquals(ConversationMutationResult.RELEASED, store.release(lease, NOW));
        ConversationLease retry = claim(store, NOW).lease().orElseThrow();
        assertEquals(0, retry.envelope().version());
        assertNotEquals(lease.token(), retry.token());
    }

    @Test
    @DisplayName("sessionが消えたときのdiscardはleaseの無い会話だけを消し、taskが動いている会話は残す")
    void discardsOnlyUnleasedConversations() {
        InMemoryConversationStore store = createdStore();
        ConversationLease lease = claim(store, NOW).lease().orElseThrow();

        assertEquals(ConversationMutationResult.LEASE_MISMATCH, store.discard(id(), NOW));
        assertEquals(ConversationMutationResult.SAVED, store.save(lease, lease.envelope().next(
                TransId.of("NXT2"), CicsPayload.empty(), NOW.plusSeconds(60),
                new IdempotencyKey("request-0002"), Optional.empty()), NOW));
        assertEquals(ConversationMutationResult.COMPLETED, store.discard(id(), NOW));
        assertTrue(store.load(id(), NOW).isEmpty());
        assertEquals(ConversationMutationResult.NOT_FOUND, store.discard(id(), NOW));

        InMemoryConversationStore expired = createdStore();
        assertEquals(ConversationMutationResult.EXPIRED, expired.discard(id(), NOW.plusSeconds(60)));
    }

    @Test
    @DisplayName("lease期限後は別taskが再claimでき古いtaskのsaveを拒否する")
    void reclaimsAfterLeaseExpiry() {
        InMemoryConversationStore store = createdStore();
        ConversationLease stale = claim(store, NOW).lease().orElseThrow();
        Instant later = NOW.plusSeconds(6);
        ConversationLease replacement = claim(store, later).lease().orElseThrow();
        ConversationEnvelope next = stale.envelope().next(
                TransId.of("NXT2"), CicsPayload.empty(), later.plusSeconds(60),
                new IdempotencyKey("request-0002"), Optional.empty());

        assertNotEquals(stale.token(), replacement.token());
        assertEquals(ConversationMutationResult.LEASE_MISMATCH,
                store.save(stale, next, later));
    }

    @Test
    @DisplayName("会話期限到達時はloadとclaimの双方から除去する")
    void expiresConversationAtBoundary() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        ConversationEnvelope initial = envelope(0, NOW.plusSeconds(1), new byte[0]);
        store.create(initial, NOW);

        assertTrue(store.load(id(), NOW.plusSeconds(1)).isEmpty());
        assertEquals(ConversationClaimStatus.NOT_FOUND,
                store.claim(id(), 0, "owner", Duration.ofSeconds(5), NOW.plusSeconds(1)).status());
    }

    @Test
    @DisplayName("同一版への同時claimは一件だけ成功する")
    void allowsOnlyOneConcurrentClaim() throws Exception {
        InMemoryConversationStore store = createdStore();
        int contenders = 12;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<ConversationClaimStatus>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return store.claim(id(), 0, "owner", Duration.ofSeconds(5), NOW).status();
                }));
            }
            ready.await();
            start.countDown();

            long claimed = 0;
            long leased = 0;
            for (Future<ConversationClaimStatus> result : results) {
                ConversationClaimStatus status = result.get();
                claimed += status == ConversationClaimStatus.CLAIMED ? 1 : 0;
                leased += status == ConversationClaimStatus.ALREADY_LEASED ? 1 : 0;
            }
            assertEquals(1, claimed);
            assertEquals(contenders - 1, leased);
        } finally {
            executor.shutdownNow();
        }
    }

    private static InMemoryConversationStore createdStore() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        store.create(envelope(0, NOW.plusSeconds(60), new byte[] {1, 2}), NOW);
        return store;
    }

    private static ConversationClaimResult claim(InMemoryConversationStore store, Instant now) {
        return store.claim(id(), 0, "owner", Duration.ofSeconds(5), now);
    }

    private static ConversationEnvelope envelope(long version, Instant expiry, byte[] commarea) {
        return new ConversationEnvelope(
                id(), version, "owner", TransId.of("NXT1"),
                new CicsPayload(commarea, Map.of("DATA", new byte[] {3})),
                expiry, new IdempotencyKey("request-0001"), Optional.empty());
    }

    private static ConversationId id() {
        return new ConversationId("conversation_0001");
    }
}
