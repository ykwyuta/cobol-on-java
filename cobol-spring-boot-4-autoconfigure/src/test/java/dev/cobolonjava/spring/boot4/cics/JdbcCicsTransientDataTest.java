package dev.cobolonjava.spring.boot4.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsResponseCode;
import dev.cobolonjava.cics.CicsTerminalRegistryPort;
import dev.cobolonjava.cics.CicsTerminalScreen;
import dev.cobolonjava.cics.CicsTerminalTasks;
import dev.cobolonjava.cics.ConversationEnvelope;
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.IdempotencyKey;
import dev.cobolonjava.cics.CicsTransientDataPort;
import dev.cobolonjava.cics.CicsTransientDataQueueDefinition;
import dev.cobolonjava.cics.CicsTransientDataQueueDefinition.Facility;
import dev.cobolonjava.cics.CicsTransientDataTrigger;
import dev.cobolonjava.cics.TransId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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

/** 一時データのキューの JDBC の置き場と trigger level (設計 83 §6・§8、暫定判断 P-144)。 */
@Tag("V1")
class JdbcCicsTransientDataTest {

    private static final Instant NOW = Instant.parse("2026-09-15T04:00:00Z");
    private static final List<CicsTransientDataQueueDefinition> QUEUES =
            List.of(new CicsTransientDataQueueDefinition("CSMT", 6));

    private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
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
    /** 起こした trigger の task。試験が走らせるまで終わらない。 */
    private final List<Runnable> held = new ArrayList<>();
    private final ConcurrentLinkedQueue<CicsTransientDataTrigger> triggered = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean abend = new AtomicBoolean();

    private DataSource dataSource;
    /** 同じ DataSource に向けた 2 つのキュー。2 つの JVM に見立てる。 */
    private JdbcCicsTransientData first;
    private JdbcCicsTransientData second;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:td-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new ClassPathResource(JdbcConversationStore.SCHEMA)).execute(dataSource);
        first = new JdbcCicsTransientData(dataSource, new JdbcTransactionManager(dataSource), QUEUES);
        second = new JdbcCicsTransientData(dataSource, new JdbcTransactionManager(dataSource), QUEUES);
    }

    private JdbcCicsTransientData triggering(int level) {
        return new JdbcCicsTransientData(dataSource, new JdbcTransactionManager(dataSource),
                List.of(new CicsTransientDataQueueDefinition("ATIQ", 8, level, Optional.of(TransId.of("TRG1")),
                        Facility.FILE, Optional.empty(), Optional.of("ATIUSER"))),
                clock, "cics-region", Optional.empty(), null, Duration.ofSeconds(30), trigger -> {
                    triggered.add(trigger);
                    if (abend.get()) {
                        throw new IllegalStateException("trigger task abended");
                    }
                    return CicsTerminalTasks.Outcome.none();
                }, Duration.ofMinutes(5), Duration.ofSeconds(1), held::add);
    }

    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static String read(CicsTransientDataPort queue, String name) {
        CicsTransientDataPort.Read read = queue.read(name);
        return read.response() == CicsResponseCode.NORMAL ? new String(read.data(), StandardCharsets.US_ASCII) : null;
    }

    private void runHeld() {
        List<Runnable> tasks = new ArrayList<>(held);
        held.clear();
        tasks.forEach(Runnable::run);
    }

    private String state() {
        return state("ATIQ");
    }

    private String state(String queue) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT TRIGGER_STATE FROM COBOL_TD_QUEUE WHERE QUEUE_NAME = ?", String.class, queue).strip();
    }

    @Test
    @DisplayName("ATIFACILITY(TERMINAL)は端末が登録されて空くまで待ち、端末の利用者でtaskを起こし、空にせず終われば同じtaskをまた起こす")
    void startsTriggerTasksOnTerminals() {
        JdbcTerminalRegistry terminals = new JdbcTerminalRegistry(dataSource, new JdbcTransactionManager(dataSource));
        AtomicReference<Optional<ConversationEnvelope>> reply = new AtomicReference<>(Optional.empty());
        JdbcCicsTransientData queue = new JdbcCicsTransientData(dataSource, new JdbcTransactionManager(dataSource),
                List.of(new CicsTransientDataQueueDefinition("PRTQ", 8, 1, Optional.of(TransId.of("PRT1")),
                        Facility.TERMINAL, Optional.of("PRT1"), Optional.empty())),
                clock, null, Optional.empty(), terminals, Duration.ofSeconds(30), trigger -> {
                    triggered.add(trigger);
                    return new CicsTerminalTasks.Outcome(reply.get(),
                            Optional.of(new CicsTerminalScreen.TextScreen("PRINTED", true, false)));
                }, Duration.ofMinutes(5), Duration.ofSeconds(1), held::add);

        queue.write("PRTQ", text("LINE1"));
        // 端末がまだ登録されていない
        assertEquals(0, queue.dispatchDue());
        assertTrue(terminals.registerNamed("PRT1", "alice", NOW.plusSeconds(3600), NOW));
        CicsTerminalRegistryPort.TerminalLease busy = terminals.lease("PRT1", "alice", Duration.ofSeconds(30), NOW)
                .orElseThrow();
        assertEquals(0, queue.dispatchDue());
        terminals.release(busy, NOW);

        assertEquals(1, queue.dispatchDue());
        assertTrue(terminals.find("PRT1", NOW).orElseThrow().leased());
        reply.set(Optional.of(new ConversationEnvelope(new ConversationId("conversation_ati01"), 0, "alice",
                TransId.of("PRT2"), CicsPayload.empty(), NOW.plusSeconds(600), new IdempotencyKey("ati-conversation-1"),
                Optional.empty())));
        runHeld();

        CicsTransientDataTrigger trigger = triggered.poll();
        assertEquals(Optional.of("PRT1"), trigger.terminalId());
        assertEquals("alice", trigger.owner());
        assertEquals(Optional.of("ALICE"), trigger.userId());
        // 空にせず正常に終わったので、同じ task をまた起こす
        assertEquals("PENDING", state("PRTQ"));
        CicsTerminalRegistryPort.Terminal after = terminals.find("PRT1", NOW).orElseThrow();
        assertFalse(after.leased());
        assertEquals("PRT2", after.conversation().orElseThrow().nextTransaction().value());
        assertEquals(1, after.screenVersion());
        assertEquals("PRINTED", ((CicsTerminalScreen.TextScreen) terminals.screen("PRT1", NOW).orElseThrow().screen())
                .text());
        // 端末が疑似会話の途中なので、会話が終わるまで起こさない
        assertEquals(0, queue.dispatchDue());
    }

    @Test
    @DisplayName("2つのJVMが書いたrecordを先に書いた順に取り出し、空はQZERO、定義の無いキューはQIDERR、長すぎればLENGERR")
    void sharesQueueAcrossJvmsInWriteOrder() {
        assertEquals(CicsResponseCode.NORMAL, first.write("CSMT", text("one")).response());
        assertEquals(CicsResponseCode.NORMAL, second.write("CSMT", text("second")).response());
        assertEquals(CicsResponseCode.LENGERR, first.write("CSMT", text("toolong")).response());
        assertEquals(CicsResponseCode.QIDERR, first.write("NOPE", text("one")).response());

        assertEquals("one", read(second, "CSMT"));
        assertEquals("second", read(first, "CSMT"));
        assertEquals(CicsResponseCode.QZERO, first.read("CSMT").response());

        second.write("CSMT", text("again"));
        assertEquals(CicsResponseCode.NORMAL, first.delete("CSMT").response());
        assertEquals(CicsResponseCode.QZERO, second.read("CSMT").response());
        assertEquals(CicsResponseCode.QIDERR, second.delete("NOPE").response());
    }

    @Test
    @DisplayName("2つのJVMの4つのtaskが同時に読んでも、どのrecordも1度だけ取り出す")
    void readsEachRecordOnceUnderConcurrency() throws Exception {
        List<String> written = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String value = String.format("R%03d", i);
            written.add(value);
            (i % 2 == 0 ? first : second).write("CSMT", text(value));
        }
        ConcurrentLinkedQueue<String> taken = new ConcurrentLinkedQueue<>();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> readers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                CicsTransientDataPort queue = i % 2 == 0 ? first : second;
                readers.add(executor.submit(() -> {
                    go.await();
                    for (String value = read(queue, "CSMT"); value != null; value = read(queue, "CSMT")) {
                        taken.add(value);
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> reader : readers) {
                reader.get();
            }
        } finally {
            executor.shutdownNow();
        }
        List<String> sorted = new ArrayList<>(taken);
        sorted.sort(null);
        assertEquals(written, sorted);
    }

    @Test
    @DisplayName("trigger levelに達したら2つのJVMのどちらかが1度だけ起こし、taskの間は次を起こさず、QZEROで再びarmし、taskの終わりに達していれば次を起こす")
    void startsTriggerTasksSequentially() {
        JdbcCicsTransientData a = triggering(2);
        JdbcCicsTransientData b = triggering(2);

        a.write("ATIQ", text("A"));
        assertEquals(0, a.dispatchDue() + b.dispatchDue());
        b.write("ATIQ", text("B"));
        assertEquals(1, b.dispatchDue() + a.dispatchDue());
        assertEquals("ATTACHED", state());

        // task が動いている間は、数が trigger level を越えても次を起こさない
        a.write("ATIQ", text("C"));
        assertEquals(0, a.dispatchDue() + b.dispatchDue());
        assertEquals("A", read(b, "ATIQ"));
        assertEquals("B", read(a, "ATIQ"));
        assertEquals("C", read(b, "ATIQ"));
        assertEquals(CicsResponseCode.QZERO, b.read("ATIQ").response());
        assertEquals("ARMED", state());
        a.write("ATIQ", text("D"));
        a.write("ATIQ", text("E"));
        assertEquals("PENDING", state());
        assertEquals(0, a.dispatchDue() + b.dispatchDue());

        runHeld();
        CicsTransientDataTrigger trigger = triggered.poll();
        assertEquals("ATIQ", trigger.queue());
        assertEquals("TRG1", trigger.transaction().value());
        assertEquals("cics-region", trigger.owner());
        assertEquals(Optional.of("ATIUSER"), trigger.userId());

        assertEquals(1, b.dispatchDue());
        runHeld();
        // FILE の task が空にせず正常に終われば trigger は戻り、数が trigger level 以上なので次を起こす
        assertEquals("PENDING", state());
    }

    @Test
    @DisplayName("空にする前のABENDと期限の過ぎたtaskは、次のQZEROまで次のtaskを起こさない")
    void blocksTriggerAfterAbendUntilQzero() {
        JdbcCicsTransientData queue = triggering(1);
        abend.set(true);
        queue.write("ATIQ", text("X"));
        assertEquals(1, queue.dispatchDue());
        runHeld();
        assertEquals("BLOCKED", state());
        queue.write("ATIQ", text("Y"));
        assertEquals(0, queue.dispatchDue());

        assertEquals("X", read(queue, "ATIQ"));
        assertEquals("Y", read(queue, "ATIQ"));
        assertEquals(CicsResponseCode.QZERO, queue.read("ATIQ").response());
        abend.set(false);
        queue.write("ATIQ", text("Z"));
        assertEquals(1, queue.dispatchDue());

        // 動いている task の token の期限が過ぎれば、終わったか分からないので次の QZERO まで起こさない
        time.set(NOW.plus(Duration.ofMinutes(6)));
        assertEquals(0, queue.dispatchDue());
        assertEquals("BLOCKED", state());
        // 期限で片付けたあとに古い task が終わっても、状態を変えない
        runHeld();
        assertEquals("BLOCKED", state());
    }

    @Test
    @DisplayName("trigger levelの定義は属性の組を確かめ、ATIFACILITY(TERMINAL)とUSERIDの無いFILEと1つのJVMの中のキューは断る")
    void validatesTriggerDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> new CicsTransientDataQueueDefinition("ATIQ", 8, 1,
                Optional.empty(), Facility.FILE, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new CicsTransientDataQueueDefinition("ATIQ", 8, 32768,
                Optional.of(TransId.of("TRG1")), Facility.FILE, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new CicsTransientDataQueueDefinition("ATIQ", 8, 1,
                Optional.of(TransId.of("TRG1")), Facility.FILE, Optional.of("W001"), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new CicsTransientDataQueueDefinition("ATIQ", 8, 1,
                Optional.of(TransId.of("TRG1")), Facility.TERMINAL, Optional.empty(), Optional.of("ATIUSER")));
        assertEquals("ATIQ", new CicsTransientDataQueueDefinition("ATIQ", 8, 1, Optional.of(TransId.of("TRG1")),
                Facility.TERMINAL, Optional.empty(), Optional.empty()).terminalId());

        CicsTransientDataQueueDefinition terminal = new CicsTransientDataQueueDefinition("ATIQ", 8, 1,
                Optional.of(TransId.of("TRG1")), Facility.TERMINAL, Optional.of("W001"), Optional.empty());
        // TERMINAL は端末の登録が要る
        assertThrows(IllegalArgumentException.class, () -> new JdbcCicsTransientData(dataSource,
                new JdbcTransactionManager(dataSource), List.of(terminal), clock, "cics-region", Optional.empty(),
                null, Duration.ofSeconds(30), trigger -> CicsTerminalTasks.Outcome.none(), Duration.ofMinutes(5),
                Duration.ofSeconds(1)));
        CicsTransientDataQueueDefinition noUser = new CicsTransientDataQueueDefinition("ATIQ", 8, 1,
                Optional.of(TransId.of("TRG1")), Facility.FILE, Optional.empty(), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> new JdbcCicsTransientData(dataSource,
                new JdbcTransactionManager(dataSource), List.of(noUser), clock, "cics-region", Optional.empty(),
                null, Duration.ofSeconds(30), trigger -> CicsTerminalTasks.Outcome.none(), Duration.ofMinutes(5),
                Duration.ofSeconds(1)));
        new JdbcCicsTransientData(dataSource, new JdbcTransactionManager(dataSource), List.of(noUser), clock,
                "cics-region", Optional.of("DEFAULT"), null, Duration.ofSeconds(30), trigger -> CicsTerminalTasks.Outcome.none(),
                Duration.ofMinutes(5), Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> new JdbcCicsTransientData(dataSource,
                new JdbcTransactionManager(dataSource), List.of(noUser)));
        assertThrows(IllegalArgumentException.class, () -> CicsTransientDataPort.inMemory(List.of(noUser)));
    }
}
