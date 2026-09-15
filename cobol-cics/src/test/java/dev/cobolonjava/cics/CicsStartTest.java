package dev.cobolonjava.cics;

import static dev.cobolonjava.cics.CicsRuntimeOps.START_AFTER;
import static dev.cobolonjava.cics.CicsRuntimeOps.START_AT;
import static dev.cobolonjava.cics.CicsRuntimeOps.START_INTERVAL;
import static dev.cobolonjava.cics.CicsRuntimeOps.START_TIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** START / RETRIEVE / CANCEL (暫定判断 P-138)。 */
@Tag("V1")
class CicsStartTest {

    private static final CodePage CP = CodePages.DEFAULT;
    private static final Instant NOON = Instant.parse("2026-09-15T12:00:00Z");

    private CicsExecution execution;

    private ProgramContext context(CicsTaskContext task, CicsEnvironment environment) {
        execution = new CicsExecution(task, 0, environment);
        return ProgramContext.standard().withCodePage(CP).withServices(
                RuntimeServices.builder().service(CicsExecution.class, execution).build());
    }

    private static CicsTaskContext task(String id) {
        return new CicsTaskContext(new CicsTaskId(id), TransId.of("TX01"), "start-test", NOON,
                OptionalInt.empty(), Optional.of(ZoneId.of("Asia/Tokyo")))
                .withUserId(Optional.of("CICSUSER"));
    }

    private int resp() {
        return ByteBuffer.wrap(execution.eib(CP).storage().array(), CicsEib.EIBRESP_OFFSET, 4).getInt();
    }

    private int resp2() {
        return ByteBuffer.wrap(execution.eib(CP).storage().array(), CicsEib.EIBRESP2_OFFSET, 4).getInt();
    }

    private static DataView text(String value) {
        return Storage.copyOf(CP.encode(value)).whole();
    }

    private static DataView halfword(int value) {
        return Storage.copyOf(new byte[] {(byte) (value >>> 8), (byte) value}).whole();
    }

    private static Decimal number(long value) {
        return Decimal.parse(Long.toString(value));
    }

    private int start(ProgramContext context, String transaction, int timing, Decimal hhmmss, Decimal hours,
                      Decimal minutes, Decimal seconds, DataView from, int length, String request,
                      String returnTransaction) {
        CicsRuntimeOps.startCondition(context, transaction, null, timing, hhmmss, hours, minutes, seconds, from, null,
                length, request, null, returnTransaction, null, null, null, null, null, true);
        return resp();
    }

    @Test
    @DisplayName("満了したSTARTはFROMとRTRANSIDを持つtaskを起こし、RETRIEVEは1度だけ読む。STARTが書かないoptionはENVDEFERR")
    void startsTaskAndRetrievesData() throws InterruptedException {
        BlockingQueue<CicsStartData> launched = new LinkedBlockingQueue<>();
        CicsStartPort port = CicsStartPort.inMemory(Clock.systemUTC(), id -> id.value().equals("TX02"), launched::add);
        ProgramContext issuer = context(task("task_issuer"), CicsEnvironment.unconfigured().withStarts(port));

        assertEquals(CicsResponseCode.NORMAL, start(issuer, "TX02", START_INTERVAL, number(0), null, null, null,
                text("hello"), -1, "REQ1", "TX01"));
        assertEquals(0x1008, ByteBuffer.wrap(execution.eib(CP).storage().array(), CicsEib.EIBFN_OFFSET, 2).getShort());
        CicsStartData data = launched.poll(5, TimeUnit.SECONDS);
        assertNotNull(data);
        assertEquals("REQ1", data.requestId());
        assertEquals(Optional.of("CICSUSER"), data.userId());

        ProgramContext started = context(new CicsTaskContext(new CicsTaskId("task_started"), TransId.of("TX02"),
                "start-test", NOON).withStart(Optional.of(data)), CicsEnvironment.unconfigured());
        CicsRuntimeOps.retrieveCondition(started, null, null, null, null, text("        "), true);
        assertEquals(CicsResponseCode.ENVDEFERR, resp());

        DataView into = text("........");
        DataView length = halfword(8);
        DataView returnTransaction = text("    ");
        CicsRuntimeOps.retrieveCondition(started, into, length, returnTransaction, null, null, true);
        assertEquals(CicsResponseCode.NORMAL, resp());
        assertEquals("hello...", CP.decode(into.toByteArray()));
        assertEquals(5, ByteBuffer.wrap(length.toByteArray()).getShort());
        assertEquals("TX01", CP.decode(returnTransaction.toByteArray()));

        CicsRuntimeOps.retrieveCondition(started, into, null, null, null, null, true);
        assertEquals(CicsResponseCode.ENDDATA, resp());

        // 受取域より長いデータは切り詰めて LENGERR
        CicsStartData longer = new CicsStartData("REQ2", TransId.of("TX02"), CP.encode("longer data"),
                Optional.empty(), Optional.empty(), Optional.empty(), "start-test", Optional.empty());
        ProgramContext truncated = context(new CicsTaskContext(new CicsTaskId("task_long"), TransId.of("TX02"),
                "start-test", NOON).withStart(Optional.of(longer)), CicsEnvironment.unconfigured());
        DataView shortLength = halfword(4);
        CicsRuntimeOps.retrieveCondition(truncated, text("........"), shortLength, null, null, null, true);
        assertEquals(CicsResponseCode.LENGERR, resp());
        assertEquals(11, ByteBuffer.wrap(shortLength.toByteArray()).getShort());

        ProgramContext notStarted = context(task("task_plain"), CicsEnvironment.unconfigured());
        assertThrows(CicsTaskStateException.class,
                () -> CicsRuntimeOps.retrieveCondition(notStarted, into, null, null, null, null, true));
    }

    @Test
    @DisplayName("TERMIDはSTARTのデータに端末を置き、portのTERMIDERRを返す。端末を知らないportは断り、RETRIEVEはまとめたSTARTを満了の順に読む")
    void startsTasksOnTerminalsAndRetrievesBatches() {
        List<CicsStartData> captured = new ArrayList<>();
        CicsStartPort terminalAware = new CicsStartPort() {
            @Override
            public Result start(Instant expiration, CicsStartData data) {
                if (data.terminalId().orElseThrow().equals("NOPE")) {
                    return new Result(CicsResponseCode.TERMIDERR, 0);
                }
                captured.add(data);
                return new Result(CicsResponseCode.NORMAL, 0);
            }

            @Override
            public Result cancel(String requestId) {
                return new Result(CicsResponseCode.NOTFND, 0);
            }

            @Override
            public String newRequestId() {
                return "GEN00001";
            }
        };
        ProgramContext context = context(task("task_termid"), CicsEnvironment.unconfigured().withStarts(terminalAware));
        CicsRuntimeOps.startCondition(context, "TX02", null, START_INTERVAL, number(0), null, null, null, null, null,
                -1, "R1", null, null, null, null, null, null, null, "W001", null, false, true);
        assertEquals(CicsResponseCode.NORMAL, resp());
        assertEquals(Optional.of("W001"), captured.get(0).terminalId());
        CicsRuntimeOps.startCondition(context, "TX02", null, START_INTERVAL, number(0), null, null, null, null, null,
                -1, "R2", null, null, null, null, null, null, null, "NOPE", null, false, true);
        assertEquals(CicsResponseCode.TERMIDERR, resp());
        assertEquals(0x1008, ByteBuffer.wrap(execution.eib(CP).storage().array(), CicsEib.EIBFN_OFFSET, 2).getShort());

        CicsStartPort inMemory = CicsStartPort.inMemory(Clock.systemUTC(), id -> true, data -> { });
        ProgramContext unaware = context(task("task_unaware"), CicsEnvironment.unconfigured().withStarts(inMemory));
        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.startCondition(unaware, "TX02", null,
                START_INTERVAL, number(0), null, null, null, null, null, -1, "R3", null, null, null, null, null, null,
                null, "W001", null, false, true));

        CicsStartData one = new CicsStartData("B1", TransId.of("TX02"), CP.encode("one"), Optional.empty(),
                Optional.empty(), Optional.empty(), "start-test", Optional.empty(), Optional.of("W001"));
        CicsStartData two = new CicsStartData("B2", TransId.of("TX02"), CP.encode("two"), Optional.empty(),
                Optional.empty(), Optional.empty(), "start-test", Optional.empty(), Optional.of("W001"));
        ProgramContext started = context(new CicsTaskContext(new CicsTaskId("task_batch"), TransId.of("TX02"),
                "start-test", NOON).withStart(Optional.of(one.withFollowing(List.of(two)))),
                CicsEnvironment.unconfigured());
        DataView into = text("...");
        CicsRuntimeOps.retrieveCondition(started, into, null, null, null, null, true);
        assertEquals("one", CP.decode(into.toByteArray()));
        CicsRuntimeOps.retrieveCondition(started, into, null, null, null, null, true);
        assertEquals(CicsResponseCode.NORMAL, resp());
        assertEquals("two", CP.decode(into.toByteArray()));
        CicsRuntimeOps.retrieveCondition(started, into, null, null, null, null, true);
        assertEquals(CicsResponseCode.ENDDATA, resp());
        assertThrows(IllegalArgumentException.class, () -> one.withFollowing(List.of(new CicsStartData("B3",
                TransId.of("TX03"), null, Optional.empty(), Optional.empty(), Optional.empty(), "start-test",
                Optional.empty(), Optional.of("W001")))));
    }

    @Test
    @DisplayName("START USERIDは代理の権限が無ければNOTAUTH 9、起こすtaskのuser IDがTRANSIDを起こせなければNOTAUTH 7、通ればそのuser IDで起こす")
    void checksSurrogateAndTransactionSecurity() {
        List<CicsStartData> captured = new ArrayList<>();
        CicsStartPort port = new CicsStartPort() {
            @Override
            public Result start(Instant expiration, CicsStartData data) {
                captured.add(data);
                return new Result(CicsResponseCode.NORMAL, 0);
            }

            @Override
            public Result cancel(String requestId) {
                return new Result(CicsResponseCode.NOTFND, 0);
            }

            @Override
            public String newRequestId() {
                return "GEN00001";
            }
        };
        // CICSUSER は BATCH01 を代理でき、BATCH01 は TX02 だけを起こせる
        CicsSecurityPort security = new CicsSecurityPort() {
            @Override
            public Optional<String> userIdOf(String principal) {
                return Optional.empty();
            }

            @Override
            public boolean mayAttach(Optional<String> userId, TransId transaction) {
                return !userId.equals(Optional.of("BATCH01")) || transaction.value().equals("TX02");
            }

            @Override
            public boolean maySurrogate(Optional<String> userId, String surrogateUserId) {
                return userId.equals(Optional.of("CICSUSER")) && surrogateUserId.equals("BATCH01");
            }
        };
        ProgramContext context = context(task("task_userid"),
                CicsEnvironment.unconfigured().withStarts(port).withSecurity(security));

        CicsRuntimeOps.startCondition(context, "TX02", null, START_INTERVAL, number(0), null, null, null, null, null,
                -1, "U1", null, null, null, null, null, null, null, null, null, "BATCH01", null, false, true);
        assertEquals(CicsResponseCode.NORMAL, resp());
        assertEquals(Optional.of("BATCH01"), captured.get(0).userId());

        CicsRuntimeOps.startCondition(context, "TX02", null, START_INTERVAL, number(0), null, null, null, null, null,
                -1, "U2", null, null, null, null, null, null, null, null, null, "OTHER1", null, false, true);
        assertEquals(CicsResponseCode.NOTAUTH, resp());
        assertEquals(9, resp2());

        CicsRuntimeOps.startCondition(context, "TX03", null, START_INTERVAL, number(0), null, null, null, null, null,
                -1, "U3", null, null, null, null, null, null, null, null, null, "BATCH01", null, false, true);
        assertEquals(CicsResponseCode.NOTAUTH, resp());
        assertEquals(7, resp2());
        assertEquals(1, captured.size());

        // USERID を書かなければ START を出した task の user ID で起こす
        start(context, "TX03", START_INTERVAL, number(0), null, null, null, null, -1, "U4", null);
        assertEquals(CicsResponseCode.NORMAL, resp());
        assertEquals(Optional.of("CICSUSER"), captured.get(1).userId());

        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.startCondition(context, "TX02", null,
                START_INTERVAL, number(0), null, null, null, null, null, -1, "U5", null, null, null, null, null, null,
                null, "W001", null, "BATCH01", null, false, true));
    }

    @Test
    @DisplayName("RETRIEVE WAITは読み尽くしていればportから次に満了したSTARTを受けて読み、端末の無いSTARTのtaskでは断る")
    void retrieveWaitReceivesLaterStarts() {
        CicsStartData first = new CicsStartData("W1", TransId.of("TX02"), CP.encode("one"), Optional.empty(),
                Optional.empty(), Optional.empty(), "start-test", Optional.empty(), Optional.of("W001"));
        CicsStartData later = new CicsStartData("W2", TransId.of("TX02"), CP.encode("two"), Optional.empty(),
                Optional.empty(), Optional.empty(), "start-test", Optional.empty(), Optional.of("W001"));
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        CicsStartPort waiting = new CicsStartPort() {
            @Override
            public Result start(Instant expiration, CicsStartData data) {
                return new Result(CicsResponseCode.NORMAL, 0);
            }

            @Override
            public Result cancel(String requestId) {
                return new Result(CicsResponseCode.NOTFND, 0);
            }

            @Override
            public String newRequestId() {
                return "GEN00001";
            }

            @Override
            public List<CicsStartData> retrieveMore(CicsStartData started) {
                // 3 度目に探したときに次の START が満了している
                return asked.incrementAndGet() < 3 ? List.of() : List.of(later);
            }
        };
        ProgramContext context = context(new CicsTaskContext(new CicsTaskId("task_wait"), TransId.of("TX02"),
                "start-test", NOON).withStart(Optional.of(first)), CicsEnvironment.unconfigured().withStarts(waiting));
        DataView into = text("...");
        CicsRuntimeOps.retrieveCondition(context, into, null, null, null, null, true, true);
        assertEquals("one", CP.decode(into.toByteArray()));
        assertEquals(0, asked.get());
        CicsRuntimeOps.retrieveCondition(context, into, null, null, null, null, true, true);
        assertEquals(CicsResponseCode.NORMAL, resp());
        assertEquals("two", CP.decode(into.toByteArray()));
        assertEquals(3, asked.get());
        // WAIT が無ければ読み尽くしで ENDDATA
        CicsRuntimeOps.retrieveCondition(context, into, null, null, null, null, false, true);
        assertEquals(CicsResponseCode.ENDDATA, resp());

        CicsStartData detached = new CicsStartData("D1", TransId.of("TX02"), CP.encode("one"), Optional.empty(),
                Optional.empty(), Optional.empty(), "start-test", Optional.empty());
        ProgramContext plain = context(new CicsTaskContext(new CicsTaskId("task_plain_wait"), TransId.of("TX02"),
                "start-test", NOON).withStart(Optional.of(detached)), CicsEnvironment.unconfigured().withStarts(waiting));
        CicsRuntimeOps.retrieveCondition(plain, into, null, null, null, null, true, true);
        assertThrows(CicsTaskStateException.class,
                () -> CicsRuntimeOps.retrieveCondition(plain, into, null, null, null, null, true, true));
    }

    @Test
    @DisplayName("未満了のSTARTはCANCELで取り消し、2度目はNOTFND。定義の無いTRANSIDはTRANSIDERR、範囲外の時刻はINVREQ 4/5/6")
    void cancelsAndValidatesStarts() {
        List<CicsStartData> launched = new ArrayList<>();
        CicsStartPort port = CicsStartPort.inMemory(Clock.systemUTC(), id -> id.value().equals("TX02"),
                data -> {
                    synchronized (launched) {
                        launched.add(data);
                    }
                });
        ProgramContext context = context(task("task_cancel"), CicsEnvironment.unconfigured().withStarts(port));

        assertEquals(CicsResponseCode.NORMAL, start(context, "TX02", START_INTERVAL, number(10000), null, null, null,
                text("later"), -1, "LATER", null));
        assertEquals(CicsResponseCode.IOERR, start(context, "TX02", START_INTERVAL, number(10000), null, null, null,
                text("again"), -1, "LATER", null));
        CicsRuntimeOps.cancelCondition(context, "LATER", null, true);
        assertEquals(CicsResponseCode.NORMAL, resp());
        CicsRuntimeOps.cancelCondition(context, "LATER", null, true);
        assertEquals(CicsResponseCode.NOTFND, resp());
        assertTrue(launched.isEmpty());

        assertEquals(CicsResponseCode.TRANSIDERR, start(context, "NOPE", START_INTERVAL, number(0), null, null, null,
                null, -1, "R3", null));

        assertEquals(CicsResponseCode.INVREQ, start(context, "TX02", START_INTERVAL, number(6000), null, null, null,
                null, -1, "R4", null));
        assertEquals(5, resp2());
        assertEquals(CicsResponseCode.INVREQ, start(context, "TX02", START_AFTER, null, number(100), null, null,
                null, -1, "R5", null));
        assertEquals(4, resp2());
        assertEquals(CicsResponseCode.INVREQ, start(context, "TX02", START_AFTER, null, number(1), null, number(60),
                null, -1, "R6", null));
        assertEquals(6, resp2());
        assertEquals(CicsResponseCode.LENGERR, start(context, "TX02", START_INTERVAL, number(0), null, null, null,
                text("abc"), 0, "R7", null));

        // REQID を書かなければ CICS が作った名前を EIBREQID に置く
        assertEquals(CicsResponseCode.NORMAL, start(context, "TX02", START_INTERVAL, number(10000), null, null, null,
                null, -1, null, null));
        String generated = CP.decode(java.util.Arrays.copyOfRange(execution.eib(CP).storage().array(),
                CicsEib.EIBREQID_OFFSET, CicsEib.EIBREQID_OFFSET + CicsEib.EIBREQID_LENGTH));
        CicsRuntimeOps.cancelCondition(context, generated, null, true);
        assertEquals(CicsResponseCode.NORMAL, resp());

        ProgramContext unconfigured = context(task("task_none"), CicsEnvironment.unconfigured());
        assertThrows(CicsTaskStateException.class, () -> start(unconfigured, "TX02", START_INTERVAL, number(0),
                null, null, null, null, -1, "R8", null));
    }

    @Test
    @DisplayName("RETRIEVE SETはSTARTのデータをCICSの置き場に写してPOINTERに番地を置き、LENGTHに長さを返す")
    void retrievesThroughSetPointer() {
        CicsStartData data = new CicsStartData("REQ3", TransId.of("TX02"), CP.encode("pointed"), Optional.empty(),
                Optional.empty(), Optional.empty(), "start-test", Optional.empty());
        ProgramContext started = context(new CicsTaskContext(new CicsTaskId("task_set"), TransId.of("TX02"),
                "start-test", NOON).withStart(Optional.of(data)), CicsEnvironment.unconfigured());
        DataView pointer = Storage.copyOf(new byte[4]).whole();
        DataView length = halfword(0);

        CicsRuntimeOps.retrieveCondition(started, pointer, length, null, null, null, false, true, true);
        assertEquals(CicsResponseCode.NORMAL, resp());
        assertEquals(7, ByteBuffer.wrap(length.toByteArray()).getShort());
        DataView addressed = dev.cobolonjava.runtime.program.Ops.addressed(started, pointer.storage(),
                pointer.offset(), 7, "LK-REC");
        assertEquals("pointed", CP.decode(addressed.toByteArray()));

        // 2 度目は ENDDATA で、POINTER は変えない
        byte[] before = pointer.toByteArray();
        CicsRuntimeOps.retrieveCondition(started, pointer, length, null, null, null, false, true, true);
        assertEquals(CicsResponseCode.ENDDATA, resp());
        assertTrue(java.util.Arrays.equals(before, pointer.toByteArray()));
    }

    /** CHANNEL / SYSID / ATTACH を書ける START。 */
    private int startWith(ProgramContext context, DataView from, String channel, String sysid, int flags) {
        CicsRuntimeOps.startCondition(context, "TX02", null, START_INTERVAL, number(0), null, null, null, from, null,
                -1, null, null, null, null, null, null, null, null, null, null, null, null, channel, null, sysid, null,
                flags, false, true);
        return resp();
    }

    @Test
    @DisplayName("ATTACHはEIBREQIDを置かず直ちに起こし、CHANNELはchannelの写しを起こすtaskの入力にし、無いchannelはCHANNELERR 1、自regionでないSYSIDはSYSIDERR、REQIDの無いCANCELはNOTFND")
    void startsWithChannelAttachAndSysid() throws InterruptedException {
        BlockingQueue<CicsStartData> launched = new LinkedBlockingQueue<>();
        CicsStartPort port = CicsStartPort.inMemory(Clock.systemUTC(), id -> id.value().equals("TX02"), launched::add);
        CicsEnvironment environment = CicsEnvironment.unconfigured().withStarts(port)
                .withLocalSystems(java.util.Set.of("HOME"));

        ProgramContext attacher = context(task("task_attach"), environment);
        assertEquals(CicsResponseCode.NORMAL, startWith(attacher, text("attached"), null, null,
                CicsRuntimeOps.START_ATTACH));
        CicsStartData attached = launched.poll(5, TimeUnit.SECONDS);
        assertNotNull(attached);
        assertEquals("attached", CP.decode(attached.data().orElseThrow()));
        byte[] eib = execution.eib(CP).storage().array();
        assertTrue(java.util.Arrays.equals(new byte[CicsEib.EIBREQID_LENGTH], java.util.Arrays.copyOfRange(eib,
                CicsEib.EIBREQID_OFFSET, CicsEib.EIBREQID_OFFSET + CicsEib.EIBREQID_LENGTH)));

        ProgramContext issuer = context(task("task_channel"), environment);
        execution.putChannel("ORDERS", java.util.Map.of("ITEM", CP.encode("apple")));
        assertEquals(CicsResponseCode.NORMAL, startWith(issuer, null, "ORDERS", "HOME", 0));
        CicsStartData started = launched.poll(5, TimeUnit.SECONDS);
        assertNotNull(started);
        assertTrue(started.data().isEmpty());
        CicsPayload payload = started.payload();
        assertEquals(Optional.of("ORDERS"), payload.channelName());
        assertEquals("apple", CP.decode(payload.containers().get("ITEM")));

        assertEquals(CicsResponseCode.CHANNELERR, startWith(issuer, null, "NOCHAN", null, 0));
        assertEquals(1, resp2());
        assertEquals(CicsResponseCode.SYSIDERR, startWith(issuer, text("remote"), null, "AWAY", 0));
        assertEquals(null, launched.poll(200, TimeUnit.MILLISECONDS));

        CicsRuntimeOps.cancelCondition(issuer, null, null, null, null, null, null, true);
        assertEquals(CicsResponseCode.NOTFND, resp());
        CicsRuntimeOps.cancelCondition(issuer, "REQ9", null, "TX02", null, "AWAY", null, true);
        assertEquals(CicsResponseCode.SYSIDERR, resp());
    }

    @Test
    @DisplayName("PROTECTのSTARTは命令の時点で条件を返し、同期点で登録し、ROLLBACKで取り消す")
    void protectedStartsWaitForSyncpoint() throws InterruptedException {
        BlockingQueue<CicsStartData> launched = new LinkedBlockingQueue<>();
        CicsStartPort port = CicsStartPort.inMemory(Clock.systemUTC(), id -> id.value().equals("TX02"), launched::add);
        ProgramContext context = context(task("task_protect"), CicsEnvironment.unconfigured().withStarts(port));

        CicsRuntimeOps.startCondition(context, "NOPE", null, START_INTERVAL, number(0), null, null, null, null, null,
                -1, "P0", null, null, null, null, null, null, null, true, true);
        assertEquals(CicsResponseCode.TRANSIDERR, resp());

        CicsRuntimeOps.startCondition(context, "TX02", null, START_INTERVAL, number(0), null, null, null,
                text("one"), null, -1, "P1", null, null, null, null, null, null, null, true, true);
        assertEquals(CicsResponseCode.NORMAL, resp());
        CicsRuntimeOps.startCondition(context, "TX02", null, START_INTERVAL, number(0), null, null, null,
                text("dup"), null, -1, "P1", null, null, null, null, null, null, null, true, true);
        assertEquals(CicsResponseCode.IOERR, resp());
        assertEquals(null, launched.poll(200, TimeUnit.MILLISECONDS));

        execution.takeProtectedStarts().forEach(Runnable::run);
        assertEquals("P1", launched.poll(5, TimeUnit.SECONDS).requestId());

        CicsRuntimeOps.startCondition(context, "TX02", null, START_INTERVAL, number(0), null, null, null, null, null,
                -1, "P2", null, null, null, null, null, null, null, true, true);
        execution.discardProtectedStarts();
        assertTrue(execution.takeProtectedStarts().isEmpty());
        CicsRuntimeOps.cancelCondition(context, "P2", null, true);
        assertEquals(CicsResponseCode.NOTFND, resp());
    }

    @Test
    @DisplayName("TIMEとATはtaskの地方時の時刻で、6時間前までは直ちに、それより前は翌日、時が23を越えれば翌日以降")
    void computesExpirationFromHostTime() {
        List<Instant> expirations = new ArrayList<>();
        CicsStartPort capturing = new CicsStartPort() {
            @Override
            public Result start(Instant expiration, CicsStartData data) {
                expirations.add(expiration);
                return new Result(CicsResponseCode.NORMAL, 0);
            }

            @Override
            public Result cancel(String requestId) {
                return new Result(CicsResponseCode.NOTFND, 0);
            }

            @Override
            public String newRequestId() {
                return "GEN00001";
            }
        };
        CicsEnvironment environment = CicsEnvironment.unconfigured().withStarts(capturing)
                .withClock(Clock.fixed(NOON, ZoneOffset.UTC));
        // 東京の 21:00
        ProgramContext context = context(task("task_time"), environment);
        start(context, "TX02", START_TIME, number(200000), null, null, null, null, -1, "T1", null);
        start(context, "TX02", START_TIME, number(90000), null, null, null, null, -1, "T2", null);
        start(context, "TX02", START_AT, null, number(25), null, null, null, -1, "T3", null);
        start(context, "TX02", START_AFTER, null, null, number(90), null, null, -1, "T4", null);
        assertEquals(List.of(NOON, Instant.parse("2026-09-16T00:00:00Z"), Instant.parse("2026-09-15T16:00:00Z"),
                NOON.plusSeconds(5400)), expirations);

        ProgramContext noZone = context(new CicsTaskContext(new CicsTaskId("task_nozone"), TransId.of("TX01"),
                "start-test", NOON), environment);
        assertThrows(CicsTaskStateException.class,
                () -> start(noZone, "TX02", START_TIME, number(200000), null, null, null, null, -1, "T5", null));
    }
}
