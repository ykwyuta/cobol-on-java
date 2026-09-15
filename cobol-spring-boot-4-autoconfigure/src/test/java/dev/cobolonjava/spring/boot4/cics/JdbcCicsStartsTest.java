package dev.cobolonjava.spring.boot4.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.CicsResponseCode;
import dev.cobolonjava.cics.CicsStartData;
import dev.cobolonjava.cics.CicsTaskStateException;
import dev.cobolonjava.cics.TransId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** START の JDBC の置き場と dispatcher (設計 83 §8、暫定判断 P-144)。 */
@Tag("V1")
class JdbcCicsStartsTest {

    private static final Instant NOW = Instant.parse("2026-09-15T03:00:00Z");

    private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
    private final ConcurrentLinkedQueue<CicsStartData> launched = new ConcurrentLinkedQueue<>();
    /** 同じ DataSource に向けた 2 つの START の置き場。2 つの JVM に見立てる。 */
    private JdbcCicsStarts first;
    private JdbcCicsStarts second;

    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return time.get();
        }
    };

    @BeforeEach
    void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:start-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new ClassPathResource(JdbcConversationStore.SCHEMA)).execute(dataSource);
        first = starts(dataSource);
        second = starts(dataSource);
    }

    private JdbcCicsStarts starts(DataSource dataSource) {
        return new JdbcCicsStarts(dataSource, new JdbcTransactionManager(dataSource), clock,
                transId -> !transId.value().equals("NONE"), launched::add, Duration.ofSeconds(1), Runnable::run);
    }

    private static CicsStartData start(String requestId, byte[] data) {
        return new CicsStartData(requestId, TransId.of("TX01"), data, Optional.of("TX02"), Optional.of("T001"),
                Optional.of("QUEUE001"), "alice", Optional.of("ALICE"));
    }

    @Test
    @DisplayName("満了したSTARTは2つのJVMのどちらかが1度だけ起こし、FROMとRTRANSIDとownerを保つ")
    void dispatchesExpiredStartOnceAcrossJvms() throws Exception {
        assertEquals(CicsResponseCode.NORMAL, first.start(NOW.plusSeconds(10), start("REQ00001",
                new byte[] {1, 2, 3})).response());
        assertEquals(0, second.dispatchDue());
        assertTrue(launched.isEmpty());

        time.set(NOW.plusSeconds(10));
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = executor.submit(() -> {
                go.await();
                return first.dispatchDue();
            });
            Future<Integer> b = executor.submit(() -> {
                go.await();
                return second.dispatchDue();
            });
            go.countDown();
            assertEquals(1, a.get() + b.get());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, launched.size());
        CicsStartData data = launched.peek();
        assertEquals("REQ00001", data.requestId());
        assertArrayEquals(new byte[] {1, 2, 3}, data.data().orElseThrow());
        assertEquals(Optional.of("TX02"), data.returnTransaction());
        assertEquals(Optional.of("T001"), data.returnTerminal());
        assertEquals(Optional.of("QUEUE001"), data.queue());
        assertEquals("alice", data.owner());
        assertEquals(Optional.of("ALICE"), data.userId());
        assertEquals(0, first.dispatchDue() + second.dispatchDue());
    }

    @Test
    @DisplayName("REQIDの重なりはFROMがあればIOERR、定義の無いTRANSIDはTRANSIDERR、CANCELは未満了だけを別のJVMからも取り消す")
    void checksConditionsAndCancelsAcrossJvms() {
        assertEquals(CicsResponseCode.NORMAL, first.start(NOW.plusSeconds(60), start("REQ00002", new byte[] {1}))
                .response());
        assertEquals(CicsResponseCode.IOERR, second.start(NOW.plusSeconds(60), start("REQ00002", new byte[] {2}))
                .response());
        assertThrows(CicsTaskStateException.class, () -> second.start(NOW.plusSeconds(60), start("REQ00002", null)));
        assertEquals(CicsResponseCode.TRANSIDERR, first.start(NOW, new CicsStartData("REQ00009", TransId.of("NONE"),
                null, Optional.empty(), Optional.empty(), Optional.empty(), "alice", Optional.empty())).response());

        assertEquals(CicsResponseCode.NORMAL, second.cancel("REQ00002").response());
        assertEquals(CicsResponseCode.NOTFND, first.cancel("REQ00002").response());

        // 満了した START は、dispatcher がまだ起こしていなくても取り消せない
        first.start(NOW.plusSeconds(5), start("REQ00003", null));
        time.set(NOW.plusSeconds(5));
        assertEquals(CicsResponseCode.NOTFND, second.cancel("REQ00003").response());
        assertEquals(1, second.dispatchDue());

        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            ids.add(first.newRequestId());
            ids.add(second.newRequestId());
        }
        assertEquals(100, ids.size());
        assertTrue(ids.stream().allMatch(id -> id.matches("JV[0-9A-Z]{6}")));
    }
}
