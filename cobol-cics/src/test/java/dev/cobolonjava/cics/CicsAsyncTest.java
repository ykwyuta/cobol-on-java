package dev.cobolonjava.cics;

import static dev.cobolonjava.cics.CicsRuntimeOps.ASYNC_FETCH_ANY;
import static dev.cobolonjava.cics.CicsRuntimeOps.ASYNC_FETCH_CHILD;
import static dev.cobolonjava.cics.CicsRuntimeOps.ASYNC_FREE_CHILD;
import static dev.cobolonjava.cics.CicsRuntimeOps.ASYNC_RUN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 非同期 API (暫定判断 P-140)。 */
@Tag("V1")
class CicsAsyncTest {

    private static final CodePage CP = CodePages.DEFAULT;

    private final CountDownLatch gate = new CountDownLatch(1);
    private final CountDownLatch never = new CountDownLatch(1);
    private final CicsAsyncPort port = CicsAsyncPort.inMemory(new CicsTransactionRegistry(List.of(
            new CicsTransactionDefinition(TransId.of("TX03"), ProgramId.of("CHILD"), Duration.ofSeconds(5),
                    0, 4, 64, 256, true),
            new CicsTransactionDefinition(TransId.of("TX04"), ProgramId.of("CHILD"), Duration.ofSeconds(5),
                    0, 0, 0, 0, false))), this::child);
    private CicsExecution execution;

    private CicsPayload child(CicsAsyncChild request) {
        try {
            if (request.containers().containsKey("WAIT")) {
                never.await(5, TimeUnit.SECONDS);
            } else {
                gate.await(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (request.containers().containsKey("BOOM")) {
            throw new CicsAbend(new CicsTaskId("task_child"), AbendCommand.user(CicsAbendCode.of("PLOP"), false, false));
        }
        Map<String, byte[]> reply = new LinkedHashMap<>(request.containers());
        reply.put("REPLY", CP.encode("done"));
        return new CicsPayload(new byte[0], reply, request.channelName().orElse(null));
    }

    private ProgramContext context(String taskId) {
        execution = new CicsExecution(new CicsTaskContext(new CicsTaskId(taskId), TransId.of("TX01"),
                "async-test", Instant.EPOCH), 0, CicsEnvironment.unconfigured().withAsync(port));
        return ProgramContext.standard().withCodePage(CP).withServices(
                RuntimeServices.builder().service(CicsExecution.class, execution).build());
    }

    private int resp() {
        return ByteBuffer.wrap(execution.eib(CP).storage().array(), CicsEib.EIBRESP_OFFSET, 4).getInt();
    }

    private int resp2() {
        return ByteBuffer.wrap(execution.eib(CP).storage().array(), CicsEib.EIBRESP2_OFFSET, 4).getInt();
    }

    private static DataView area(int length) {
        return Storage.allocate(length).whole();
    }

    private int run(ProgramContext context, String transaction, String channel, DataView token) {
        CicsRuntimeOps.asyncCommandCondition(context, ASYNC_RUN, transaction, null, channel, null, null, token, null,
                null, -1, null, false, true);
        return resp();
    }

    private int fetch(ProgramContext context, int kind, DataView token, DataView channel, DataView status,
                      DataView abend, int timeout, boolean noSuspend) {
        CicsRuntimeOps.asyncCommandCondition(context, kind, null, null, null, null, channel, token, status, abend,
                timeout, null, noSuspend, true);
        return resp();
    }

    @Test
    @DisplayName("RUNは子をchannelの写しで起こし、FETCH ANYはreply channelに名前をつけて返す。取り出し済みはNOTFND、FREE CHILDの二度目はINVREQ 50")
    void runsAndFetchesChildren() {
        ProgramContext parent = context("task_parent");
        execution.putChannel("CIPCREDCHANN", Map.of("CIPA", CP.encode("input")));
        DataView token = area(16);
        assertEquals(CicsResponseCode.NORMAL, run(parent, "TX03", "CIPCREDCHANN", token));
        assertEquals(0x343E, ByteBuffer.wrap(execution.eib(CP).storage().array(), CicsEib.EIBFN_OFFSET, 2).getShort());
        assertNotEquals(0, ByteBuffer.wrap(token.toByteArray()).getLong());

        DataView fetched = area(16);
        assertEquals(CicsResponseCode.NOTFINISHED, fetch(parent, ASYNC_FETCH_ANY, fetched, null, null, null, -1, true));
        assertEquals(52, resp2());

        gate.countDown();
        DataView channel = area(16);
        DataView status = area(4);
        DataView abend = area(4);
        assertEquals(CicsResponseCode.NORMAL, fetch(parent, ASYNC_FETCH_ANY, fetched, channel, status, abend, 5000,
                false));
        assertArrayEquals(token.toByteArray(), fetched.toByteArray());
        assertEquals(CicsCvda.NORMAL, ByteBuffer.wrap(status.toByteArray()).getInt());
        assertEquals("    ", CP.decode(abend.toByteArray()));
        Map<String, byte[]> reply = execution.channel(CP.decode(channel.toByteArray()).stripTrailing(), false)
                .orElseThrow();
        assertEquals("done", CP.decode(reply.get("REPLY")));
        assertEquals("input", CP.decode(reply.get("CIPA")));

        assertEquals(CicsResponseCode.NOTFND, fetch(parent, ASYNC_FETCH_ANY, area(16), null, null, null, -1, true));
        assertEquals(1, resp2());
        assertEquals(CicsResponseCode.INVREQ, fetch(parent, ASYNC_FETCH_CHILD, token, null, null, null, -1, true));
        assertEquals(51, resp2());
        CicsRuntimeOps.asyncCommandCondition(parent, ASYNC_FREE_CHILD, null, null, null, null, null, token, null,
                null, -1, null, false, true);
        assertEquals(CicsResponseCode.NORMAL, resp());
        CicsRuntimeOps.asyncCommandCondition(parent, ASYNC_FREE_CHILD, null, null, null, null, null, token, null,
                null, -1, null, false, true);
        assertEquals(CicsResponseCode.INVREQ, resp());
        assertEquals(50, resp2());
    }

    @Test
    @DisplayName("子のABENDはCOMPSTATUS ABENDとABCODE、未定義はTRANSIDERR 1、使えないtransactionはDISABLED 50、子の無い親はINVREQ 52")
    void reportsChildFailuresAndInvalidRequests() {
        gate.countDown();
        ProgramContext parent = context("task_failing");
        assertEquals(CicsResponseCode.INVREQ, fetch(parent, ASYNC_FETCH_ANY, area(16), null, null, null, -1, true));
        assertEquals(52, resp2());

        execution.putChannel("BAD", Map.of("BOOM", new byte[] {1}));
        DataView token = area(16);
        run(parent, "TX03", "BAD", token);
        DataView channel = area(16);
        DataView status = area(4);
        DataView abend = area(4);
        assertEquals(CicsResponseCode.NORMAL, fetch(parent, ASYNC_FETCH_CHILD, token, channel, status, abend, 5000,
                false));
        assertEquals(CicsCvda.ABEND, ByteBuffer.wrap(status.toByteArray()).getInt());
        assertEquals("PLOP", CP.decode(abend.toByteArray()));
        assertEquals(" ".repeat(16), CP.decode(channel.toByteArray()));

        assertEquals(CicsResponseCode.TRANSIDERR, run(parent, "NOPE", null, area(16)));
        assertEquals(1, resp2());
        assertEquals(CicsResponseCode.DISABLED, run(parent, "TX04", null, area(16)));
        assertEquals(50, resp2());

        assertEquals(CicsResponseCode.INVREQ, fetch(parent, ASYNC_FETCH_ANY, area(16), null, null, null, 40_800_001,
                false));
        assertEquals(241, resp2());

        execution.putChannel("SLOW", Map.of("WAIT", new byte[] {1}));
        run(parent, "TX03", "SLOW", area(16));
        assertEquals(CicsResponseCode.NOTFINISHED, fetch(parent, ASYNC_FETCH_ANY, area(16), null, null, null, 50,
                false));
        assertEquals(53, resp2());
        never.countDown();
    }
}
