package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 端末の登録 (設計 83 §4、暫定判断 P-144)。 */
@Tag("V1")
class InMemoryCicsTerminalRegistryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T02:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    private final CicsTerminalRegistryPort registry = CicsTerminalRegistryPort.inMemory();

    @Test
    @DisplayName("端末はWと36進3桁の重ならない名前で登録し、期限とownerの照合で延ばす")
    void registersDistinctTerminals() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String id = registry.register("alice", NOW.plusSeconds(60), NOW);
            assertTrue(id.matches("W[0-9A-Z]{3}"), id);
            assertTrue(ids.add(id));
        }
        String id = ids.iterator().next();

        assertFalse(registry.touch(id, "bob", NOW.plusSeconds(600), NOW));
        assertTrue(registry.touch(id, "alice", NOW.plusSeconds(600), NOW));
        assertTrue(registry.find(id, NOW.plusSeconds(300)).isPresent());
        assertTrue(registry.find(id, NOW.plusSeconds(600)).isEmpty());
        assertFalse(registry.touch(id, "alice", NOW.plusSeconds(900), NOW.plusSeconds(600)));
    }

    @Test
    @DisplayName("1つの端末のleaseは同時に1つだけで、ownerが違えば取れず、leaseを持つ要求だけが会話を書き換える")
    void leasesTerminalsExclusively() {
        String id = registry.register("alice", NOW.plusSeconds(600), NOW);
        CicsTerminalRegistryPort.TerminalConversation conversation = new CicsTerminalRegistryPort.TerminalConversation(
                new ConversationId("conversation_term01"), 3, TransId.of("TX01"));

        assertTrue(registry.lease(id, "bob", LEASE, NOW).isEmpty());
        CicsTerminalRegistryPort.TerminalLease lease = registry.lease(id, "alice", LEASE, NOW).orElseThrow();
        assertTrue(registry.lease(id, "alice", LEASE, NOW).isEmpty());
        assertTrue(registry.find(id, NOW).orElseThrow().leased());

        assertTrue(registry.setConversation(lease, Optional.of(conversation), NOW));
        assertEquals(Optional.of(conversation), registry.find(id, NOW).orElseThrow().conversation());
        assertTrue(registry.release(lease, NOW));
        assertFalse(registry.setConversation(lease, Optional.empty(), NOW));
        assertEquals(Optional.of(conversation), registry.find(id, NOW).orElseThrow().conversation());

        // lease の期限が過ぎれば別の要求が取り直せ、古い lease では書き換えられない
        CicsTerminalRegistryPort.TerminalLease stale = registry.lease(id, "alice", LEASE, NOW).orElseThrow();
        Instant later = NOW.plusSeconds(31);
        CicsTerminalRegistryPort.TerminalLease fresh = registry.lease(id, "alice", LEASE, later).orElseThrow();
        assertNotEquals(stale.token(), fresh.token());
        assertFalse(registry.setConversation(stale, Optional.empty(), later));
        assertFalse(registry.release(stale, later));
    }

    @Test
    @DisplayName("task が動いている端末は消さず、期限の過ぎた端末の名前は登録し直せる")
    void removesOnlyIdleTerminals() {
        String id = registry.register("alice", NOW.plusSeconds(60), NOW);
        CicsTerminalRegistryPort.TerminalLease lease = registry.lease(id, "alice", LEASE, NOW).orElseThrow();

        assertFalse(registry.remove(id, NOW));
        assertTrue(registry.release(lease, NOW));
        assertTrue(registry.remove(id, NOW));
        assertTrue(registry.find(id, NOW).isEmpty());

        registry.register("alice", NOW.plusSeconds(60), NOW);
        assertEquals(1, registry.purgeExpired(NOW.plusSeconds(60)));
    }
}
