package dev.cobolonjava.mq;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 翻訳した COBOL から MQI を呼ぶ (設計 88、暫定判断 P-251〜P-253)。 */
@Tag("V1")
class MqiTest {

    private static final CodePage CP = CodePages.DEFAULT;
    private static final Instant NOW = Instant.parse("2026-10-20T01:02:03.45Z");

    private static MqQueueManager manager() {
        return new MqQueueManager("QM01", CP, 1047, Clock.fixed(NOW, ZoneOffset.UTC), MqConstants.MQAT_ZOS)
                .defineLocalQueue("APP.IN")
                .defineLocalQueue("APP.OUT");
    }

    /** 名前の待ち行列を開く文。 */
    private static List<String> open(String queue, String options) {
        return List.of(
                "    MOVE '" + queue + "' TO MQOD-OBJECTNAME",
                "    COMPUTE OPTS = " + options,
                "    PERFORM OPEN-Q",
                "    PERFORM SHOW");
    }

    private static List<String> concat(List<List<String>> parts) {
        return parts.stream().flatMap(List::stream).toList();
    }

    @Test
    @DisplayName("PUT した 3 通が GET で同じ順に返り、空になれば MQCC_FAILED / MQRC_NO_MSG_AVAILABLE")
    void putThenGetInOrder() {
        MqQueueManager qm = manager();
        List<String> lines = MqProgram.compile("FIFO", concat(List.of(
                open("APP.IN", "MQOO-OUTPUT + MQOO-INPUT-SHARED"),
                List.of(
                        "    COMPUTE MQPMO-OPTIONS = MQPMO-NO-SYNCPOINT",
                        "    MOVE 5 TO BUFLEN",
                        "    MOVE 'ONE' TO BUF PERFORM PUT-MSG",
                        "    MOVE 'TWO' TO BUF PERFORM PUT-MSG",
                        "    MOVE 'THREE' TO BUF PERFORM PUT-MSG",
                        "    MOVE 20 TO BUFLEN",
                        "    COMPUTE MQGMO-OPTIONS = MQGMO-NO-SYNCPOINT",
                        "    PERFORM 4 TIMES",
                        "        PERFORM GET-MSG",
                        "        PERFORM SHOW",
                        "    END-PERFORM")))).run(qm);
        assertEquals(List.of("0000 0000", "0000 0000 ONE", "0000 0000 TWO", "0000 0000 THREE", "0002 2033"),
                lines);
    }

    @Test
    @DisplayName("優先度の高い電文が先に渡り、同じ優先度なら置いた順")
    void higherPriorityFirst() {
        MqQueueManager qm = manager();
        qm.put("APP.IN", MqMessage.builder(CP).text("LOW-A").priority(1).build());
        qm.put("APP.IN", MqMessage.builder(CP).text("HIGH").priority(7).build());
        qm.put("APP.IN", MqMessage.builder(CP).text("LOW-B").priority(1).build());
        List<String> lines = MqProgram.compile("PRIO", concat(List.of(
                open("APP.IN", "MQOO-INPUT-AS-Q-DEF"),
                List.of(
                        "    PERFORM 3 TIMES",
                        "        PERFORM GET-MSG",
                        "        PERFORM SHOW",
                        "    END-PERFORM")))).run(qm);
        assertEquals(List.of("0000 0000", "0000 0000 HIGH", "0000 0000 LOW-A", "0000 0000 LOW-B"), lines);
    }

    @Test
    @DisplayName("同期点の下の PUT は MQCMIT まで見えず、MQBACK で消える (z/OS は指定が無ければ同期点の下)")
    void syncpointPutIsInvisibleUntilCommit() {
        MqQueueManager qm = manager();
        List<String> lines = MqProgram.compile("UOWPUT", concat(List.of(
                open("APP.OUT", "MQOO-OUTPUT"),
                List.of(
                        "    MOVE 4 TO BUFLEN",
                        "    MOVE 'KEEP' TO BUF PERFORM PUT-MSG",
                        "    CALL 'MQCMIT' USING HCONN CC RC",
                        "    MOVE 'DROP' TO BUF PERFORM PUT-MSG",
                        "    CALL 'MQBACK' USING HCONN CC RC",
                        "    COMPUTE MQPMO-OPTIONS = MQPMO-SYNCPOINT",
                        "    MOVE 'LATE' TO BUF PERFORM PUT-MSG",
                        "    PERFORM SHOW")))).run(qm);
        assertEquals(List.of("0000 0000", "0000 0000 LATE"), lines);
        // LATE は確約されないまま実行単位が正常に終わった。終わりの暗黙の同期点で確約される
        assertEquals(List.of("KEEP", "LATE"), qm.messages("APP.OUT").stream().map(m -> m.text(CP)).toList());
    }

    @Test
    @DisplayName("実行単位が異常に終われば、同期点の下の PUT と GET は取り消される")
    void abnormalEndBacksOut() {
        MqQueueManager qm = manager();
        qm.put("APP.IN", MqMessage.builder(CP).text("REQUEST").build());
        MqProgram program = MqProgram.compile("ABEND", concat(List.of(
                open("APP.IN", "MQOO-INPUT-SHARED + MQOO-OUTPUT"),
                List.of(
                        "    PERFORM GET-MSG",
                        "    MOVE 5 TO BUFLEN",
                        "    MOVE 'REPLY' TO BUF PERFORM PUT-MSG",
                        "    CALL 'NOSUCHPG'"))));
        assertThrows(RuntimeException.class, () -> program.run(qm));
        List<MqMessage> left = qm.messages("APP.IN");
        assertEquals(List.of("REQUEST"), left.stream().map(m -> m.text(CP)).toList());
        assertEquals(1, left.get(0).backoutCount());
        assertEquals(1, qm.depth("APP.IN"), "取り消した PUT は深さにも残らない");
    }

    @Test
    @DisplayName("同期点の下で取って MQBACK すると、電文は元の位置へ戻り BackoutCount が 1 増える")
    void backedOutGetReturnsTheMessage() {
        MqQueueManager qm = manager();
        qm.put("APP.IN", MqMessage.builder(CP).text("FIRST").build());
        qm.put("APP.IN", MqMessage.builder(CP).text("SECOND").build());
        List<String> lines = MqProgram.compile("UOWGET", concat(List.of(
                open("APP.IN", "MQOO-INPUT-SHARED"),
                List.of(
                        "    COMPUTE MQGMO-OPTIONS = MQGMO-SYNCPOINT",
                        "    PERFORM GET-MSG PERFORM SHOW",
                        "    CALL 'MQBACK' USING HCONN CC RC",
                        "    PERFORM GET-MSG PERFORM SHOW",
                        "    MOVE MQMD-BACKOUTCOUNT TO N-D",
                        "    DISPLAY 'BACKOUT' N-D",
                        "    CALL 'MQCMIT' USING HCONN CC RC")))).run(qm);
        assertEquals(List.of("0000 0000", "0000 0000 FIRST", "0000 0000 FIRST", "BACKOUT 000000001"), lines);
        assertEquals(List.of("SECOND"), qm.messages("APP.IN").stream().map(m -> m.text(CP)).toList());
    }

    @Test
    @DisplayName("中身が BufferLength より長ければ、既定は電文を残して 2080、ACCEPT-TRUNCATED なら取って 2079")
    void truncation() {
        MqQueueManager qm = manager();
        qm.put("APP.IN", MqMessage.builder(CP).text("ABCDEFGHIJ").build());
        List<String> lines = MqProgram.compile("TRUNC", concat(List.of(
                open("APP.IN", "MQOO-INPUT-SHARED"),
                List.of(
                        "    MOVE 4 TO BUFLEN",
                        "    COMPUTE MQGMO-OPTIONS = MQGMO-NO-SYNCPOINT",
                        "    PERFORM GET-MSG PERFORM SHOW",
                        "    MOVE DATALEN TO N-D DISPLAY 'LEN' N-D",
                        "    COMPUTE MQGMO-OPTIONS = MQGMO-NO-SYNCPOINT",
                        "                          + MQGMO-ACCEPT-TRUNCATED-MSG",
                        "    PERFORM GET-MSG PERFORM SHOW",
                        "    PERFORM GET-MSG PERFORM SHOW")))).run(qm);
        assertEquals(List.of("0000 0000", "0001 2080 ABCD", "LEN 000000010", "0001 2079 ABCD", "0002 2033"),
                lines);
    }

    @Test
    @DisplayName("CorrelId を置いて GET すると、それに合う電文だけが渡る")
    void matchesCorrelationId() {
        MqQueueManager qm = manager();
        byte[] a = new byte[24];
        Arrays.fill(a, CP.ch('A'));
        byte[] b = new byte[24];
        Arrays.fill(b, CP.ch('B'));
        qm.put("APP.IN", MqMessage.builder(CP).text("FOR-A").correlationId(a).build());
        qm.put("APP.IN", MqMessage.builder(CP).text("FOR-B").correlationId(b).build());
        List<String> lines = MqProgram.compile("CORREL", concat(List.of(
                open("APP.IN", "MQOO-INPUT-SHARED"),
                List.of(
                        "    MOVE ALL 'B' TO MQMD-CORRELID",
                        "    MOVE MQMI-NONE TO MQMD-MSGID",
                        "    CALL 'MQGET' USING HCONN HOBJ MD GMO BUFLEN BUF DATALEN",
                        "                       CC RC",
                        "    PERFORM SHOW",
                        "    CALL 'MQCMIT' USING HCONN CC RC")))).run(qm);
        assertEquals(List.of("0000 0000", "0000 0000 FOR-B"), lines);
        assertEquals(List.of("FOR-A"), qm.messages("APP.IN").stream().map(m -> m.text(CP)).toList());
    }

    @Test
    @DisplayName("MQPUT1 は MsgId を振り、文脈 (PutApplType、UTC の PutDate / PutTime) と Expiry を記述子に残す")
    void put1FillsDescriptor() {
        MqQueueManager qm = manager();
        List<String> lines = MqProgram.compile("PUTONE", List.of(
                "    MOVE MQOT-Q TO MQOD-OBJECTTYPE",
                "    MOVE 'APP.OUT' TO MQOD-OBJECTNAME",
                "    MOVE MQMT-REPLY TO MQMD-MSGTYPE",
                "    MOVE MQFMT-STRING TO MQMD-FORMAT",
                "    MOVE 50 TO MQMD-EXPIRY",
                "    COMPUTE MQPMO-OPTIONS = MQPMO-NO-SYNCPOINT",
                "                          + MQPMO-DEFAULT-CONTEXT",
                "    MOVE 'REPLY' TO BUF",
                "    MOVE 5 TO BUFLEN",
                "    CALL 'MQPUT1' USING HCONN OD MD PMO BUFLEN BUF CC RC",
                "    PERFORM SHOW",
                "    IF MQMD-MSGID NOT = MQMI-NONE DISPLAY 'MSGID SET' END-IF",
                "    DISPLAY MQMD-PUTDATE ' ' MQMD-PUTTIME",
                "    DISPLAY MQPMO-RESOLVEDQNAME(1:8)")).run(qm);
        assertEquals(List.of("0000 0000 REPLY", "MSGID SET", "20261020 01020345", "APP.OUT"), lines);
        MqMessage put = qm.messages("APP.OUT").get(0);
        assertEquals("REPLY", put.text(CP));
        assertEquals("MQSTR", put.format(CP));
        assertEquals(MqConstants.MQMT_REPLY, put.messageType());
        assertEquals(50, put.expiry());
        assertEquals(1047, put.codedCharSetId(), "MQCCSI_Q_MGR は待ち行列管理者の CCSID に置き換わる");
        assertEquals(0, put.priority(), "MQPRI_PRIORITY_AS_Q_DEF は定義の DEFPRTY に置き換わる");
        assertEquals("CSQ QM01", CP.decode(Arrays.copyOf(put.messageId(), 8)));
    }

    @Test
    @DisplayName("知らない待ち行列は 2085、出力だけで開いた handle の GET は 2037、閉じた handle は 2019")
    void reasonCodes() {
        MqQueueManager qm = manager();
        List<String> lines = MqProgram.compile("ERRORS", concat(List.of(
                open("NO.SUCH.Q", "MQOO-OUTPUT"),
                open("APP.IN", "MQOO-OUTPUT"),
                List.of(
                        "    PERFORM GET-MSG PERFORM SHOW",
                        "    CALL 'MQCLOSE' USING HCONN HOBJ MQCO-NONE CC RC",
                        "    PERFORM SHOW",
                        "    MOVE HOBJ TO N-D DISPLAY N-D",
                        "    MOVE 1 TO HOBJ",
                        "    PERFORM PUT-MSG PERFORM SHOW",
                        "    MOVE 'XX' TO MQOD-STRUCID",
                        "    PERFORM OPEN-Q PERFORM SHOW",
                        "    MOVE 7 TO HCONN",
                        "    PERFORM PUT-MSG PERFORM SHOW")))).run(qm);
        assertEquals(List.of("0002 2085", "0000 0000", "0002 2037", "0000 0000", "-000000001",
                "0002 2019", "0002 2044", "0002 2018"), lines);
    }

    @Test
    @DisplayName("MQCONN は 2 度目に 2002 を返し、ほかの名前は 2058。MQDISC は確約し、その handle は使えなくなる")
    void connectAndDisconnect() {
        MqQueueManager qm = manager();
        List<String> lines = MqProgram.compile("CONN", List.of("01 QMNAME PIC X(48) VALUE SPACES."), concat(List.of(
                List.of(
                        "    CALL 'MQCONN' USING QMNAME HCONN CC RC PERFORM SHOW",
                        "    MOVE 'QM01' TO QMNAME",
                        "    CALL 'MQCONN' USING QMNAME HCONN CC RC PERFORM SHOW",
                        "    MOVE 'QM99' TO QMNAME",
                        "    CALL 'MQCONN' USING QMNAME OPTS CC RC PERFORM SHOW"),
                open("APP.OUT", "MQOO-OUTPUT"),
                List.of(
                        "    MOVE 4 TO BUFLEN",
                        "    MOVE 'DISC' TO BUF PERFORM PUT-MSG",
                        "    CALL 'MQDISC' USING HCONN CC RC PERFORM SHOW",
                        "    MOVE HCONN TO N-D DISPLAY N-D",
                        "    PERFORM PUT-MSG PERFORM SHOW")))).run(qm);
        assertEquals(List.of("0000 0000", "0001 2002", "0002 2058", "0000 0000", "0000 0000 DISC",
                "-000000001", "0002 2018 DISC"), lines);
        assertEquals(List.of("DISC"), qm.messages("APP.OUT").stream().map(m -> m.text(CP)).toList());
    }

    @Test
    @DisplayName("MQINQ は確約されていない電文も深さに数え、名前は 48 文字で返す")
    void inquire() {
        MqQueueManager qm = manager();
        List<String> lines = MqProgram.compile("INQ", List.of(
                "01 SELS.",
                "   05 SEL PIC S9(9) BINARY OCCURS 3.",
                "01 INTS.",
                "   05 INTV PIC S9(9) BINARY OCCURS 2.",
                "01 CHARS PIC X(48).",
                "01 NSEL PIC S9(9) BINARY VALUE 3.",
                "01 NINT PIC S9(9) BINARY VALUE 2.",
                "01 NCHR PIC S9(9) BINARY VALUE 48."), concat(List.of(
                open("APP.OUT", "MQOO-OUTPUT + MQOO-INQUIRE"),
                List.of(
                        "    MOVE 3 TO BUFLEN",
                        "    MOVE 'ABC' TO BUF PERFORM PUT-MSG",
                        "    MOVE MQIA-CURRENT-Q-DEPTH TO SEL(1)",
                        "    MOVE MQCA-Q-NAME TO SEL(2)",
                        "    MOVE MQIA-OPEN-OUTPUT-COUNT TO SEL(3)",
                        "    CALL 'MQINQ' USING HCONN HOBJ NSEL SELS NINT INTS",
                        "                       NCHR CHARS CC RC",
                        "    PERFORM SHOW",
                        "    MOVE INTV(1) TO N-D DISPLAY 'DEPTH' N-D",
                        "    MOVE INTV(2) TO N-D DISPLAY 'OPENOUT' N-D",
                        "    DISPLAY CHARS(1:8) '/'")))).run(qm);
        assertEquals(List.of("0000 0000", "0000 0000 ABC", "DEPTH 000000001", "OPENOUT 000000001",
                "APP.OUT /"), lines);
    }

    @Test
    @DisplayName("MQGMO-WAIT は待ち時間のあいだに置かれた電文を受け取り、置かれなければ時間切れで 2033")
    void waitsForAMessage() throws Exception {
        MqQueueManager qm = manager();
        MqProgram program = MqProgram.compile("WAITER", concat(List.of(
                open("APP.IN", "MQOO-INPUT-SHARED"),
                List.of(
                        "    COMPUTE MQGMO-OPTIONS = MQGMO-WAIT + MQGMO-NO-SYNCPOINT",
                        "    MOVE 300 TO MQGMO-WAITINTERVAL",
                        "    PERFORM GET-MSG PERFORM SHOW",
                        "    MOVE 5000 TO MQGMO-WAITINTERVAL",
                        "    PERFORM GET-MSG PERFORM SHOW"))));
        Thread producer = new Thread(() -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            qm.put("APP.IN", MqMessage.builder(CP).text("LATE").build());
        });
        long start = System.nanoTime();
        producer.start();
        List<String> lines = program.run(qm);
        long millis = (System.nanoTime() - start) / 1_000_000;
        producer.join();
        assertEquals(List.of("0000 0000", "0002 2033", "0000 0000 LATE"), lines);
        assertTrue(millis >= 700 && millis < 5000, "waited " + millis + " ms");
    }

    @Test
    @DisplayName("持たない選択 (browse) は理由コードを作らずに断る")
    void refusesUnsupportedOptions() {
        MqQueueManager qm = manager();
        MqProgram program = MqProgram.compile("BROWSE", open("APP.IN", "MQOO-BROWSE"));
        RuntimeException failure = assertThrows(RuntimeException.class, () -> program.run(qm));
        Throwable cause = failure;
        while (cause != null && !(cause instanceof UnsupportedOperationException)) {
            cause = cause.getCause();
        }
        assertTrue(cause != null, () -> "expected an UnsupportedOperationException, got " + failure);
        assertFalse(qm.depth("APP.IN") != 0);
    }
}
