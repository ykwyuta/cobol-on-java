package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.interop.ProgramId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class CicsTaskCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-09-10T01:00:00Z");
    private static final ConversationId CONVERSATION =
            new ConversationId("conversation_1001");

    @Test
    @DisplayName("新規taskのRETURN TRANSIDをversion zeroの会話作成と同じcommit境界へ渡す")
    void createsConversationAtTaskCommit() {
        Fixture fixture = fixture((definition, input, task, syncpoints) ->
                new TaskCompletion(Optional.of(TransId.of("NXT1")),
                        CicsPayload.ofCommarea(new byte[] {7})));

        CicsTaskReply reply = fixture.coordinator.launch(request(Optional.empty(), new byte[] {1}));

        ConversationEnvelope stored = fixture.store.load(CONVERSATION, NOW).orElseThrow();
        assertEquals(0, stored.version());
        assertEquals(TransId.of("NXT1"), stored.nextTransaction());
        assertArrayEquals(new byte[] {7}, stored.payload().commarea());
        assertEquals(Optional.of(stored), reply.nextConversation());
        assertEquals(1, fixture.boundary.commits.size());
        assertInstanceOf(ConversationMutation.Create.class, fixture.boundary.commits.get(0));
        assertEquals(1, fixture.boundary.closeCount);
    }

    @Test
    @DisplayName("既存会話をCOBOL起動前にclaimしRETURNで版を一つだけ進める")
    void claimsAndAdvancesExistingConversation() {
        byte[][] observedInput = {null};
        Fixture fixture = fixture((definition, input, task, syncpoints) -> {
            observedInput[0] = input.commarea();
            return new TaskCompletion(Optional.of(TransId.of("NXT1")),
                    CicsPayload.ofCommarea(new byte[] {8}));
        });
        createConversation(fixture.store, "TX01", 0);

        CicsTaskReply reply = fixture.coordinator.launch(request(
                Optional.of(new ConversationReference(CONVERSATION, 0)), new byte[] {4}));

        assertArrayEquals(new byte[] {4}, observedInput[0]);
        assertEquals(1, reply.nextConversation().orElseThrow().version());
        assertEquals(1, fixture.store.load(CONVERSATION, NOW).orElseThrow().version());
        assertInstanceOf(ConversationMutation.Save.class, fixture.boundary.commits.get(0));
    }

    @Test
    @DisplayName("RETURN without TRANSIDは既存会話を同じcommit境界で完了する")
    void completesExistingConversation() {
        Fixture fixture = fixture((definition, input, task, syncpoints) ->
                new TaskCompletion(Optional.empty(), CicsPayload.ofCommarea(new byte[] {9})));
        createConversation(fixture.store, "TX01", 0);

        CicsTaskReply reply = fixture.coordinator.launch(request(
                Optional.of(new ConversationReference(CONVERSATION, 0)), new byte[] {4}));

        assertTrue(reply.nextConversation().isEmpty());
        assertTrue(fixture.store.load(CONVERSATION, NOW).isEmpty());
        assertInstanceOf(ConversationMutation.Complete.class, fixture.boundary.commits.get(0));
    }

    @Test
    @DisplayName("version競合ではprogramとtask resourceを開始しない")
    void rejectsConflictBeforeTaskStart() {
        int[] programCalls = {0};
        Fixture fixture = fixture((definition, input, task, syncpoints) -> {
            programCalls[0]++;
            return new TaskCompletion(Optional.empty(), CicsPayload.empty());
        });
        createConversation(fixture.store, "TX01", 1);

        ConversationConflictException failure = assertThrows(
                ConversationConflictException.class,
                () -> fixture.coordinator.launch(request(
                        Optional.of(new ConversationReference(CONVERSATION, 0)), new byte[0])));

        assertEquals(ConversationClaimStatus.VERSION_CONFLICT, failure.status());
        assertEquals(0, programCalls[0]);
        assertEquals(0, fixture.factory.openCount);
    }

    @Test
    @DisplayName("会話の次TRANSIDと要求が違えばclaimを解放してprogramを開始しない")
    void releasesClaimOnTransactionMismatch() {
        Fixture fixture = fixture((definition, input, task, syncpoints) -> {
            throw new AssertionError("program must not run");
        });
        createConversation(fixture.store, "NXT1", 0);

        assertThrows(CicsTaskStateException.class,
                () -> fixture.coordinator.launch(request(
                        Optional.of(new ConversationReference(CONVERSATION, 0)), new byte[0])));
        assertEquals(0, fixture.factory.openCount);
        assertEquals(ConversationClaimStatus.CLAIMED,
                fixture.store.claim(CONVERSATION, 0, "owner",
                        Duration.ofSeconds(10), NOW).status());
    }

    @Test
    @DisplayName("program異常はUOW abortとlease解放を行い元の版で再試行可能にする")
    void abortsAndReleasesAfterProgramFailure() {
        Fixture fixture = fixture((definition, input, task, syncpoints) -> {
            throw new IllegalStateException("program failed");
        });
        createConversation(fixture.store, "TX01", 0);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.coordinator.launch(request(
                        Optional.of(new ConversationReference(CONVERSATION, 0)), new byte[0])));

        assertEquals("program failed", failure.getMessage());
        assertEquals(1, fixture.boundary.abortCount);
        assertEquals(1, fixture.boundary.closeCount);
        assertEquals(ConversationClaimStatus.CLAIMED,
                fixture.store.claim(CONVERSATION, 0, "owner",
                        Duration.ofSeconds(10), NOW).status());
    }

    @Test
    @DisplayName("CICS ABENDを構造化原因のままtask boundaryのabortへ渡す")
    void passesStructuredAbendToBoundaryAbort() {
        Fixture fixture = fixture((definition, input, task, syncpoints) -> {
            throw new CicsAbend(task.taskId(),
                    AbendCommand.user(CicsAbendCode.of("B123"), false, true));
        });

        CicsAbend failure = assertThrows(CicsAbend.class,
                () -> fixture.coordinator.launch(
                        request(Optional.empty(), new byte[0])));

        assertEquals(failure, fixture.boundary.abortCause);
        assertEquals("B123", failure.code().value());
        assertEquals(1, fixture.boundary.abortCount);
        assertTrue(fixture.boundary.commits.isEmpty());
    }

    @Test
    @DisplayName("明示NOT_COMMITTEDだけabortしUNKNOWNではleaseを保持して自動再実行を防ぐ")
    void distinguishesKnownRollbackFromUnknownCommitOutcome() {
        Fixture notCommitted = fixture(returningNext());
        createConversation(notCommitted.store, "TX01", 0);
        notCommitted.boundary.commitFailure = new CicsTaskCommitException(
                "not committed", CommitFailureState.NOT_COMMITTED, null);

        assertThrows(CicsTaskCommitException.class,
                () -> notCommitted.coordinator.launch(request(
                        Optional.of(new ConversationReference(CONVERSATION, 0)), new byte[0])));
        assertEquals(1, notCommitted.boundary.abortCount);
        assertEquals(ConversationClaimStatus.CLAIMED,
                notCommitted.store.claim(CONVERSATION, 0, "owner",
                        Duration.ofSeconds(10), NOW).status());

        Fixture unknown = fixture(returningNext());
        createConversation(unknown.store, "TX01", 0);
        unknown.boundary.commitFailure = new CicsTaskCommitException(
                "outcome unknown", CommitFailureState.UNKNOWN, null);

        assertThrows(CicsTaskCommitException.class,
                () -> unknown.coordinator.launch(request(
                        Optional.of(new ConversationReference(CONVERSATION, 0)), new byte[0])));
        assertEquals(0, unknown.boundary.abortCount);
        assertEquals(ConversationClaimStatus.ALREADY_LEASED,
                unknown.store.claim(CONVERSATION, 0, "owner",
                        Duration.ofSeconds(10), NOW).status());
    }

    @Test
    @DisplayName("次TRANSIDのpayload上限をcommit前に検査してabortする")
    void validatesPayloadAgainstNextTransaction() {
        Fixture fixture = fixture((definition, input, task, syncpoints) ->
                new TaskCompletion(Optional.of(TransId.of("SMAL")),
                        CicsPayload.ofCommarea(new byte[] {1, 2})));

        assertThrows(CicsInputLimitException.class,
                () -> fixture.coordinator.launch(request(Optional.empty(), new byte[0])));
        assertEquals(1, fixture.boundary.abortCount);
        assertTrue(fixture.boundary.commits.isEmpty());
    }

    @Test
    @DisplayName("programのSYNCPOINTは同じtask boundaryへ委譲する")
    void delegatesSyncpointWithinTaskBoundary() {
        Fixture fixture = fixture((definition, input, task, syncpoints) -> {
            syncpoints.syncpoint(SyncpointAction.COMMIT, task);
            return new TaskCompletion(Optional.empty(), CicsPayload.empty());
        });

        fixture.coordinator.launch(request(Optional.empty(), new byte[0]));

        assertEquals(List.of(SyncpointAction.COMMIT), fixture.boundary.syncpoints);
        assertEquals(1, fixture.boundary.commits.size());
    }

    @Test
    @DisplayName("lease期間がtask timeout以下なら会話をclaimしない")
    void rejectsUnsafeLeasePolicyBeforeClaim() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        createConversation(store, "TX01", 0);
        FakeBoundary boundary = new FakeBoundary(store);
        CicsTaskCoordinator coordinator = coordinator(
                store, new FakeBoundaryFactory(boundary), returningNext(),
                new CicsTaskPolicy(Duration.ofMinutes(5), Duration.ofSeconds(5)));

        assertThrows(IllegalStateException.class,
                () -> coordinator.launch(request(
                        Optional.of(new ConversationReference(CONVERSATION, 0)), new byte[0])));
        assertEquals(ConversationClaimStatus.CLAIMED,
                store.claim(CONVERSATION, 0, "owner", Duration.ofSeconds(10), NOW).status());
    }

    @Test
    @DisplayName("task boundary開始失敗時は取得済みleaseを直接解放する")
    void releasesLeaseWhenOpeningBoundaryFails() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        createConversation(store, "TX01", 0);
        CicsTaskBoundaryFactory failingFactory = (task, definition) -> {
            throw new IllegalStateException("boundary open failed");
        };
        CicsTaskCoordinator coordinator = new CicsTaskCoordinator(
                registry(), store, failingFactory, returningNext(),
                new CicsTaskPolicy(Duration.ofMinutes(5), Duration.ofSeconds(10)),
                () -> CONVERSATION, Clock.fixed(NOW, ZoneOffset.UTC));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> coordinator.launch(request(
                        Optional.of(new ConversationReference(CONVERSATION, 0)), new byte[0])));

        assertEquals("boundary open failed", failure.getMessage());
        assertEquals(ConversationClaimStatus.CLAIMED,
                store.claim(CONVERSATION, 0, "owner", Duration.ofSeconds(10), NOW).status());
    }

    @Test
    @DisplayName("commit成功後のclose失敗は会話を戻さず結果不明応答として通知する")
    void doesNotCompensateCommittedWorkWhenCloseFails() {
        Fixture fixture = fixture(returningNext());
        createConversation(fixture.store, "TX01", 0);
        fixture.boundary.closeFailure = new IllegalStateException("close failed");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.coordinator.launch(request(
                        Optional.of(new ConversationReference(CONVERSATION, 0)), new byte[0])));

        assertEquals("close failed", failure.getMessage());
        assertEquals(0, fixture.boundary.abortCount);
        assertEquals(1, fixture.store.load(CONVERSATION, NOW).orElseThrow().version());
    }

    @Test
    @DisplayName("program障害を主原因に保ちabortとclose障害をsuppressedへ残す")
    void preservesPrimaryFailureAcrossAbortAndCloseFailures() {
        Fixture fixture = fixture((definition, input, task, syncpoints) -> {
            throw new IllegalStateException("program failed");
        });
        fixture.boundary.abortFailure = new IllegalStateException("abort failed");
        fixture.boundary.closeFailure = new IllegalStateException("close failed");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.coordinator.launch(request(Optional.empty(), new byte[0])));

        assertEquals("program failed", failure.getMessage());
        assertEquals(2, failure.getSuppressed().length);
        assertEquals("abort failed", failure.getSuppressed()[0].getMessage());
        assertEquals("close failed", failure.getSuppressed()[1].getMessage());
    }

    private static CicsTaskProgramPort returningNext() {
        return (definition, input, task, syncpoints) ->
                new TaskCompletion(Optional.of(TransId.of("NXT1")), CicsPayload.empty());
    }

    private static Fixture fixture(CicsTaskProgramPort program) {
        InMemoryConversationStore store = new InMemoryConversationStore();
        FakeBoundary boundary = new FakeBoundary(store);
        FakeBoundaryFactory factory = new FakeBoundaryFactory(boundary);
        return new Fixture(store, boundary, factory,
                coordinator(store, factory, program,
                        new CicsTaskPolicy(Duration.ofMinutes(5), Duration.ofSeconds(10))));
    }

    private static CicsTaskCoordinator coordinator(
            InMemoryConversationStore store,
            FakeBoundaryFactory factory,
            CicsTaskProgramPort program,
            CicsTaskPolicy policy) {
        return new CicsTaskCoordinator(
                registry(), store, factory, program, policy, () -> CONVERSATION,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static CicsTransactionRegistry registry() {
        return new CicsTransactionRegistry(List.of(
                definition("TX01", 16), definition("NXT1", 16), definition("SMAL", 1)));
    }

    private static CicsTransactionDefinition definition(String transId, int commareaLimit) {
        return new CicsTransactionDefinition(
                TransId.of(transId), ProgramId.of(transId + "PGM"), Duration.ofSeconds(5),
                commareaLimit, 4, 16, 32, true);
    }

    private static CicsTaskRequest request(
            Optional<ConversationReference> conversation, byte[] commarea) {
        return new CicsTaskRequest(
                "TX01", "owner", CicsPayload.ofCommarea(commarea), conversation,
                new IdempotencyKey("request-1001"));
    }

    private static void createConversation(
            InMemoryConversationStore store, String nextTransId, long version) {
        ConversationEnvelope envelope = new ConversationEnvelope(
                CONVERSATION, 0, "owner", TransId.of(nextTransId),
                CicsPayload.ofCommarea(new byte[] {3}), NOW.plusSeconds(60),
                new IdempotencyKey("request-1000"), Optional.empty());
        store.create(envelope, NOW);
        for (long current = 0; current < version; current++) {
            ConversationLease lease = store.claim(
                    CONVERSATION, current, "owner", Duration.ofSeconds(10), NOW)
                    .lease().orElseThrow();
            envelope = envelope.next(TransId.of(nextTransId), envelope.payload(),
                    NOW.plusSeconds(60), new IdempotencyKey("request-1000"), Optional.empty());
            assertEquals(ConversationMutationResult.SAVED, store.save(lease, envelope, NOW));
        }
    }

    private record Fixture(
            InMemoryConversationStore store,
            FakeBoundary boundary,
            FakeBoundaryFactory factory,
            CicsTaskCoordinator coordinator) {
    }

    private static final class FakeBoundaryFactory implements CicsTaskBoundaryFactory {

        private final FakeBoundary boundary;
        private int openCount;

        private FakeBoundaryFactory(FakeBoundary boundary) {
            this.boundary = boundary;
        }

        @Override
        public CicsTaskBoundary open(
                CicsTaskContext task, CicsTransactionDefinition definition) {
            openCount++;
            return boundary;
        }
    }

    private static final class FakeBoundary implements CicsTaskBoundary {

        private final InMemoryConversationStore store;
        private final List<ConversationMutation> commits = new ArrayList<>();
        private final List<SyncpointAction> syncpoints = new ArrayList<>();
        private RuntimeException commitFailure;
        private RuntimeException abortFailure;
        private RuntimeException closeFailure;
        private Throwable abortCause;
        private int abortCount;
        private int closeCount;

        private FakeBoundary(InMemoryConversationStore store) {
            this.store = store;
        }

        @Override
        public void commit(ConversationMutation mutation, Instant now) {
            if (commitFailure != null) {
                throw commitFailure;
            }
            commits.add(mutation);
            ConversationMutationResult result = switch (mutation) {
                case ConversationMutation.None ignored -> null;
                case ConversationMutation.Create create -> store.create(create.initial(), now);
                case ConversationMutation.Save save -> store.save(save.lease(), save.next(), now);
                case ConversationMutation.Complete complete -> store.complete(complete.lease(), now);
            };
            if (result != null && result != ConversationMutationResult.CREATED
                    && result != ConversationMutationResult.SAVED
                    && result != ConversationMutationResult.COMPLETED) {
                throw new CicsTaskCommitException(
                        "conversation mutation failed: " + result,
                        CommitFailureState.NOT_COMMITTED, null);
            }
        }

        @Override
        public void abort(Optional<ConversationLease> lease, Throwable failure, Instant now) {
            abortCount++;
            abortCause = failure;
            if (abortFailure != null) {
                throw abortFailure;
            }
            lease.ifPresent(value -> store.release(value, now));
        }

        @Override
        public void syncpoint(SyncpointAction action, CicsTaskContext task) {
            syncpoints.add(action);
        }

        @Override
        public void close() {
            closeCount++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
