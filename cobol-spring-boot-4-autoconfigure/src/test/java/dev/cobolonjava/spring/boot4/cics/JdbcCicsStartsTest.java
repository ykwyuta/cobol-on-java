package dev.cobolonjava.spring.boot4.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsResponseCode;
import dev.cobolonjava.cics.CicsStartData;
import dev.cobolonjava.cics.CicsTaskStateException;
import dev.cobolonjava.cics.CicsTerminalRegistryPort;
import dev.cobolonjava.cics.CicsTerminalScreen;
import dev.cobolonjava.cics.CicsTerminalTasks;
import dev.cobolonjava.cics.ConversationEnvelope;
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.IdempotencyKey;
import dev.cobolonjava.cics.TransId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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

/** START の JDBC の置き場と dispatcher (設計 83 §5・§8、暫定判断 P-144)。 */
@Tag("V1")
class JdbcCicsStartsTest {

    private static final Instant NOW = Instant.parse("2026-09-15T03:00:00Z");

    private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
    private final ConcurrentLinkedQueue<CicsStartData> launched = new ConcurrentLinkedQueue<>();
    /** 起きた task が返す端末の次の疑似会話。 */
    private Function<CicsStartData, CicsTerminalTasks.Outcome> replies = data -> CicsTerminalTasks.Outcome.none();
    private JdbcTemplate jdbc;
    private JdbcTerminalRegistry terminals;
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

    private TestDatabase database;

    /** 試験する database。既定は H2。実 Db2 の試験はここを替える。 */
    TestDatabase openDatabase() {
        return TestDatabase.h2("start");
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
        terminals = new JdbcTerminalRegistry(dataSource, new JdbcTransactionManager(dataSource));
        first = starts(dataSource);
        second = starts(dataSource);
    }

    private JdbcCicsStarts starts(DataSource dataSource) {
        return new JdbcCicsStarts(dataSource, new JdbcTransactionManager(dataSource), clock,
                transId -> !transId.value().equals("NONE"), terminals, Duration.ofSeconds(30),
                data -> {
                    launched.add(data);
                    return replies.apply(data);
                }, Duration.ofSeconds(1), Runnable::run);
    }

    private static CicsStartData start(String requestId, byte[] data) {
        return new CicsStartData(requestId, TransId.of("TX01"), data, Optional.of("TX02"), Optional.of("T001"),
                Optional.of("QUEUE001"), "alice", Optional.of("ALICE"));
    }

    private static CicsStartData toTerminal(String requestId, String transaction, String terminal, String owner,
                                            byte[] data) {
        return new CicsStartData(requestId, TransId.of(transaction), data, Optional.empty(), Optional.empty(),
                Optional.empty(), owner, Optional.empty(), Optional.of(terminal));
    }

    @Test
    @DisplayName("START CHANNELのchannelの写しは行に置き、別のJVMが起こすtaskの入力のchannelになる")
    void keepsChannelCopyAcrossJvms() {
        CicsStartData channel = start("REQ00031", null)
                .withChannel("ORDERS", java.util.Map.of("ITEM", new byte[] {7, 8}));
        assertEquals(CicsResponseCode.NORMAL, first.start(NOW, channel).response());
        assertEquals(1, second.dispatchDue());
        CicsStartData data = launched.peek();
        assertEquals(Optional.of("ORDERS"), data.channelName());
        assertEquals(Optional.of("ORDERS"), data.payload().channelName());
        assertArrayEquals(new byte[] {7, 8}, data.payload().containers().get("ITEM"));
    }

    private int pending() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM COBOL_START", Integer.class);
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
        assertEquals(Optional.empty(), data.terminalId());
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

    @Test
    @DisplayName("TERMIDは無い端末と他の利用者の端末をTERMIDERRにし、task中と疑似会話の途中は待ち、同じ端末とTRANSIDの満了したSTARTを1つのtaskにまとめる")
    void startsTasksOnTerminals() {
        String alice = terminals.register("alice", NOW.plusSeconds(3600), NOW);
        String bob = terminals.register("bob", NOW.plusSeconds(3600), NOW);

        assertEquals(CicsResponseCode.TERMIDERR, first.start(NOW, toTerminal("T0", "TX01", "ZZZZ", "alice", null))
                .response());
        assertEquals(CicsResponseCode.TERMIDERR, first.start(NOW, toTerminal("T0", "TX01", bob, "alice", null))
                .response());
        assertEquals(CicsResponseCode.NORMAL, first.start(NOW.plusSeconds(2),
                toTerminal("TB", "TX01", alice, "alice", new byte[] {2})).response());
        assertEquals(CicsResponseCode.NORMAL, second.start(NOW.plusSeconds(1),
                toTerminal("TA", "TX01", alice, "alice", new byte[] {1})).response());
        assertEquals(CicsResponseCode.NORMAL, second.start(NOW.plusSeconds(3),
                toTerminal("TC", "TX02", alice, "alice", null)).response());
        time.set(NOW.plusSeconds(5));

        // 端末で task が動いている間は待つ
        CicsTerminalRegistryPort.TerminalLease busy = terminals.lease(alice, "alice", Duration.ofSeconds(30),
                time.get()).orElseThrow();
        assertEquals(0, first.dispatchDue());
        // 疑似会話の途中も待つ
        CicsTerminalRegistryPort.TerminalConversation conversation = new CicsTerminalRegistryPort.TerminalConversation(
                new ConversationId("conversation_start01"), 0, TransId.of("TX09"));
        terminals.setConversation(busy, Optional.of(conversation), time.get());
        terminals.release(busy, time.get());
        assertEquals(0, second.dispatchDue());
        CicsTerminalRegistryPort.TerminalLease clear = terminals.lease(alice, "alice", Duration.ofSeconds(30),
                time.get()).orElseThrow();
        terminals.setConversation(clear, Optional.empty(), time.get());
        terminals.release(clear, time.get());
        assertTrue(launched.isEmpty());
        assertEquals(3, pending());

        // TX02 の task は RETURN TRANSID で端末を疑似会話に入れる
        replies = data -> data.transaction().value().equals("TX02")
                ? new CicsTerminalTasks.Outcome(Optional.of(new ConversationEnvelope(
                        new ConversationId("conversation_start02"), 0, "alice", TransId.of("TX03"), CicsPayload.empty(),
                        NOW.plusSeconds(600), new IdempotencyKey("start-conversation-1"), Optional.empty())),
                        Optional.of(new CicsTerminalScreen.TextScreen("STARTED", true, false)))
                : CicsTerminalTasks.Outcome.none();
        assertEquals(2, first.dispatchDue());
        assertEquals(0, pending());

        CicsStartData batched = launched.poll();
        assertEquals("TA", batched.requestId());
        assertEquals(Optional.of(alice), batched.terminalId());
        assertEquals(List.of("TB"), batched.following().stream().map(CicsStartData::requestId).toList());
        assertEquals("TC", launched.poll().requestId());
        CicsTerminalRegistryPort.Terminal after = terminals.find(alice, time.get()).orElseThrow();
        assertFalse(after.leased());
        assertEquals("TX03", after.conversation().orElseThrow().nextTransaction().value());
        // 端末へ出す task の画面は端末の現在の画面になり、版が進む。画面を送らなかった TX01 は版を進めない
        assertEquals(1, after.screenVersion());
        assertEquals("STARTED", ((CicsTerminalScreen.TextScreen) terminals.screen(alice, time.get()).orElseThrow()
                .screen()).text());
    }

    @Test
    @DisplayName("RETRIEVE WAITは同じ端末とTRANSIDの満了したSTARTだけを取り出し、未満了と別のTRANSIDは残す")
    void retrievesLaterStartsForWaitingTasks() {
        String alice = terminals.register("alice", NOW.plusSeconds(3600), NOW);
        CicsStartData started = toTerminal("W0", "TX01", alice, "alice", null);
        first.start(NOW.plusSeconds(1), toTerminal("W1", "TX01", alice, "alice", new byte[] {1}));
        first.start(NOW.plusSeconds(60), toTerminal("W2", "TX01", alice, "alice", null));
        first.start(NOW.plusSeconds(1), toTerminal("W3", "TX02", alice, "alice", null));
        time.set(NOW.plusSeconds(5));

        assertEquals(List.of("W1"), second.retrieveMore(started).stream().map(CicsStartData::requestId).toList());
        assertTrue(first.retrieveMore(started).isEmpty());
        assertEquals(2, pending());
    }

    @Test
    @DisplayName("満了したときに端末が無いか別の利用者に振り直されていれば、TERMIDのSTARTは起こさずに捨てる")
    void discardsStartsForMissingTerminals() {
        String alice = terminals.register("alice", NOW.plusSeconds(3600), NOW);
        first.start(NOW.plusSeconds(1), toTerminal("GONE", "TX01", alice, "alice", null));
        assertTrue(terminals.remove(alice, NOW));
        time.set(NOW.plusSeconds(5));

        assertEquals(0, second.dispatchDue());
        assertEquals(0, pending());
        assertTrue(launched.isEmpty());
    }
}
