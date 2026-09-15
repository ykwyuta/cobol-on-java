package dev.cobolonjava.cics;

import static dev.cobolonjava.cics.CicsRuntimeOps.QUEUE_DELETEQ_TD;
import static dev.cobolonjava.cics.CicsRuntimeOps.QUEUE_DELETEQ_TS;
import static dev.cobolonjava.cics.CicsRuntimeOps.QUEUE_NEXT;
import static dev.cobolonjava.cics.CicsRuntimeOps.QUEUE_READQ_TD;
import static dev.cobolonjava.cics.CicsRuntimeOps.QUEUE_READQ_TS;
import static dev.cobolonjava.cics.CicsRuntimeOps.QUEUE_REWRITE;
import static dev.cobolonjava.cics.CicsRuntimeOps.QUEUE_WRITEQ_TD;
import static dev.cobolonjava.cics.CicsRuntimeOps.QUEUE_WRITEQ_TS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 一時記憶・一時データのキュー (暫定判断 P-137)。 */
@Tag("V1")
class CicsQueueTest {

    private static final CodePage CP = CodePages.DEFAULT;

    private final CicsTemporaryStoragePort storage = CicsTemporaryStoragePort.inMemory();
    private final CicsTransientDataPort transientData = CicsTransientDataPort.inMemory(
            List.of(new CicsTransientDataQueueDefinition("CSMT", 6)));
    private CicsExecution execution;

    private ProgramContext context(String taskId) {
        execution = new CicsExecution(new CicsTaskContext(new CicsTaskId(taskId), TransId.of("TX01"),
                "queue-test", Instant.EPOCH), 0, CicsEnvironment.unconfigured()
                .withTemporaryStorage(storage).withTransientData(transientData));
        return ProgramContext.standard().withCodePage(CP).withServices(
                RuntimeServices.builder().service(CicsExecution.class, execution).build());
    }

    private ProgramContext context(String taskId, CicsTransientDataPort port) {
        execution = new CicsExecution(new CicsTaskContext(new CicsTaskId(taskId), TransId.of("TX01"),
                "queue-test", Instant.EPOCH), 0, CicsEnvironment.unconfigured().withTransientData(port));
        return ProgramContext.standard().withCodePage(CP).withServices(
                RuntimeServices.builder().service(CicsExecution.class, execution).build());
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path directory;

    @Test
    @DisplayName("区画外のOUTPUTのキューはデータセットへ足し、INPUTのキューは順に読んで終わりはQZERO。向きの違う命令とDELETEQはINVREQ、開けなければNOTOPEN")
    void extrapartitionQueuesUseSequentialDataSets() {
        java.nio.file.Path input = directory.resolve("input.seq");
        java.nio.file.Path output = directory.resolve("output.seq");
        CicsTransientDataPort port = transientData.withExtrapartition(List.of(
                new CicsExtrapartitionQueueDefinition("LOGA", output,
                        CicsExtrapartitionQueueDefinition.Direction.OUTPUT, 6, false, CP),
                new CicsExtrapartitionQueueDefinition("INPA", input,
                        CicsExtrapartitionQueueDefinition.Direction.INPUT, 8, true, CP)));
        ProgramContext context = context("task_extra", port);

        assertEquals(CicsResponseCode.NORMAL, td(context, QUEUE_WRITEQ_TD, "LOGA", text("first "), null));
        assertEquals(CicsResponseCode.NORMAL, td(context, QUEUE_WRITEQ_TD, "LOGA", text("second"), null));
        assertEquals(CicsResponseCode.LENGERR, td(context, QUEUE_WRITEQ_TD, "LOGA", text("short"), null));
        assertEquals(CicsResponseCode.INVREQ, td(context, QUEUE_READQ_TD, "LOGA", text("......"), null));
        assertEquals(CicsResponseCode.INVREQ, td(context, QUEUE_DELETEQ_TD, "LOGA", null, null));
        dev.cobolonjava.runtime.file.SequentialDataSet written = dev.cobolonjava.runtime.file.SequentialDataSet.at(
                output, new dev.cobolonjava.runtime.file.DataSetAttributes(
                        dev.cobolonjava.runtime.file.RecordFormat.FIXED, 6, CP));
        written.open(dev.cobolonjava.runtime.file.OpenMode.INPUT);
        byte[] buffer = new byte[6];
        written.read(buffer);
        assertEquals("first ", CP.decode(buffer));
        written.read(buffer);
        assertEquals("second", CP.decode(buffer));
        written.close();

        // 無いデータセットは閉じたキューと同じ NOTOPEN
        assertEquals(CicsResponseCode.NOTOPEN, td(context, QUEUE_READQ_TD, "INPA", text("........"), null));
        dev.cobolonjava.runtime.file.SequentialDataSet job = dev.cobolonjava.runtime.file.SequentialDataSet.at(
                input, new dev.cobolonjava.runtime.file.DataSetAttributes(
                        dev.cobolonjava.runtime.file.RecordFormat.VARIABLE, 8, CP));
        job.open(dev.cobolonjava.runtime.file.OpenMode.OUTPUT);
        job.write(CP.encode("abc"));
        job.write(CP.encode("defghijk"));
        job.close();
        assertEquals(CicsResponseCode.INVREQ, td(context, QUEUE_WRITEQ_TD, "INPA", text("x"), null));
        DataView into = text("........");
        DataView length = halfword(8);
        assertEquals(CicsResponseCode.NORMAL, td(context, QUEUE_READQ_TD, "INPA", into, length));
        assertEquals(3, halfwordOf(length));
        assertEquals("abc.....", CP.decode(into.toByteArray()));
        assertEquals(CicsResponseCode.NORMAL, td(context, QUEUE_READQ_TD, "INPA", into, null));
        assertEquals("defghijk", CP.decode(into.toByteArray()));
        assertEquals(CicsResponseCode.QZERO, td(context, QUEUE_READQ_TD, "INPA", into, null));

        // 区画内のキューは今までどおり
        assertEquals(CicsResponseCode.NORMAL, td(context, QUEUE_WRITEQ_TD, "CSMT", text("intra "), null));
        assertEquals(CicsResponseCode.NORMAL, td(context, QUEUE_READQ_TD, "CSMT", text("......"), null));
    }

    @Test
    @DisplayName("回復可能なキューはtaskの変更をcommitまで見せず、ROLLBACKで読んだrecordを先頭へ戻し、DELETEQはcommitで消す")
    void recoverableQueueBuffersTaskChanges() {
        CicsTransientDataPort port = CicsTransientDataPort.inMemory(List.of(
                new CicsTransientDataQueueDefinition("RECQ", 6)
                        .withRecovery(CicsTransientDataQueueDefinition.Recovery.LOGICAL)));
        ProgramContext writer = context("task_writer", port);
        CicsTaskId writerId = execution.task().taskId();
        assertEquals(CicsResponseCode.NORMAL, td(writer, QUEUE_WRITEQ_TD, "RECQ", text("first "), null));
        assertEquals(CicsResponseCode.NORMAL, td(writer, QUEUE_WRITEQ_TD, "RECQ", text("second"), null));
        assertEquals(CicsResponseCode.QZERO, td(writer, QUEUE_READQ_TD, "RECQ", text("......"), null));

        ProgramContext reader = context("task_reader", port);
        CicsTaskId readerId = execution.task().taskId();
        assertEquals(CicsResponseCode.QZERO, td(reader, QUEUE_READQ_TD, "RECQ", text("......"), null));
        port.commitUnitOfWork(writerId);

        DataView into = text("......");
        assertEquals(CicsResponseCode.NORMAL, td(reader, QUEUE_READQ_TD, "RECQ", into, null));
        assertEquals("first ", CP.decode(into.toByteArray()));
        port.rollbackUnitOfWork(readerId);
        assertEquals(CicsResponseCode.NORMAL, td(reader, QUEUE_READQ_TD, "RECQ", into, null));
        assertEquals("first ", CP.decode(into.toByteArray()));
        port.commitUnitOfWork(readerId);

        ProgramContext deleter = context("task_deleter", port);
        CicsTaskId deleterId = execution.task().taskId();
        assertEquals(CicsResponseCode.NORMAL, td(deleter, QUEUE_DELETEQ_TD, "RECQ", null, null));
        // EIB は task ごとなので、同じ task の context を作り直して読み手の EIB を見る
        reader = context("task_reader", port);
        assertEquals(CicsResponseCode.NORMAL, td(reader, QUEUE_READQ_TD, "RECQ", into, null));
        assertEquals("second", CP.decode(into.toByteArray()));
        port.rollbackUnitOfWork(readerId);
        port.commitUnitOfWork(deleterId);
        assertEquals(CicsResponseCode.QZERO, td(reader, QUEUE_READQ_TD, "RECQ", into, null));
    }

    private int eib(int offset) {
        return ByteBuffer.wrap(execution.eib(CP).storage().array(), offset, 4).getInt();
    }

    private static DataView text(String value) {
        return Storage.copyOf(CP.encode(value)).whole();
    }

    private static DataView halfword(int value) {
        return Storage.copyOf(new byte[] {(byte) (value >>> 8), (byte) value}).whole();
    }

    private static int halfwordOf(DataView view) {
        byte[] bytes = view.toByteArray();
        return (short) (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
    }

    private int ts(ProgramContext context, int kind, String name, int nameLength, DataView data, DataView length,
                   DataView item, int itemLiteral, DataView numItems, int flags) {
        CicsRuntimeOps.queueCommandCondition(context, kind, name, null, nameLength, data, length, -1, item,
                itemLiteral, numItems, flags, true);
        return eib(CicsEib.EIBRESP_OFFSET);
    }

    private int td(ProgramContext context, int kind, String name, DataView data, DataView length) {
        CicsRuntimeOps.queueCommandCondition(context, kind, name, null, 4, data, length, -1, null, -1, null, 0, true);
        return eib(CicsEib.EIBRESP_OFFSET);
    }

    @Test
    @DisplayName("WRITEQ TSはitemの番号を返し、READQ TSのNEXTは直前に読まれたitemの次を読み、終わりはITEMERR")
    void writesAndReadsTemporaryStorage() {
        ProgramContext writer = context("task_writer");
        DataView item = halfword(0);
        for (String value : List.of("first ", "second", "third ")) {
            assertEquals(CicsResponseCode.NORMAL,
                    ts(writer, QUEUE_WRITEQ_TS, "SCRATCH", 8, text(value), null, item, -1, null, 0));
        }
        assertEquals(3, halfwordOf(item));
        assertEquals(0x0A02, ByteBuffer.wrap(execution.eib(CP).storage().array(), CicsEib.EIBFN_OFFSET, 2).getShort());

        // キューは task をまたいで残る。QNAME の 16 byte の名前は、空白を足した QUEUE の名前と同じキューを指す
        ProgramContext reader = context("task_reader");
        DataView into = text("......");
        DataView numItems = halfword(0);
        assertEquals(CicsResponseCode.NORMAL,
                ts(reader, QUEUE_READQ_TS, "SCRATCH", 16, into, null, null, 2, numItems, 0));
        assertEquals("second", CP.decode(into.toByteArray()));
        assertEquals(3, halfwordOf(numItems));
        assertEquals(CicsResponseCode.NORMAL,
                ts(reader, QUEUE_READQ_TS, "SCRATCH", 8, into, null, null, -1, null, QUEUE_NEXT));
        assertEquals("third ", CP.decode(into.toByteArray()));
        assertEquals(CicsResponseCode.ITEMERR,
                ts(reader, QUEUE_READQ_TS, "SCRATCH", 8, into, null, null, -1, null, QUEUE_NEXT));

        assertEquals(CicsResponseCode.NORMAL, ts(reader, QUEUE_WRITEQ_TS, "SCRATCH", 8, text("SECOND"), null,
                halfword(2), -1, null, QUEUE_REWRITE));
        assertEquals(CicsResponseCode.ITEMERR, ts(reader, QUEUE_WRITEQ_TS, "SCRATCH", 8, text("fourth"), null,
                halfword(4), -1, null, QUEUE_REWRITE));
        ts(reader, QUEUE_READQ_TS, "SCRATCH", 8, into, null, null, 2, null, 0);
        assertEquals("SECOND", CP.decode(into.toByteArray()));

        // 受取域より長い item は切り詰めて LENGERR、LENGTH の域には本来の長さを置く
        DataView length = halfword(3);
        assertEquals(CicsResponseCode.LENGERR,
                ts(reader, QUEUE_READQ_TS, "SCRATCH", 8, text("......"), length, null, 1, null, 0));
        assertEquals(6, halfwordOf(length));

        assertEquals(CicsResponseCode.NORMAL, ts(reader, QUEUE_DELETEQ_TS, "SCRATCH", 8, null, null, null, -1,
                null, 0));
        assertEquals(CicsResponseCode.QIDERR,
                ts(reader, QUEUE_READQ_TS, "SCRATCH", 8, into, null, null, 1, null, 0));
        assertEquals(CicsResponseCode.QIDERR, ts(reader, QUEUE_DELETEQ_TS, "SCRATCH", 8, null, null, null, -1,
                null, 0));
        assertEquals(CicsResponseCode.QIDERR, ts(reader, QUEUE_WRITEQ_TS, "SCRATCH", 8, text("x"), null,
                halfword(1), -1, null, QUEUE_REWRITE));
    }

    @Test
    @DisplayName("TSの長さ0はLENGERR、binary zeroの名前はINVREQ、CICSが使う名前は条件が無いので失敗させる")
    void rejectsInvalidTemporaryStorageRequests() {
        ProgramContext context = context("task_invalid");
        CicsRuntimeOps.queueCommandCondition(context, QUEUE_WRITEQ_TS, "SCRATCH", null, 8, text("abc"), null, 0,
                null, -1, null, 0, true);
        assertEquals(CicsResponseCode.LENGERR, eib(CicsEib.EIBRESP_OFFSET));
        CicsRuntimeOps.queueCommandCondition(context, QUEUE_WRITEQ_TS, null, new byte[8], 8, text("abc"), null, -1,
                null, -1, null, 0, true);
        assertEquals(CicsResponseCode.INVREQ, eib(CicsEib.EIBRESP_OFFSET));
        assertThrows(CicsTaskStateException.class,
                () -> ts(context, QUEUE_WRITEQ_TS, "DFHQUEUE", 8, text("abc"), null, null, -1, null, 0));
    }

    @Test
    @DisplayName("TDは定義したキューだけに書き、先に書いたrecordから取り出す。空はQZERO、定義の無いキューはQIDERR")
    void writesAndReadsTransientData() {
        ProgramContext context = context("task_td");
        assertEquals(CicsResponseCode.NORMAL, td(context, QUEUE_WRITEQ_TD, "CSMT", text("one"), null));
        assertEquals(CicsResponseCode.NORMAL, td(context, QUEUE_WRITEQ_TD, "CSMT", text("second"), null));
        assertEquals(CicsResponseCode.LENGERR, td(context, QUEUE_WRITEQ_TD, "CSMT", text("toolong"), null));
        assertEquals(CicsResponseCode.QIDERR, td(context, QUEUE_WRITEQ_TD, "NOPE", text("one"), null));
        assertEquals(0x0802, ByteBuffer.wrap(execution.eib(CP).storage().array(), CicsEib.EIBFN_OFFSET, 2).getShort());

        DataView into = text("......");
        DataView length = halfword(6);
        assertEquals(CicsResponseCode.NORMAL, td(context, QUEUE_READQ_TD, "CSMT", into, length));
        assertEquals("one...", CP.decode(into.toByteArray()));
        assertEquals(3, halfwordOf(length));

        // 切り詰めても record は読まれてキューから消える
        length = halfword(2);
        assertEquals(CicsResponseCode.LENGERR, td(context, QUEUE_READQ_TD, "CSMT", text("......"), length));
        assertEquals(6, halfwordOf(length));
        assertEquals(CicsResponseCode.QZERO, td(context, QUEUE_READQ_TD, "CSMT", into, null));

        td(context, QUEUE_WRITEQ_TD, "CSMT", text("again"), null);
        assertEquals(CicsResponseCode.NORMAL, td(context, QUEUE_DELETEQ_TD, "CSMT", null, null));
        assertEquals(CicsResponseCode.QZERO, td(context, QUEUE_READQ_TD, "CSMT", into, null));
        assertEquals(CicsResponseCode.QIDERR, td(context, QUEUE_DELETEQ_TD, "NOPE", null, null));
    }
}
