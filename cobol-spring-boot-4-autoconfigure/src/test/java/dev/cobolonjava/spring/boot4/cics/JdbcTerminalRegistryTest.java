package dev.cobolonjava.spring.boot4.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.CicsTerminalRegistryPort;
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.TransId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** 端末の登録の JDBC の置き場 (設計 83 §4、暫定判断 P-144)。 */
@Tag("V1")
class JdbcTerminalRegistryTest {

    private static final Instant NOW = Instant.parse("2026-09-15T02:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    /** 同じ DataSource に向けた 2 つの登録。2 つの JVM に見立てる。 */
    private JdbcTerminalRegistry first;
    private JdbcTerminalRegistry second;

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:terminal-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource(JdbcConversationStore.SCHEMA)).execute(dataSource);
        first = new JdbcTerminalRegistry(dataSource, new JdbcTransactionManager(dataSource));
        second = new JdbcTerminalRegistry(dataSource, new JdbcTransactionManager(dataSource));
    }

    @Test
    @DisplayName("2つのJVMが登録した端末は重ならず、どちらのJVMからも見え、ownerの照合で期限を延ばす")
    void registersTerminalsAcrossJvms() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            assertTrue(ids.add(first.register("alice", NOW.plusSeconds(60), NOW)));
            assertTrue(ids.add(second.register("bob", NOW.plusSeconds(60), NOW)));
        }
        String id = first.register("alice", NOW.plusSeconds(60), NOW);

        assertEquals("alice", second.find(id, NOW).orElseThrow().owner());
        assertFalse(second.touch(id, "bob", NOW.plusSeconds(600), NOW));
        assertTrue(second.touch(id, "alice", NOW.plusSeconds(600), NOW));
        assertTrue(first.find(id, NOW.plusSeconds(300)).isPresent());
        assertTrue(first.find(id, NOW.plusSeconds(600)).isEmpty());
    }

    @Test
    @DisplayName("同じ端末を2つのJVMから同時にleaseしても1つだけが取れ、leaseを持つ側だけが会話を書き換える")
    void leasesTerminalOnceAcrossJvms() throws Exception {
        String id = first.register("alice", NOW.plusSeconds(600), NOW);
        int contenders = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        List<Future<Optional<CicsTerminalRegistryPort.TerminalLease>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < contenders; i++) {
                JdbcTerminalRegistry registry = i % 2 == 0 ? first : second;
                results.add(executor.submit(() -> {
                    start.await();
                    return registry.lease(id, "alice", LEASE, NOW);
                }));
            }
            start.countDown();
            List<CicsTerminalRegistryPort.TerminalLease> leases = new ArrayList<>();
            for (Future<Optional<CicsTerminalRegistryPort.TerminalLease>> result : results) {
                result.get().ifPresent(leases::add);
            }
            assertEquals(1, leases.size());

            CicsTerminalRegistryPort.TerminalLease lease = leases.get(0);
            CicsTerminalRegistryPort.TerminalConversation conversation = new CicsTerminalRegistryPort.TerminalConversation(
                    new ConversationId("conversation_term02"), 2, TransId.of("TX01"));
            assertTrue(second.setConversation(lease, Optional.of(conversation), NOW));
            assertEquals(Optional.of(conversation), first.find(id, NOW).orElseThrow().conversation());
            assertFalse(first.remove(id, NOW));
            assertTrue(first.release(lease, NOW));
            assertFalse(second.setConversation(lease, Optional.empty(), NOW));
            assertTrue(second.lease(id, "bob", LEASE, NOW).isEmpty());
            assertTrue(second.remove(id, NOW));
            assertTrue(first.find(id, NOW).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("固定の端末名は2つのJVMから同じownerなら使い回し、別のownerが使っていれば登録せず、期限が過ぎれば別のownerが使える")
    void registersNamedTerminalsAcrossJvms() {
        assertTrue(first.registerNamed("PRT1", "alice", NOW.plusSeconds(60), NOW));
        assertTrue(second.registerNamed("PRT1", "alice", NOW.plusSeconds(600), NOW));
        assertEquals(NOW.plusSeconds(600), first.find("PRT1", NOW).orElseThrow().expiresAt());
        assertFalse(second.registerNamed("PRT1", "bob", NOW.plusSeconds(60), NOW));
        assertTrue(second.registerNamed("PRT1", "bob", NOW.plusSeconds(1200), NOW.plusSeconds(600)));
        assertEquals("bob", first.find("PRT1", NOW.plusSeconds(600)).orElseThrow().owner());
    }

    @Test
    @DisplayName("期限の過ぎた端末は見えず、purgeで消える")
    void purgesExpiredTerminals() {
        String id = first.register("alice", NOW.plusSeconds(60), NOW);
        assertTrue(second.find(id, NOW.plusSeconds(60)).isEmpty());
        assertTrue(second.lease(id, "alice", LEASE, NOW.plusSeconds(60)).isEmpty());
        assertEquals(1, second.purgeExpired(NOW.plusSeconds(60)));
    }
}
