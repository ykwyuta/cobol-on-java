package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.interop.ProgramId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** principal と CICS の user ID の対応と、transaction の attach の権限 (設計 84、暫定判断 P-145)。 */
@Tag("V1")
class CicsSecurityTest {

    private static final Instant NOW = Instant.parse("2026-09-15T08:00:00Z");

    @Test
    @DisplayName("権限を構成しなければprincipal名が形に収まるときだけuser IDにし、transactionはすべて許し、代理は同じuser IDだけ")
    void derivesUserIdsWithoutConfiguration() {
        CicsSecurityPort security = CicsSecurityPort.derived();

        assertEquals(Optional.of("ALICE"), security.userIdOf("alice"));
        assertEquals(Optional.empty(), security.userIdOf("alice@example.com"));
        assertEquals(Optional.empty(), security.userIdOf("verylongname"));
        assertTrue(security.mayAttach(Optional.empty(), TransId.of("TX01")));
        assertTrue(security.maySurrogate(Optional.of("ALICE"), "ALICE"));
        assertFalse(security.maySurrogate(Optional.of("ALICE"), "BATCH01"));
        assertFalse(security.maySurrogate(Optional.empty(), "ALICE"));
    }

    @Test
    @DisplayName("coordinatorはprincipalからuser IDを決めてtaskに渡し、起こせないtransactionはtaskを動かさず断る。要求のuser IDはprincipalより優先する")
    void coordinatorMapsUsersAndChecksAttach() {
        CicsSecurityPort security = new CicsSecurityPort() {
            @Override
            public Optional<String> userIdOf(String principal) {
                return principal.equals("alice") ? Optional.of("ALICE01") : Optional.empty();
            }

            @Override
            public boolean mayAttach(Optional<String> userId, TransId transaction) {
                return userId.equals(Optional.of("ALICE01")) && transaction.value().equals("TX01");
            }

            @Override
            public boolean maySurrogate(Optional<String> userId, String surrogateUserId) {
                return false;
            }
        };
        AtomicInteger runs = new AtomicInteger();
        CicsTaskProgramPort program = (definition, input, task, syncpoints) -> {
            runs.incrementAndGet();
            return new TaskCompletion(Optional.empty(), CicsPayload.ofCommarea(
                    task.userId().orElse("-").getBytes(StandardCharsets.US_ASCII)));
        };
        ConversationStorePort conversations = new InMemoryConversationStore();
        CicsOutcomeStorePort outcomes = CicsOutcomeStorePort.inMemory();
        CicsTaskCoordinator coordinator = new CicsTaskCoordinator(new CicsTransactionRegistry(List.of(
                new CicsTransactionDefinition(TransId.of("TX01"), ProgramId.of("PGM1"), Duration.ofSeconds(5),
                        16, 0, 0, 0, true),
                new CicsTransactionDefinition(TransId.of("TX02"), ProgramId.of("PGM2"), Duration.ofSeconds(5),
                        16, 0, 0, 0, true))),
                conversations, new NonRecoverableTaskBoundaryFactory(conversations, outcomes), program,
                new CicsTaskPolicy(Duration.ofMinutes(5), Duration.ofSeconds(30)), ConversationId::create,
                Clock.fixed(NOW, ZoneOffset.UTC), outcomes, security);

        CicsTaskReply reply = coordinator.launch(new CicsTaskRequest("TX01", "alice", CicsPayload.empty(),
                Optional.empty(), new IdempotencyKey("sec-key-0001"), Optional.empty(), Optional.empty()));
        assertEquals("ALICE01", new String(reply.payload().commarea(), StandardCharsets.US_ASCII));

        assertThrows(TransactionNotAuthorizedException.class, () -> coordinator.launch(new CicsTaskRequest("TX02",
                "alice", CicsPayload.empty(), Optional.empty(), new IdempotencyKey("sec-key-0002"),
                Optional.empty(), Optional.empty())));
        assertThrows(TransactionNotAuthorizedException.class, () -> coordinator.launch(new CicsTaskRequest("TX01",
                "mallory", CicsPayload.empty(), Optional.empty(), new IdempotencyKey("sec-key-0003"),
                Optional.empty(), Optional.empty())));
        // START の USERID のように要求が user ID を持てば、principal からは決めない
        assertThrows(TransactionNotAuthorizedException.class, () -> coordinator.launch(new CicsTaskRequest("TX01",
                "alice", CicsPayload.empty(), Optional.empty(), new IdempotencyKey("sec-key-0004"),
                Optional.empty(), Optional.empty(), Optional.of("BATCH01"))));
        assertEquals(1, runs.get());
    }
}
