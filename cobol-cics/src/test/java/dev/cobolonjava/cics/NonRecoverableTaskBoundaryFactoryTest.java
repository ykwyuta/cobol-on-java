package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.interop.ProgramId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 回復可能な資源を持たない task の境界。 */
@Tag("V1")
class NonRecoverableTaskBoundaryFactoryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    @Test
    @DisplayName("会話を作り、次のtaskで続けて保存し、RETURNで完了する")
    void appliesConversationMutations() {
        InMemoryConversationStore store = new InMemoryConversationStore();
        CicsTransactionRegistry registry = new CicsTransactionRegistry(List.of(new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("PGM1"), Duration.ofSeconds(5), 16, 0, 0, 0, true)));
        int[] calls = {0};
        CicsTaskProgramPort program = (definition, input, task, syncpoints) -> {
            syncpoints.syncpoint(SyncpointAction.ROLLBACK, task);
            return ++calls[0] < 3
                    ? new TaskCompletion(Optional.of(TransId.of("TX01")), CicsPayload.ofCommarea(new byte[] {7}))
                    : new TaskCompletion(Optional.empty(), CicsPayload.empty());
        };
        ConversationId id = ConversationId.create();
        CicsTaskCoordinator coordinator = new CicsTaskCoordinator(registry, store,
                new NonRecoverableTaskBoundaryFactory(store), program,
                new CicsTaskPolicy(Duration.ofMinutes(5), Duration.ofSeconds(10)), () -> id,
                Clock.fixed(NOW, ZoneOffset.UTC));

        CicsTaskReply first = coordinator.launch(new CicsTaskRequest("TX01", "owner", CicsPayload.empty(),
                Optional.empty(), new IdempotencyKey("request-0001")));
        ConversationEnvelope created = first.nextConversation().orElseThrow();
        assertEquals(0, created.version());

        CicsTaskReply second = coordinator.launch(new CicsTaskRequest("TX01", "owner", created.payload(),
                Optional.of(new ConversationReference(id, 0)), new IdempotencyKey("request-0002")));
        assertEquals(1, second.nextConversation().orElseThrow().version());

        CicsTaskReply third = coordinator.launch(new CicsTaskRequest("TX01", "owner", CicsPayload.empty(),
                Optional.of(new ConversationReference(id, 1)), new IdempotencyKey("request-0003")));
        assertTrue(third.nextConversation().isEmpty());
        assertTrue(store.load(id, NOW).isEmpty());
    }
}
