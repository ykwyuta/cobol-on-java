package dev.cobolonjava.cics;

import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_CONSISTENT;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_DEBKEY;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_DEBREC;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_DELETE;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_MASSINSERT;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_NOSUSPEND;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_RBA;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_XRBA;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_ENDBR;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_GENERIC;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_GTEQ;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_READ;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_READNEXT;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_READPREV;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_REWRITE;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_RRN;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_STARTBR;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_UNLOCK;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_UPDATE;
import static dev.cobolonjava.cics.CicsRuntimeOps.FILE_WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** file control (暫定判断 P-131、P-136)。 */
@Tag("V1")
class CicsFileControlTest {

    private static final CodePage CP = CodePages.DEFAULT;

    @TempDir
    Path directory;

    private CicsExecution execution;

    private ProgramContext context(CicsFilePort files) {
        return context(files, "task_file");
    }

    private ProgramContext context(CicsFilePort files, String taskId) {
        execution = new CicsExecution(new CicsTaskContext(new CicsTaskId(taskId), TransId.of("TX01"),
                "file-test", Instant.EPOCH), 0, CicsEnvironment.unconfigured().withFiles(files));
        return ProgramContext.standard().withCodePage(CP).withServices(
                RuntimeServices.builder().service(CicsExecution.class, execution).build());
    }

    private CicsFilePort keyed() {
        return CicsFilePort.dataSets(List.of(
                new CicsFileDefinition("CUSTFILE", directory.resolve("cust.ksds"), 0, 4, 10, CP)));
    }

    private int eib(int offset) {
        return ByteBuffer.wrap(execution.eib(CP).storage().array(), offset, 4).getInt();
    }

    private int resp() {
        return eib(CicsEib.EIBRESP_OFFSET);
    }

    private int resp2() {
        return eib(CicsEib.EIBRESP2_OFFSET);
    }

    private static DataView text(String value) {
        return Storage.copyOf(CP.encode(value)).whole();
    }

    private static DataView halfword(int value) {
        return Storage.copyOf(new byte[] {(byte) (value >>> 8), (byte) value}).whole();
    }

    private static DataView fullword(int value) {
        return Storage.copyOf(ByteBuffer.allocate(4).putInt(value).array()).whole();
    }

    private static int halfwordOf(DataView view) {
        byte[] bytes = view.toByteArray();
        return (short) (((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
    }

    /** RESP を立てる形 (NOHANDLE) で命令を流し、EIBRESP を返す。 */
    private int run(ProgramContext context, int kind, DataView data, DataView length, DataView ridfld,
                    int keyLength, int reqid, DataView numrec, int flags) {
        CicsRuntimeOps.fileCommandCondition(context, kind, "CUSTFILE", null, data, length, -1, ridfld, null,
                keyLength, null, reqid, numrec, flags, true);
        return resp();
    }

    private void write(ProgramContext context, String record) {
        assertEquals(CicsResponseCode.NORMAL,
                run(context, FILE_WRITE, text(record), null, text(record.substring(0, 4)), -1, -1, null, 0));
    }

    @Test
    @DisplayName("定義したKSDSへ鍵で書き、同じ鍵はDUPREC、定義の無いfileはFILENOTFOUND")
    void writesKeyedRecords() {
        CicsFilePort files = keyed();
        ProgramContext context = context(files);
        write(context, "K001record");
        assertEquals("CUSTFILE", CP.decode(java.util.Arrays.copyOfRange(execution.eib(CP).storage().array(),
                CicsEib.EIBDS_OFFSET, CicsEib.EIBDS_OFFSET + CicsEib.EIBDS_LENGTH)));

        // 別の task でも同じデータセットに残っている
        ProgramContext next = context(files, "task_other");
        assertEquals(CicsResponseCode.DUPREC,
                run(next, FILE_WRITE, text("K001other "), null, text("K001"), -1, -1, null, 0));
        assertEquals(150, resp2());

        CicsRuntimeOps.fileCommandCondition(next, FILE_WRITE, null, CP.encode("NOFILE  "), text("K002record"), null,
                -1, text("K002"), null, -1, null, -1, null, 0, true);
        assertEquals(CicsResponseCode.FILENOTFOUND, resp());
        assertThrows(CicsTaskStateException.class, () -> CicsRuntimeOps.fileCommandCondition(next, FILE_WRITE,
                "NOFILE", null, text("K002record"), null, -1, text("K002"), null, -1, null, -1, null, 0, false));
    }

    @Test
    @DisplayName("鍵の食い違いは条件の値が無いので失敗させ、固定長と違う長さはLENGERR 14")
    void rejectsMismatches() {
        ProgramContext context = context(keyed());
        assertThrows(CicsTaskStateException.class,
                () -> run(context, FILE_WRITE, text("K001record"), null, text("K999"), -1, -1, null, 0));
        assertEquals(CicsResponseCode.LENGERR,
                run(context, FILE_WRITE, text("K001rec"), null, text("K001"), -1, -1, null, 0));
        assertEquals(14, resp2());
    }

    @Test
    @DisplayName("READは鍵、GTEQ、総称で引き、見つけた鍵をRIDFLDへ返す。KEYLENGTHの誤りはINVREQ 25/26")
    void readsByKeyGteqAndGeneric() {
        ProgramContext context = context(keyed());
        write(context, "K001aaaaaa");
        write(context, "K003cccccc");
        write(context, "K005eeeeee");
        DataView into = text("          ");

        assertEquals(CicsResponseCode.NORMAL, run(context, FILE_READ, into, null, text("K003"), -1, -1, null, 0));
        assertEquals("K003cccccc", CP.decode(into.toByteArray()));
        assertEquals(0x0602, ByteBuffer.wrap(execution.eib(CP).storage().array(), CicsEib.EIBFN_OFFSET, 2).getShort());

        assertEquals(CicsResponseCode.NOTFND, run(context, FILE_READ, into, null, text("K002"), -1, -1, null, 0));
        assertEquals(80, resp2());

        DataView ridfld = text("K002");
        assertEquals(CicsResponseCode.NORMAL, run(context, FILE_READ, into, null, ridfld, -1, -1, null, FILE_GTEQ));
        assertEquals("K003", CP.decode(ridfld.toByteArray()));

        DataView generic = text("K00 ");
        assertEquals(CicsResponseCode.NORMAL,
                run(context, FILE_READ, into, null, generic, 3, -1, null, FILE_GENERIC));
        assertEquals("K001", CP.decode(generic.toByteArray()));
        assertEquals("K001aaaaaa", CP.decode(into.toByteArray()));

        assertEquals(CicsResponseCode.INVREQ, run(context, FILE_READ, into, null, text("K001"), 4, -1, null,
                FILE_GENERIC));
        assertEquals(25, resp2());
        assertEquals(CicsResponseCode.INVREQ, run(context, FILE_READ, into, null, text("K001"), 3, -1, null, 0));
        assertEquals(26, resp2());

        // 固定長の record を違う長さで読む形は、LENGERR (RESP2 13) で何を移すかを確かめていない
        assertThrows(CicsTaskStateException.class,
                () -> run(context, FILE_READ, text("            "), null, text("K001"), -1, -1, null, 0));
    }

    @Test
    @DisplayName("READ UPDATEで持ったrecordをREWRITE・DELETEし、総称のDELETEは消した数をNUMRECへ返す")
    void updatesAndDeletes() {
        ProgramContext context = context(keyed());
        write(context, "K001aaaaaa");
        write(context, "K003cccccc");
        write(context, "K005eeeeee");
        DataView into = text("          ");

        assertEquals(CicsResponseCode.NORMAL,
                run(context, FILE_READ, into, null, text("K003"), -1, -1, null, FILE_UPDATE));
        assertEquals(CicsResponseCode.INVREQ,
                run(context, FILE_READ, into, null, text("K001"), -1, -1, null, FILE_UPDATE));
        assertEquals(28, resp2());
        assertEquals(CicsResponseCode.NORMAL,
                run(context, FILE_REWRITE, text("K003CCCCCC"), null, null, -1, -1, null, 0));
        assertEquals(CicsResponseCode.INVREQ, run(context, FILE_REWRITE, text("K003dddddd"), null, null, -1, -1,
                null, 0));
        assertEquals(30, resp2());
        run(context, FILE_READ, into, null, text("K003"), -1, -1, null, 0);
        assertEquals("K003CCCCCC", CP.decode(into.toByteArray()));

        // 鍵を変える REWRITE は、文書が条件を示さないので失敗させる
        run(context, FILE_READ, into, null, text("K003"), -1, -1, null, FILE_UPDATE);
        assertThrows(CicsTaskStateException.class,
                () -> run(context, FILE_REWRITE, text("K004CCCCCC"), null, null, -1, -1, null, 0));
        assertEquals(CicsResponseCode.NORMAL, run(context, FILE_UNLOCK, null, null, null, -1, -1, null, 0));

        assertEquals(CicsResponseCode.INVREQ, run(context, FILE_DELETE, null, null, null, -1, -1, null, 0));
        assertEquals(31, resp2());
        run(context, FILE_READ, into, null, text("K001"), -1, -1, null, FILE_UPDATE);
        assertEquals(CicsResponseCode.NORMAL, run(context, FILE_DELETE, null, null, null, -1, -1, null, 0));

        DataView numrec = halfword(0);
        assertEquals(CicsResponseCode.NORMAL,
                run(context, FILE_DELETE, null, null, text("K00 "), 3, -1, numrec, FILE_GENERIC));
        assertEquals(2, halfwordOf(numrec));
        assertEquals(CicsResponseCode.NOTFND, run(context, FILE_READ, into, null, text("K005"), -1, -1, null, 0));
    }

    @Test
    @DisplayName("他のtaskがREAD UPDATEで持つrecordは期限まで待ち、UNLOCKのあとは得られる。更新しないREADは待たない")
    void waitsForRecordsHeldByAnotherTask() {
        CicsFilePort files = keyed();
        ProgramContext owner = context(files, "task_owner");
        write(owner, "K001aaaaaa");
        DataView into = text("          ");
        assertEquals(CicsResponseCode.NORMAL,
                run(owner, FILE_READ, into, null, text("K001"), -1, -1, null, FILE_UPDATE));
        CicsExecution ownerExecution = execution;

        ProgramContext other = context(files, "task_waiter");
        execution.limitTo(Instant.now().plusMillis(200));
        assertThrows(CicsTaskStateException.class,
                () -> run(other, FILE_READ, into, null, text("K001"), -1, -1, null, FILE_UPDATE));
        assertEquals(CicsResponseCode.NORMAL, run(other, FILE_READ, into, null, text("K001"), -1, -1, null, 0));

        CicsExecution waiter = execution;
        execution = ownerExecution;
        run(owner, FILE_UNLOCK, null, null, null, -1, -1, null, 0);
        execution = waiter;
        assertEquals(CicsResponseCode.NORMAL,
                run(other, FILE_READ, into, null, text("K001"), -1, -1, null, FILE_UPDATE));
    }

    @Test
    @DisplayName("browseは前後に読み、向きを変えると同じrecordをもう一度返し、端はENDFILE。RIDFLDを変えると位置づけ直す")
    void browsesForwardAndBackward() {
        CicsFilePort files = keyed();
        ProgramContext context = context(files);
        write(context, "K001aaaaaa");
        write(context, "K002bbbbbb");
        write(context, "K003cccccc");
        DataView into = text("          ");
        DataView ridfld = text("K002");

        assertEquals(CicsResponseCode.NORMAL, run(context, FILE_STARTBR, null, null, ridfld, -1, -1, null, 0));
        assertEquals(CicsResponseCode.INVREQ, run(context, FILE_STARTBR, null, null, ridfld, -1, -1, null, 0));
        assertEquals(33, resp2());
        List<String> keys = new java.util.ArrayList<>();
        int[] moves = {FILE_READNEXT, FILE_READNEXT, FILE_READNEXT, FILE_READPREV, FILE_READPREV, FILE_READPREV,
            FILE_READPREV};
        for (int move : moves) {
            int response = run(context, move, into, null, ridfld, -1, -1, null, 0);
            keys.add(response == CicsResponseCode.NORMAL ? CP.decode(ridfld.toByteArray()) : "END" + resp2());
        }
        assertEquals(List.of("K002", "K003", "END90", "K003", "K002", "K001", "END90"), keys);
        assertEquals(CicsResponseCode.NORMAL, run(context, FILE_ENDBR, null, null, null, -1, -1, null, 0));
        assertEquals(CicsResponseCode.INVREQ, run(context, FILE_ENDBR, null, null, null, -1, -1, null, 0));
        assertEquals(35, resp2());
        assertEquals(CicsResponseCode.INVREQ, run(context, FILE_READNEXT, into, null, ridfld, -1, -1, null, 0));
        assertEquals(34, resp2());

        // すべて X'FF' の RIDFLD は終わりへ位置づけ、READPREV が最後の record を返す
        DataView high = Storage.copyOf(new byte[] {-1, -1, -1, -1}).whole();
        run(context, FILE_STARTBR, null, null, high, -1, 1, null, 0);
        assertEquals(CicsResponseCode.NORMAL, run(context, FILE_READPREV, into, null, high, -1, 1, null, 0));
        assertEquals("K003", CP.decode(high.toByteArray()));

        // RIDFLD を変えると、その鍵以上の最初の record から読み直す
        DataView moved = text("K001");
        run(context, FILE_STARTBR, null, null, moved, -1, 2, null, 0);
        run(context, FILE_READNEXT, into, null, moved, -1, 2, null, 0);
        moved.setBytes(CP.encode("K003"));
        run(context, FILE_READNEXT, into, null, moved, -1, 2, null, 0);
        assertEquals("K003cccccc", CP.decode(into.toByteArray()));

        // GTEQ で無い鍵に位置づけた直後の READPREV は NOTFND、総称の browse の READPREV は INVREQ 24
        run(context, FILE_STARTBR, null, null, text("K000"), -1, 3, null, 0);
        assertEquals(CicsResponseCode.NOTFND, run(context, FILE_READPREV, into, null, text("K000"), -1, 3, null, 0));
        run(context, FILE_STARTBR, null, null, text("K00 "), 3, 4, null, FILE_GENERIC);
        assertEquals(CicsResponseCode.INVREQ, run(context, FILE_READPREV, into, null, text("K00 "), -1, 4, null, 0));
        assertEquals(24, resp2());

        // SYNCPOINT で browse は終わる
        files.releaseUnitOfWork(execution.task().taskId());
        assertEquals(CicsResponseCode.INVREQ, run(context, FILE_READNEXT, into, null, moved, -1, 2, null, 0));
        assertEquals(34, resp2());
    }

    @Test
    @DisplayName("可変長のRRDSは番号で読み書きし、長いrecordは切り詰めてLENGERR 11、最大を越える書き込みはLENGERR 12")
    void relativeVariableLengthRecords() {
        CicsFilePort files = CicsFilePort.dataSets(List.of(
                CicsFileDefinition.relative("CUSTFILE", directory.resolve("cust.rrds"), 8, true, CP)));
        ProgramContext context = context(files);
        assertEquals(CicsResponseCode.NORMAL,
                run(context, FILE_WRITE, text("abc"), null, fullword(3), -1, -1, null, FILE_RRN));
        assertEquals(CicsResponseCode.NORMAL,
                run(context, FILE_WRITE, text("abcdefgh"), null, fullword(1), -1, -1, null, FILE_RRN));
        assertEquals(CicsResponseCode.LENGERR,
                run(context, FILE_WRITE, text("abcdefghi"), null, fullword(2), -1, -1, null, FILE_RRN));
        assertEquals(12, resp2());

        DataView into = text("........");
        DataView length = halfword(8);
        assertEquals(CicsResponseCode.NORMAL, run(context, FILE_READ, into, length, fullword(3), -1, -1, null,
                FILE_RRN));
        assertEquals(3, halfwordOf(length));
        assertEquals("abc.....", CP.decode(into.toByteArray()));

        length = halfword(4);
        assertEquals(CicsResponseCode.LENGERR, run(context, FILE_READ, into, length, fullword(1), -1, -1, null,
                FILE_RRN));
        assertEquals(11, resp2());
        assertEquals(8, halfwordOf(length));
        assertEquals("abcd....", CP.decode(into.toByteArray()));

        DataView number = fullword(2);
        assertEquals(CicsResponseCode.NOTFND, run(context, FILE_READ, into, null, number, -1, -1, null, FILE_RRN));
        assertEquals(CicsResponseCode.NORMAL, run(context, FILE_READ, into, null, number, -1, -1, null,
                FILE_RRN | FILE_GTEQ));
        assertEquals(3, ByteBuffer.wrap(number.toByteArray()).getInt());

        DataView browse = fullword(0);
        run(context, FILE_STARTBR, null, null, browse, -1, -1, null, FILE_RRN);
        List<Integer> numbers = new java.util.ArrayList<>();
        while (run(context, FILE_READNEXT, text("........"), null, browse, -1, -1, null, FILE_RRN)
                == CicsResponseCode.NORMAL) {
            numbers.add(ByteBuffer.wrap(browse.toByteArray()).getInt());
        }
        assertEquals(List.of(1, 3), numbers);
        assertEquals(CicsResponseCode.ENDFILE, resp());
    }

    /** TOKEN と SYSID を渡す形で命令を流し、EIBRESP を返す。 */
    private int runWith(ProgramContext context, int kind, DataView data, DataView ridfld, DataView token,
                        String sysid, int flags) {
        CicsRuntimeOps.fileCommandCondition(context, kind, "CUSTFILE", null, data, null, -1, ridfld, null, -1,
                null, -1, null, token, sysid, null, flags, true);
        return resp();
    }

    private static int intOf(DataView view) {
        return ByteBuffer.wrap(view.toByteArray()).getInt();
    }

    @Test
    @DisplayName("ESDSは終わりに足してRBAを返し、RBAとXRBAで読み、browseし、DELETEはINVREQ 21、長さを変えるREWRITEはILLOGIC 110")
    void entrySequencedFileByRba() {
        CicsFilePort files = CicsFilePort.dataSets(List.of(
                CicsFileDefinition.entrySequenced("CUSTFILE", directory.resolve("cust.esds"), 8, true, CP)));
        ProgramContext context = context(files);
        DataView rba = fullword(-1);
        assertEquals(CicsResponseCode.NORMAL, runWith(context, FILE_WRITE, text("first"), rba, null, null,
                FILE_RBA | FILE_MASSINSERT));
        assertEquals(0, intOf(rba));
        assertEquals(CicsResponseCode.NORMAL, runWith(context, FILE_WRITE, text("second!!"), rba, null, null, FILE_RBA));
        assertEquals(5, intOf(rba));
        DataView xrba = Storage.copyOf(new byte[8]).whole();
        runWith(context, FILE_WRITE, text("3rd"), xrba, null, null, FILE_XRBA);
        assertEquals(13L, ByteBuffer.wrap(xrba.toByteArray()).getLong());

        DataView into = text("........");
        assertEquals(CicsResponseCode.NORMAL, runWith(context, FILE_READ, into, fullword(5), null, null, FILE_RBA));
        assertEquals("second!!", CP.decode(into.toByteArray()));
        assertEquals(CicsResponseCode.NOTFND, runWith(context, FILE_READ, into, fullword(6), null, null, FILE_RBA));

        DataView cursor = fullword(1);
        runWith(context, FILE_STARTBR, null, cursor, null, null, FILE_RBA | FILE_GTEQ);
        List<Integer> seen = new java.util.ArrayList<>();
        while (runWith(context, FILE_READNEXT, text("........"), cursor, null, null, FILE_RBA)
                == CicsResponseCode.NORMAL) {
            seen.add(intOf(cursor));
        }
        assertEquals(List.of(5, 13), seen);

        assertEquals(CicsResponseCode.INVREQ, runWith(context, FILE_DELETE, null, fullword(0), null, null, FILE_RBA));
        assertEquals(21, resp2());

        runWith(context, FILE_READ, into, fullword(0), null, null, FILE_RBA | FILE_UPDATE);
        assertEquals(CicsResponseCode.ILLOGIC, runWith(context, FILE_REWRITE, text("longer"), null, null, null, 0));
        assertEquals(110, resp2());
        assertEquals(CicsResponseCode.NORMAL, runWith(context, FILE_REWRITE, text("FIRST"), null, null, null, 0));
        runWith(context, FILE_READ, into, fullword(0), null, null, FILE_RBA);
        assertEquals("FIRST", CP.decode(into.subView(0, 5).toByteArray()));

        // ESDS に RBA を書かない形は、文書に条件が無いので失敗させる
        assertThrows(CicsTaskStateException.class,
                () -> runWith(context, FILE_READ, into, text("K001"), null, null, 0));
    }

    @Test
    @DisplayName("TOKENで同じfileの複数のrecordを持ち、合わないTOKENはINVREQ 47。KSDSのRBAは鍵の順に長さを足す")
    void tokensHoldSeveralRecords() {
        ProgramContext context = context(keyed());
        write(context, "K001aaaaaa");
        write(context, "K002bbbbbb");
        DataView into = text("          ");
        DataView first = fullword(0);
        DataView second = fullword(0);
        assertEquals(CicsResponseCode.NORMAL, runWith(context, FILE_READ, into, text("K001"), first, null, FILE_UPDATE));
        assertEquals(CicsResponseCode.NORMAL,
                runWith(context, FILE_READ, into, text("K002"), second, null, FILE_UPDATE));
        assertEquals(CicsResponseCode.INVREQ, runWith(context, FILE_REWRITE, text("K001AAAAAA"), null,
                fullword(intOf(second) + 100), null, 0));
        assertEquals(47, resp2());
        assertEquals(CicsResponseCode.NORMAL,
                runWith(context, FILE_REWRITE, text("K001AAAAAA"), null, first, null, 0));
        assertEquals(CicsResponseCode.NORMAL, runWith(context, FILE_UNLOCK, null, null, second, null, 0));
        assertEquals(CicsResponseCode.INVREQ, runWith(context, FILE_UNLOCK, null, null, second, null, 0));
        assertEquals(47, resp2());

        // browse の UPDATE は読んだ record を token で持ち、DELETE がその token で消す
        DataView cursor = text("K001");
        runWith(context, FILE_STARTBR, null, cursor, null, null, 0);
        DataView browsed = fullword(0);
        assertEquals(CicsResponseCode.NORMAL,
                runWith(context, FILE_READNEXT, into, cursor, browsed, null, FILE_UPDATE));
        assertEquals(CicsResponseCode.NORMAL, runWith(context, FILE_DELETE, null, null, browsed, null, 0));
        runWith(context, FILE_ENDBR, null, null, null, null, 0);

        assertEquals(CicsResponseCode.NORMAL, runWith(context, FILE_READ, into, fullword(0), null, null, FILE_RBA));
        assertEquals("K002bbbbbb", CP.decode(into.toByteArray()));
    }

    @Test
    @DisplayName("NOSUSPENDは他のtaskが持つrecordを待たずRECORDBUSY 107、CONSISTENTの読みは期限まで待つ")
    void noSuspendAndConsistentReads() {
        CicsFilePort files = keyed();
        ProgramContext owner = context(files, "task_owner");
        write(owner, "K001aaaaaa");
        write(owner, "K002bbbbbb");
        DataView into = text("          ");
        run(owner, FILE_READ, into, null, text("K001"), -1, -1, null, FILE_UPDATE);

        ProgramContext other = context(files, "task_other");
        execution.limitTo(Instant.now().plusMillis(200));
        assertEquals(CicsResponseCode.RECORDBUSY,
                runWith(other, FILE_READ, into, text("K001"), null, null, FILE_UPDATE | FILE_NOSUSPEND));
        assertEquals(107, resp2());
        assertEquals(CicsResponseCode.RECORDBUSY,
                runWith(other, FILE_READ, into, text("K001"), null, null, FILE_CONSISTENT | FILE_NOSUSPEND));
        assertThrows(CicsTaskStateException.class,
                () -> runWith(other, FILE_READ, into, text("K001"), null, null, FILE_CONSISTENT));
        assertEquals(CicsResponseCode.NORMAL, runWith(other, FILE_READ, into, text("K001"), null, null, 0));

        // 総称の DELETE は持たれていない record だけを消し、NUMREC に消した数を返す
        DataView numrec = halfword(0);
        CicsRuntimeOps.fileCommandCondition(other, FILE_DELETE, "CUSTFILE", null, null, null, -1, text("K00 "), null,
                3, null, -1, numrec, null, null, null, FILE_GENERIC | FILE_NOSUSPEND, true);
        assertEquals(CicsResponseCode.RECORDBUSY, resp());
        assertEquals(1, halfwordOf(numrec));
    }

    @Test
    @DisplayName("BDAMは相対block番号をRRDSの番号に写し、DEBRECは0だけ、DEBKEYは鍵が合うときだけ読む。DELETEはINVREQ 27")
    void bdamBlocks() {
        CicsFilePort files = CicsFilePort.dataSets(List.of(CicsFileDefinition.directAccess("CUSTFILE",
                directory.resolve("cust.bdam"), 0, 4, 10, false, CP)));
        ProgramContext context = context(files);
        assertEquals(CicsResponseCode.NORMAL, runWith(context, FILE_WRITE, text("K007record"), fullword(0), null,
                null, 0));
        assertEquals(CicsResponseCode.INVREQ, runWith(context, FILE_WRITE, text("K008record"), fullword(1), null,
                null, FILE_MASSINSERT));
        assertEquals(38, resp2());

        DataView into = text("          ");
        assertEquals(CicsResponseCode.NORMAL, runWith(context, FILE_READ, into, fullword(0), null, null, 0));
        assertEquals("K007record", CP.decode(into.toByteArray()));
        DataView debrec = Storage.copyOf(ByteBuffer.allocate(8).putInt(0).putInt(1).array()).whole();
        assertEquals(CicsResponseCode.NOTFND, runWith(context, FILE_READ, into, debrec, null, null, FILE_DEBREC));
        debrec.setBytes(new byte[8]);
        assertEquals(CicsResponseCode.NORMAL, runWith(context, FILE_READ, into, debrec, null, null, FILE_DEBREC));

        byte[] key = ByteBuffer.allocate(8).putInt(0).put(CP.encode("K007")).array();
        assertEquals(CicsResponseCode.NORMAL,
                runWith(context, FILE_READ, into, Storage.copyOf(key).whole(), null, null, FILE_DEBKEY));
        key[7] = CP.encode("9")[0];
        assertEquals(CicsResponseCode.NOTFND,
                runWith(context, FILE_READ, into, Storage.copyOf(key).whole(), null, null, FILE_DEBKEY));

        assertEquals(CicsResponseCode.INVREQ, runWith(context, FILE_DELETE, null, fullword(0), null, null, 0));
        assertEquals(27, resp2());
    }

    @Test
    @DisplayName("構成した自regionのSYSIDは書かないのと同じに処理し、ほかのSYSIDはSYSIDERR 130")
    void sysidOfThisRegion() {
        CicsFilePort files = keyed();
        execution = null;
        ProgramContext context = ProgramContext.standard().withCodePage(CP).withServices(RuntimeServices.builder()
                .service(CicsExecution.class, execution = new CicsExecution(new CicsTaskContext(
                        new CicsTaskId("task_sysid"), TransId.of("TX01"), "file-test", Instant.EPOCH), 0,
                        CicsEnvironment.unconfigured().withFiles(files).withLocalSystems(java.util.Set.of("HOME"))))
                .build());
        DataView into = text("          ");
        assertEquals(CicsResponseCode.NOTFND, runWith(context, FILE_READ, into, text("K001"), null, "HOME", 0));
        assertEquals(CicsResponseCode.SYSIDERR, runWith(context, FILE_READ, into, text("K001"), null, "AWAY", 0));
        assertEquals(130, resp2());
        CicsRuntimeOps.fileCommandCondition(context, FILE_UNLOCK, "CUSTFILE", null, null, null, -1, null, null, -1,
                null, -1, null, null, null, CP.encode("AWAY"), 0, true);
        assertEquals(CicsResponseCode.SYSIDERR, resp());
    }

    @Test
    @DisplayName("定義が許さない更新・削除・browseはINVREQ 20")
    void honoursDefinitionServices() {
        CicsFilePort files = CicsFilePort.dataSets(List.of(
                new CicsFileDefinition("CUSTFILE", directory.resolve("cust.ksds"), 0, 4, 10, CP)
                        .withServices(EnumSet.of(CicsFileDefinition.Service.READ))));
        ProgramContext context = context(files);
        DataView into = text("          ");
        assertEquals(CicsResponseCode.NOTFND, run(context, FILE_READ, into, null, text("K001"), -1, -1, null, 0));
        for (int kind : new int[] {FILE_READ, FILE_DELETE, FILE_STARTBR}) {
            run(context, kind, kind == FILE_READ ? into : null, null, text("K001"), -1, -1, null,
                    kind == FILE_READ ? FILE_UPDATE : 0);
            assertEquals(CicsResponseCode.INVREQ, resp());
            assertEquals(20, resp2());
        }
    }
}
