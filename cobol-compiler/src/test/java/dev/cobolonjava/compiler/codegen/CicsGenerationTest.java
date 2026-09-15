package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.CicsAbend;
import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskContext;
import dev.cobolonjava.cics.CicsTaskId;
import dev.cobolonjava.cics.CicsTaskStateException;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CobolCicsTaskProgram;
import dev.cobolonjava.cics.SyncpointAction;
import dev.cobolonjava.cics.TaskCompletion;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** EXEC CICS island parser、生成コード、task program portを通した結合試験。 */
@Tag("V1")
class CicsGenerationTest {

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(CicsGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    @Test
    @DisplayName("生成COBOLのLINK・XCTL・SYNCPOINT・RETURNを同じtaskで実行する")
    void executesGeneratedCicsCommands() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> main = compile(loader, "MAIN", List.of(
                "EXEC CICS LINK PROGRAM('CHILD') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "EXEC CICS XCTL PROGRAM('NEXTPGM') COMMAREA(LK-AREA) LENGTH(4) END-EXEC"));
        Supplier<CobolProgram> child = compile(loader, "CHILD", List.of(
                "MOVE 'LINK' TO LK-AREA",
                "GOBACK"));
        Supplier<CobolProgram> next = compile(loader, "NEXTPGM", List.of(
                "MOVE 'DONE' TO LK-AREA",
                "EXEC CICS SYNCPOINT END-EXEC",
                "EXEC CICS RETURN TRANSID('NXT1') COMMAREA(LK-AREA) LENGTH(4) END-EXEC"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .revision("generated-cics-r1")
                .cobolProgram("MAIN", main)
                .cobolProgram("CHILD", child)
                .cobolProgram("NEXTPGM", next)
                .build();
        List<SyncpointAction> syncpoints = new ArrayList<>();

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 8)
                .execute(definition(), CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> syncpoints.add(action));

        assertEquals(List.of(SyncpointAction.COMMIT), syncpoints);
        assertEquals(Optional.of(TransId.of("NXT1")), result.nextTransaction());
        assertEquals("DONE", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("PROGRAM(データ名)のLINK・XCTLは実行時の値から名前を決め、未登録はPGMIDERR")
    void linksAndTransfersToProgramNamedByDataArea() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> main = compile(loader, "MAIN", List.of(
                "MOVE 'NOPE' TO WS-PGM",
                "EXEC CICS LINK PROGRAM(WS-PGM) RESP(WS-RESP) END-EXEC",
                "IF WS-RESP NOT = 27 GOBACK END-IF",
                "MOVE 'CHILD' TO WS-PGM",
                "EXEC CICS LINK PROGRAM(WS-PGM) COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "MOVE 'NEXTPGM' TO WS-PGM",
                "EXEC CICS XCTL PROGRAM(WS-PGM) COMMAREA(LK-AREA) LENGTH(4) END-EXEC"));
        Supplier<CobolProgram> child = compile(loader, "CHILD", List.of(
                "MOVE 'LINK' TO LK-AREA",
                "GOBACK"));
        Supplier<CobolProgram> next = compile(loader, "NEXTPGM", List.of(
                "IF LK-AREA = 'LINK' MOVE 'DONE' TO LK-AREA END-IF",
                "EXEC CICS RETURN TRANSID('NXT1') COMMAREA(LK-AREA) LENGTH(4) END-EXEC"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("MAIN", main)
                .cobolProgram("CHILD", child)
                .cobolProgram("NEXTPGM", next)
                .build();

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 8)
                .execute(definition(), CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> { });

        assertEquals("DONE", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("LENGTHを省いたCOMMAREAはデータ項目の長さで渡し、SYNCONRETURNはlocal LINKで効果を持たない")
    void omittedLengthUsesTheCommareaItemLength() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> main = compile(loader, "MAIN", List.of(
                "EXEC CICS LINK PROGRAM('CHILD') COMMAREA(LK-AREA) SYNCONRETURN END-EXEC",
                "EXEC CICS RETURN TRANSID('NXT1') COMMAREA(LK-AREA) END-EXEC"));
        Supplier<CobolProgram> child = compile(loader, "CHILD", List.of(
                "IF EIBCALEN = 4 MOVE 'FULL' TO LK-AREA END-IF",
                "GOBACK"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("MAIN", main)
                .cobolProgram("CHILD", child)
                .build();
        List<SyncpointAction> syncpoints = new ArrayList<>();

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 8)
                .execute(definition(), CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> syncpoints.add(action));

        assertEquals("FULL", CodePages.DEFAULT.decode(result.payload().commarea()));
        assertEquals(List.of(), syncpoints);
    }

    @Test
    @DisplayName("EXEC CICSのoptionには修飾したデータ名を書け、修飾が合わなければ断る")
    void acceptsQualifiedOptionNamesAndDfhbmsca() {
        CobolCompiler.Result result = compileResult("QUALNM", List.of(
                "EXEC CICS FORMATTIME ABSTIME(WS-ABS) DDMMYYYY(WS-DATE) TIME(WS-QTIME OF WS-GRP)"
                        + " DATESEP END-EXEC"));
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        assertRejected("EXEC CICS FORMATTIME ABSTIME(WS-ABS) TIME(WS-QTIME OF WS-RESP) END-EXEC",
                "no WS-QTIME is contained in WS-RESP");
        CobolCompiler.Result lengthOf = compileResult("LENOF", List.of(
                "EXEC CICS RETURN TRANSID('TX01') COMMAREA(WS-PGM) LENGTH(LENGTH OF WS-PGM) END-EXEC"));
        assertTrue(lengthOf.succeeded(), () -> "unexpected diagnostics: " + lengthOf.diagnostics());
        assertRejected("EXEC CICS RETURN TRANSID('TX01') COMMAREA(WS-PGM) LENGTH(LENGTH OF WS-APPL) END-EXEC",
                "supported only when x is the COMMAREA data area");
    }

    @Test
    @DisplayName("DFHVALUEはCICS TSのCVDAの数になり、INQUIRE / SET TERMINAL UCTRANSTを翻訳できる")
    void translatesDfhvalueAndTerminalUctranst() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "CVDAS", List.of(
                "MOVE DFHVALUE(NOUCTRAN) TO WS-RESP",
                "IF WS-RESP = 452 MOVE 'N' TO LK-AREA(1:1) END-IF",
                "IF DFHVALUE(UCTRAN) = 451 AND DFHVALUE(TRANIDONLY) = 460 MOVE 'U' TO LK-AREA(2:1) END-IF"));

        assertEquals("NUIT", CodePages.DEFAULT.decode(execute(loader, "CVDAS", program).payload().commarea()));
        CobolCompiler.Result terminal = compileResult("TERMUC", List.of(
                "EXEC CICS INQUIRE TERMINAL(EIBTRMID) UCTRANST(WS-RESP) RESP(WS-RESP2) END-EXEC",
                "EXEC CICS SET TERMINAL(EIBTRMID) UCTRANST(WS-RESP) END-EXEC"));
        assertTrue(terminal.succeeded(), () -> "unexpected diagnostics: " + terminal.diagnostics());
        assertRejected("MOVE DFHVALUE(ACQUIRED) TO WS-RESP", "unsupported CVDA name: ACQUIRED");
        assertRejected("EXEC CICS INQUIRE TERMINAL(EIBTRMID) UCTRANST(WS-DATE) END-EXEC",
                "UCTRANST must be a 4-byte binary integer");
        assertRejected("EXEC CICS INQUIRE TERMINAL(EIBTRMID) ACQSTATUS(WS-RESP) END-EXEC",
                "unsupported INQUIRE TERMINAL option: ACQSTATUS");
        CobolCompiler.Result asis = compileResult("RECVAS", List.of(
                "EXEC CICS RECEIVE MAP('M1') MAPSET('MS1') INTO(WS-DATE) TERMINAL ASIS RESP(WS-RESP) END-EXEC"));
        assertTrue(asis.succeeded(), () -> "unexpected diagnostics: " + asis.diagnostics());
        assertRejected("EXEC CICS RECEIVE MAP('M1') INTO(WS-DATE) TERMINAL(WS-PGM) END-EXEC",
                "TERMINAL does not take a value");
    }

    @Test
    @DisplayName("INQUIRE ASSOCIATIONはtask自身のEIBTASKNに限って翻訳し、ほかのtaskと未対応のoptionは断る")
    void translatesInquireAssociationForTheOwnTask() {
        CobolCompiler.Result result = compileResult("ASSOC", List.of(
                "EXEC CICS INQUIRE ASSOCIATION(EIBTASKN) ODAPPLID(WS-APPL) ODUSERID(WS-PGM)"
                        + " ODFACILNAME(WS-APPL) ODNETWORKID(WS-PGM) ODFACILTYPE(WS-RESP) END-EXEC"));
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        assertRejected("EXEC CICS INQUIRE ASSOCIATION(WS-RESP) ODAPPLID(WS-APPL) END-EXEC",
                "supported only for ASSOCIATION(EIBTASKN)");
        assertRejected("EXEC CICS INQUIRE ASSOCIATION(EIBTASKN) ODTRANSID(WS-ABCODE) END-EXEC",
                "unsupported INQUIRE ASSOCIATION option: ODTRANSID");
        assertRejected("EXEC CICS INQUIRE ASSOCIATION(EIBTASKN) ODAPPLID(WS-ABCODE) END-EXEC",
                "origin data area must be an 8-byte alphanumeric item");
    }

    @Test
    @DisplayName("WRITE FILEは定義の無いfileでFILENOTFOUNDをRESPに返し、file control以外のWRITEは断る")
    void writesFileAndReportsFileNotFound() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "WRFILE", List.of(
                "MOVE '000042' TO WS-QTIME",
                "EXEC CICS WRITE FILE('ABNDFILE') FROM(WS-GRP) RIDFLD(WS-GRP) RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(FILENOTFOUND) MOVE 'F' TO LK-AREA(1:1) END-IF"));

        assertEquals("FNIT", CodePages.DEFAULT.decode(execute(loader, "WRFILE", program).payload().commarea()));
        assertRejected("EXEC CICS WRITE FILE('ABNDFILE') FROM(WS-GRP) RIDFLD(WS-GRP) MASSINSERT END-EXEC",
                "unsupported WRITE FILE option: MASSINSERT");
        assertRejected("EXEC CICS WRITE FILE('ABNDFILE') FROM(WS-GRP) RIDFLD(WS-GRP) LENGTH(7) END-EXEC",
                "WRITE FILE LENGTH 7 exceeds the FROM data area of 6 bytes");
        assertRejected("EXEC CICS WRITE OPERATOR TEXT(WS-DATE) END-EXEC",
                "WRITE requires FILE");
    }

    @Test
    @DisplayName("生成COBOLがKSDSへ書き、browseし、READ UPDATEとREWRITEで書き換える")
    void runsFileControlAgainstDataSet(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "FILEPGM", List.of(
                "MOVE '000042' TO WS-QTIME",
                "MOVE '0000' TO WS-ABCODE",
                "EXEC CICS WRITE FILE('CUSTFILE') FROM(WS-GRP) RIDFLD(WS-ABCODE) END-EXEC",
                "MOVE '000177' TO WS-QTIME",
                "MOVE '0001' TO WS-ABCODE",
                "EXEC CICS WRITE FILE('CUSTFILE') FROM(WS-GRP) RIDFLD(WS-ABCODE) END-EXEC",
                "MOVE LOW-VALUES TO WS-ABCODE",
                "EXEC CICS STARTBR FILE('CUSTFILE') RIDFLD(WS-ABCODE) GTEQ END-EXEC",
                "EXEC CICS READNEXT FILE('CUSTFILE') INTO(WS-GRP) RIDFLD(WS-ABCODE) END-EXEC",
                "IF WS-ABCODE = '0000' MOVE 'A' TO LK-AREA(1:1) END-IF",
                "MOVE 6 TO WS-LEN",
                "EXEC CICS READNEXT FILE('CUSTFILE') INTO(WS-GRP) LENGTH(WS-LEN) RIDFLD(WS-ABCODE) END-EXEC",
                "IF WS-QTIME = 177 AND WS-LEN = 6 MOVE 'B' TO LK-AREA(2:1) END-IF",
                "EXEC CICS READNEXT FILE('CUSTFILE') INTO(WS-GRP) RIDFLD(WS-ABCODE) RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(ENDFILE) MOVE 'C' TO LK-AREA(3:1) END-IF",
                "EXEC CICS ENDBR FILE('CUSTFILE') END-EXEC",
                "EXEC CICS READ FILE('CUSTFILE') INTO(WS-GRP) RIDFLD(WS-ABCODE) UPDATE END-EXEC",
                "MOVE '000199' TO WS-QTIME",
                "EXEC CICS REWRITE FILE('CUSTFILE') FROM(WS-GRP) END-EXEC",
                "MOVE ZERO TO WS-QTIME",
                "EXEC CICS READ FILE('CUSTFILE') INTO(WS-GRP) RIDFLD(WS-ABCODE) END-EXEC",
                "IF WS-QTIME = 199 MOVE 'D' TO LK-AREA(4:1) END-IF"));
        ProgramCatalog catalog = ProgramCatalog.builder().cobolProgram("FILEPGM", program).build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("FILEPGM"), Duration.ofSeconds(5), 16, 0, 0, 0, true);
        dev.cobolonjava.cics.CicsEnvironment environment = dev.cobolonjava.cics.CicsEnvironment.unconfigured()
                .withFiles(dev.cobolonjava.cics.CicsFilePort.dataSets(List.of(new dev.cobolonjava.cics.CicsFileDefinition(
                        "CUSTFILE", directory.resolve("cust.ksds"), 0, 4, 6, CodePages.DEFAULT))));

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2, environment)
                .execute(definition, CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> { });

        assertEquals("ABCD", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("生成COBOLが一時記憶と一時データのキューへ書いて読み、ITEMERRとQZEROを受け取る")
    void runsTemporaryStorageAndTransientDataQueues() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "QUEUEPGM", List.of(
                "MOVE '000042' TO WS-QTIME",
                "EXEC CICS WRITEQ TS QUEUE('SCRATCH') FROM(WS-GRP) ITEM(WS-LEN) END-EXEC",
                "IF WS-LEN = 1 MOVE 'W' TO LK-AREA(1:1) END-IF",
                "MOVE ZERO TO WS-QTIME",
                "EXEC CICS READQ TS QUEUE('SCRATCH') INTO(WS-GRP) NEXT END-EXEC",
                "IF WS-QTIME = 42 MOVE 'R' TO LK-AREA(2:1) END-IF",
                "EXEC CICS READQ TS QUEUE('SCRATCH') INTO(WS-GRP) NEXT RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(ITEMERR) MOVE 'E' TO LK-AREA(3:1) END-IF",
                "EXEC CICS WRITEQ TD QUEUE('CSMT') FROM(WS-GRP) END-EXEC",
                "EXEC CICS READQ TD QUEUE('CSMT') INTO(WS-GRP) END-EXEC",
                "EXEC CICS READQ TD QUEUE('CSMT') INTO(WS-GRP) RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(QZERO) MOVE 'Z' TO LK-AREA(4:1) END-IF",
                "EXEC CICS DELETEQ TS QUEUE('SCRATCH') END-EXEC"));
        ProgramCatalog catalog = ProgramCatalog.builder().cobolProgram("QUEUEPGM", program).build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("QUEUEPGM"), Duration.ofSeconds(5), 16, 0, 0, 0, true);
        dev.cobolonjava.cics.CicsEnvironment environment = dev.cobolonjava.cics.CicsEnvironment.unconfigured()
                .withTransientData(dev.cobolonjava.cics.CicsTransientDataPort.inMemory(List.of(
                        new dev.cobolonjava.cics.CicsTransientDataQueueDefinition("CSMT", 6))));

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2, environment)
                .execute(definition, CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> { });

        assertEquals("WREZ", CodePages.DEFAULT.decode(result.payload().commarea()));
        assertRejected("EXEC CICS READQ TS QUEUE('Q') INTO(WS-GRP) END-EXEC", "requires either ITEM or NEXT");
        assertRejected("EXEC CICS WRITEQ TS QUEUE('Q') FROM(WS-GRP) REWRITE END-EXEC", "REWRITE requires ITEM");
        assertRejected("EXEC CICS WRITEQ TS QUEUE('Q') QNAME('Q') FROM(WS-GRP) END-EXEC",
                "QUEUE and QNAME are mutually exclusive");
        assertRejected("EXEC CICS WRITEQ TS QUEUE('Q') FROM(WS-GRP) SYSID('S1') END-EXEC",
                "unsupported WRITEQ TS option: SYSID");
        assertRejected("EXEC CICS READQ TD QUEUE('TOOLONG') INTO(WS-GRP) END-EXEC", "name must be 1 to 4");
        assertRejected("EXEC CICS WRITEQ TS QUEUE(WS-ABCODE) FROM(WS-GRP) END-EXEC",
                "queue name data area must be a 8-byte alphanumeric item");
    }

    @Test
    @DisplayName("生成COBOLがRUN TRANSIDで子を起こし、FETCH ANYのCOMPSTATUSとreply channelのcontainerを読む")
    void runsAsynchronousChildren() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "ASYNCPGM", List.of(
                "MOVE 'CIPCREDCHANN' TO WS-CHAN",
                "MOVE 'CIPA' TO WS-CONT",
                "EXEC CICS PUT CONTAINER(WS-CONT) CHANNEL(WS-CHAN) FROM(WS-GRP) FLENGTH(6) END-EXEC",
                "EXEC CICS RUN TRANSID('TX03') CHANNEL(WS-CHAN) CHILD(WS-CHILD) RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(NORMAL) MOVE 'R' TO LK-AREA(1:1) END-IF",
                "EXEC CICS FETCH ANY(WS-CHILD) CHANNEL(WS-FETCHCH) COMPSTATUS(WS-COMPST) ABCODE(WS-ABCODE)"
                        + " TIMEOUT(5000) RESP(WS-RESP) END-EXEC",
                "EVALUATE WS-COMPST WHEN DFHVALUE(ABEND) MOVE 'A' TO LK-AREA(2:1)"
                        + " WHEN DFHVALUE(NORMAL) MOVE 'F' TO LK-AREA(2:1) END-EVALUATE",
                "MOVE ZERO TO WS-QTIME",
                "EXEC CICS GET CONTAINER('REPLY') CHANNEL(WS-FETCHCH) INTO(WS-GRP) RESP(WS-RESP) END-EXEC",
                "IF WS-QTIME = 99 MOVE 'G' TO LK-AREA(3:1) END-IF",
                "EXEC CICS FETCH ANY(WS-CHILD) NOSUSPEND RESP(WS-RESP) RESP2(WS-RESP2) END-EXEC",
                "IF WS-RESP = DFHRESP(NOTFND) MOVE 'N' TO LK-AREA(4:1) END-IF"));
        dev.cobolonjava.cics.CicsTransactionRegistry registry = new dev.cobolonjava.cics.CicsTransactionRegistry(
                List.of(new CicsTransactionDefinition(TransId.of("TX03"), ProgramId.of("CHILD"),
                        Duration.ofSeconds(5), 0, 4, 64, 256, true)));
        dev.cobolonjava.cics.CicsEnvironment environment = dev.cobolonjava.cics.CicsEnvironment.unconfigured()
                .withAsync(dev.cobolonjava.cics.CicsAsyncPort.inMemory(registry, child -> {
                    Map<String, byte[]> reply = new java.util.LinkedHashMap<>(child.containers());
                    reply.put("REPLY", CodePages.DEFAULT.encode("000099"));
                    return new CicsPayload(new byte[0], reply, child.channelName().orElse(null));
                }));
        ProgramCatalog catalog = ProgramCatalog.builder().cobolProgram("ASYNCPGM", program).build();

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2, environment)
                .execute(new CicsTransactionDefinition(TransId.of("TX01"), ProgramId.of("ASYNCPGM"),
                                Duration.ofSeconds(5), 16, 0, 0, 0, true),
                        CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")), task(), (action, ignored) -> { });

        assertEquals("RFGN", CodePages.DEFAULT.decode(result.payload().commarea()));
        assertRejected("EXEC CICS RUN TRANSID('TX03') CHILD(WS-CHILD) USERID('U1') END-EXEC",
                "unsupported RUN TRANSID option: USERID");
        assertRejected("EXEC CICS RUN TRANSID('TX03') END-EXEC", "RUN TRANSID requires CHILD");
        assertRejected("EXEC CICS FETCH ANY(WS-CHILD) NOSUSPEND TIMEOUT(10) END-EXEC", "mutually exclusive");
        assertRejected("EXEC CICS FETCH ANY(WS-ABCODE) END-EXEC", "ANY data area must be a 16-byte alphanumeric item");
        assertRejected("EXEC CICS FETCH CHILD(WS-CHILD) COMPSTATUS(WS-LEN) END-EXEC",
                "COMPSTATUS must be a fullword binary data area");
    }

    @Test
    @DisplayName("PROTECTのSTARTはtaskの終わりのcommitまで登録せず、commitのあとに始まる")
    void protectedStartWaitsForTaskCommit() throws InterruptedException {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> issuer = compile(loader, "PROTPGM", List.of(
                "EXEC CICS START TRANSID('TX02') PROTECT REQID('PROT1') END-EXEC",
                "EXEC CICS START TRANSID('TX02') PROTECT REQID('PROT2') END-EXEC",
                "EXEC CICS CANCEL REQID('PROT2') RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(NORMAL) MOVE 'C' TO LK-AREA(1:1) END-IF"));
        java.util.concurrent.BlockingQueue<dev.cobolonjava.cics.CicsStartData> launched =
                new java.util.concurrent.LinkedBlockingQueue<>();
        dev.cobolonjava.cics.CicsEnvironment environment = dev.cobolonjava.cics.CicsEnvironment.unconfigured()
                .withStarts(dev.cobolonjava.cics.CicsStartPort.inMemory(java.time.Clock.systemUTC(),
                        id -> id.value().equals("TX02"), launched::add));
        ProgramCatalog catalog = ProgramCatalog.builder().cobolProgram("PROTPGM", issuer).build();

        TaskCompletion completion = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2, environment)
                .execute(new CicsTransactionDefinition(TransId.of("TX01"), ProgramId.of("PROTPGM"),
                                Duration.ofSeconds(5), 16, 0, 0, 0, true),
                        CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")), task(), (action, ignored) -> { });

        assertEquals("CNIT", CodePages.DEFAULT.decode(completion.payload().commarea()));
        assertEquals(null, launched.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS));
        assertEquals(1, completion.afterCommit().size());
        // coordinator が暗黙の同期点を commit したあとに行う
        completion.afterCommit().forEach(Runnable::run);
        assertEquals("PROT1", launched.poll(5, java.util.concurrent.TimeUnit.SECONDS).requestId());
    }

    @Test
    @DisplayName("生成COBOLのSTART TERMIDは定数とデータ名の端末をportへ渡し、DFHRESP(TERMIDERR)で判定できる")
    void startsTasksOnTerminals() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> issuer = compile(loader, "TERMPGM", List.of(
                "MOVE 'W001' TO WS-ABCODE",
                "EXEC CICS START TRANSID('TX02') TERMID(WS-ABCODE) REQID('TERM1') RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(NORMAL) MOVE 'S' TO LK-AREA(1:1) END-IF",
                "EXEC CICS START TRANSID('TX02') TERMID('ZZZZ') RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(TERMIDERR) MOVE 'T' TO LK-AREA(2:1) END-IF"));
        List<dev.cobolonjava.cics.CicsStartData> captured = new java.util.ArrayList<>();
        dev.cobolonjava.cics.CicsStartPort port = new dev.cobolonjava.cics.CicsStartPort() {
            @Override
            public Result start(Instant expiration, dev.cobolonjava.cics.CicsStartData data) {
                if (data.terminalId().equals(Optional.of("ZZZZ"))) {
                    return new Result(dev.cobolonjava.cics.CicsResponseCode.TERMIDERR, 0);
                }
                captured.add(data);
                return new Result(dev.cobolonjava.cics.CicsResponseCode.NORMAL, 0);
            }

            @Override
            public Result cancel(String requestId) {
                return new Result(dev.cobolonjava.cics.CicsResponseCode.NOTFND, 0);
            }

            @Override
            public String newRequestId() {
                return "GEN00001";
            }
        };
        ProgramCatalog catalog = ProgramCatalog.builder().cobolProgram("TERMPGM", issuer).build();

        TaskCompletion completion = new CobolCicsTaskProgram(CobolRuntime.builder(catalog).classLoader(loader).build(),
                2, dev.cobolonjava.cics.CicsEnvironment.unconfigured().withStarts(port))
                .execute(new CicsTransactionDefinition(TransId.of("TX01"), ProgramId.of("TERMPGM"),
                                Duration.ofSeconds(5), 16, 0, 0, 0, true),
                        CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")), task(), (action, ignored) -> { });

        assertEquals("STIT", CodePages.DEFAULT.decode(completion.payload().commarea()));
        assertEquals(1, captured.size());
        assertEquals(Optional.of("W001"), captured.get(0).terminalId());
        assertEquals("TERM1", captured.get(0).requestId());
    }

    @Test
    @DisplayName("生成COBOLのSTART USERIDは代理の権限を確かめてそのuser IDで起こし、権限が無ければDFHRESP(NOTAUTH)")
    void startsTasksWithUserIds() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> issuer = compile(loader, "USERPGM", List.of(
                "EXEC CICS START TRANSID('TX02') USERID('BATCH01') REQID('USER1') RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(NORMAL) MOVE 'S' TO LK-AREA(1:1) END-IF",
                "EXEC CICS START TRANSID('TX02') USERID('INTRUDER') REQID('USER2') RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(NOTAUTH) MOVE 'N' TO LK-AREA(2:1) END-IF"));
        List<dev.cobolonjava.cics.CicsStartData> captured = new java.util.ArrayList<>();
        dev.cobolonjava.cics.CicsStartPort port = new dev.cobolonjava.cics.CicsStartPort() {
            @Override
            public Result start(Instant expiration, dev.cobolonjava.cics.CicsStartData data) {
                captured.add(data);
                return new Result(dev.cobolonjava.cics.CicsResponseCode.NORMAL, 0);
            }

            @Override
            public Result cancel(String requestId) {
                return new Result(dev.cobolonjava.cics.CicsResponseCode.NOTFND, 0);
            }

            @Override
            public String newRequestId() {
                return "GEN00001";
            }
        };
        dev.cobolonjava.cics.CicsSecurityPort security = new dev.cobolonjava.cics.CicsSecurityPort() {
            @Override
            public Optional<String> userIdOf(String principal) {
                return Optional.empty();
            }

            @Override
            public boolean mayAttach(Optional<String> userId, TransId transaction) {
                return true;
            }

            @Override
            public boolean maySurrogate(Optional<String> userId, String surrogateUserId) {
                return surrogateUserId.equals("BATCH01");
            }
        };
        ProgramCatalog catalog = ProgramCatalog.builder().cobolProgram("USERPGM", issuer).build();

        TaskCompletion completion = new CobolCicsTaskProgram(CobolRuntime.builder(catalog).classLoader(loader).build(),
                2, dev.cobolonjava.cics.CicsEnvironment.unconfigured().withStarts(port).withSecurity(security))
                .execute(new CicsTransactionDefinition(TransId.of("TX01"), ProgramId.of("USERPGM"),
                                Duration.ofSeconds(5), 16, 0, 0, 0, true),
                        CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")), task(), (action, ignored) -> { });

        assertEquals("SNIT", CodePages.DEFAULT.decode(completion.payload().commarea()));
        assertEquals(1, captured.size());
        assertEquals(Optional.of("BATCH01"), captured.get(0).userId());
    }

    @Test
    @DisplayName("生成COBOLのRETRIEVE WAITは、端末へ出すSTARTでまとめた次のデータを読み、読み尽くしたらportに次を求める")
    void retrieveWaitReadsLaterStarts() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> started = compile(loader, "WAITPGM", List.of(
                "MOVE ZERO TO WS-QTIME",
                "EXEC CICS RETRIEVE INTO(WS-GRP) WAIT RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(NORMAL) AND WS-QTIME = 42 MOVE 'W' TO LK-AREA(1:1) END-IF",
                "EXEC CICS RETRIEVE INTO(WS-GRP) WAIT RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(NORMAL) AND WS-QTIME = 43 MOVE 'X' TO LK-AREA(2:1) END-IF"));
        dev.cobolonjava.cics.CicsStartData first = new dev.cobolonjava.cics.CicsStartData("W1", TransId.of("TX02"),
                CodePages.DEFAULT.encode("000042"), Optional.empty(), Optional.empty(), Optional.empty(),
                "compiler-test", Optional.empty(), Optional.of("W001"));
        dev.cobolonjava.cics.CicsStartData later = new dev.cobolonjava.cics.CicsStartData("W2", TransId.of("TX02"),
                CodePages.DEFAULT.encode("000043"), Optional.empty(), Optional.empty(), Optional.empty(),
                "compiler-test", Optional.empty(), Optional.of("W001"));
        dev.cobolonjava.cics.CicsStartPort port = new dev.cobolonjava.cics.CicsStartPort() {
            @Override
            public Result start(Instant expiration, dev.cobolonjava.cics.CicsStartData data) {
                return new Result(dev.cobolonjava.cics.CicsResponseCode.NORMAL, 0);
            }

            @Override
            public Result cancel(String requestId) {
                return new Result(dev.cobolonjava.cics.CicsResponseCode.NOTFND, 0);
            }

            @Override
            public String newRequestId() {
                return "GEN00001";
            }

            @Override
            public List<dev.cobolonjava.cics.CicsStartData> retrieveMore(dev.cobolonjava.cics.CicsStartData task) {
                return List.of(later);
            }
        };
        ProgramCatalog catalog = ProgramCatalog.builder().cobolProgram("WAITPGM", started).build();

        TaskCompletion completion = new CobolCicsTaskProgram(CobolRuntime.builder(catalog).classLoader(loader).build(),
                2, dev.cobolonjava.cics.CicsEnvironment.unconfigured().withStarts(port))
                .execute(new CicsTransactionDefinition(TransId.of("TX02"), ProgramId.of("WAITPGM"),
                                Duration.ofSeconds(5), 16, 0, 0, 0, true),
                        CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        new CicsTaskContext(new CicsTaskId("task_wait"), TransId.of("TX02"), "compiler-test",
                                Instant.parse("2026-09-10T03:00:00Z")).withStart(Optional.of(first)),
                        (action, ignored) -> { });

        assertEquals("WXIT", CodePages.DEFAULT.decode(completion.payload().commarea()));
    }

    @Test
    @DisplayName("生成COBOLがSTARTとCANCELを出し、起こされたtaskのRETRIEVEがFROMとRTRANSIDを読む")
    void runsStartRetrieveAndCancel() throws InterruptedException {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> issuer = compile(loader, "STARTPGM", List.of(
                "MOVE '000042' TO WS-QTIME",
                "EXEC CICS START TRANSID('TX02') FROM(WS-GRP) REQID('REQ1') RTRANSID('TX01') RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(NORMAL) MOVE 'S' TO LK-AREA(1:1) END-IF",
                "EXEC CICS START TRANSID('NOPE') RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(TRANSIDERR) MOVE 'T' TO LK-AREA(2:1) END-IF",
                "EXEC CICS START TRANSID('TX02') AFTER MINUTES(30) REQID('LATER') END-EXEC",
                "EXEC CICS CANCEL REQID('LATER') RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(NORMAL) MOVE 'C' TO LK-AREA(3:1) END-IF",
                "EXEC CICS CANCEL REQID('LATER') RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(NOTFND) MOVE 'N' TO LK-AREA(4:1) END-IF"));
        Supplier<CobolProgram> started = compile(loader, "RETRPGM", List.of(
                "MOVE ZERO TO WS-QTIME",
                "MOVE 6 TO WS-LEN",
                "EXEC CICS RETRIEVE INTO(WS-GRP) LENGTH(WS-LEN) RTRANSID(WS-ABCODE) END-EXEC",
                "IF WS-QTIME = 42 AND WS-ABCODE = 'TX01' MOVE 'R' TO LK-AREA(1:1) END-IF",
                "EXEC CICS RETRIEVE INTO(WS-GRP) RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(ENDDATA) MOVE 'E' TO LK-AREA(2:1) END-IF"));
        java.util.concurrent.BlockingQueue<dev.cobolonjava.cics.CicsStartData> launched =
                new java.util.concurrent.LinkedBlockingQueue<>();
        dev.cobolonjava.cics.CicsEnvironment environment = dev.cobolonjava.cics.CicsEnvironment.unconfigured()
                .withStarts(dev.cobolonjava.cics.CicsStartPort.inMemory(java.time.Clock.systemUTC(),
                        id -> id.value().equals("TX02"), launched::add));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("STARTPGM", issuer).cobolProgram("RETRPGM", started).build();
        CobolCicsTaskProgram programs = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2, environment);

        TaskCompletion issued = programs.execute(new CicsTransactionDefinition(TransId.of("TX01"),
                        ProgramId.of("STARTPGM"), Duration.ofSeconds(5), 16, 0, 0, 0, true),
                CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")), task(), (action, ignored) -> { });
        assertEquals("STCN", CodePages.DEFAULT.decode(issued.payload().commarea()));

        dev.cobolonjava.cics.CicsStartData data = launched.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        TaskCompletion retrieved = programs.execute(new CicsTransactionDefinition(TransId.of("TX02"),
                        ProgramId.of("RETRPGM"), Duration.ofSeconds(5), 16, 0, 0, 0, true),
                CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                new CicsTaskContext(new CicsTaskId("task_started"), TransId.of("TX02"), "compiler-test",
                        Instant.parse("2026-09-10T03:00:00Z")).withStart(Optional.of(data)),
                (action, ignored) -> { });
        assertEquals("REIT", CodePages.DEFAULT.decode(retrieved.payload().commarea()));

        assertRejected("EXEC CICS START TRANSID('TX02') NOCHECK END-EXEC", "unsupported START option: NOCHECK");
        assertRejected("EXEC CICS START TRANSID('TX02') USERID('U1') TERMID('T001') END-EXEC",
                "START USERID with TERMID is not supported");
        assertRejected("EXEC CICS START TRANSID('TX02') TERMID(WS-PGM) END-EXEC",
                "TERMID data area must be a 4-byte alphanumeric item");
        assertRejected("EXEC CICS START INTERVAL(0) END-EXEC", "START requires TRANSID");
        assertRejected("EXEC CICS START TRANSID('TX02') INTERVAL(0) TIME(0) END-EXEC", "mutually exclusive");
        assertRejected("EXEC CICS START TRANSID('TX02') HOURS(1) END-EXEC", "require AFTER or AT");
        assertRejected("EXEC CICS CANCEL END-EXEC", "CANCEL requires REQID");
        assertRejected("EXEC CICS RETRIEVE SET(WS-GRP) END-EXEC", "unsupported RETRIEVE option: SET");
        assertRejected("EXEC CICS RETRIEVE INTO(WS-GRP) WAIT('X') END-EXEC", "WAIT");
        assertRejected("EXEC CICS RETRIEVE RTRANSID(WS-PGM) END-EXEC",
                "RTRANSID data area must be a 4-byte alphanumeric item");
    }

    @Test
    @DisplayName("file controlの命令は定義の無いfileでFILENOTFOUNDを返し、表せないoptionと形は名前をつけて断る")
    void rejectsUnsupportedFileControlForms() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "NOFILE", List.of(
                "EXEC CICS READ FILE('CUSTFILE') INTO(WS-GRP) RIDFLD(WS-ABCODE) RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(FILENOTFOUND) MOVE 'F' TO LK-AREA(1:1) END-IF",
                "EXEC CICS STARTBR FILE('CUSTFILE') RIDFLD(WS-ABCODE) REQID(WS-LEN) RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(FILENOTFOUND) MOVE 'S' TO LK-AREA(2:1) END-IF",
                "EXEC CICS DELETE FILE('CUSTFILE') RIDFLD(WS-ABCODE) KEYLENGTH(3) GENERIC NUMREC(WS-LEN)"
                        + " RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(FILENOTFOUND) MOVE 'D' TO LK-AREA(3:1) END-IF",
                "EXEC CICS UNLOCK FILE('CUSTFILE') NOHANDLE END-EXEC"));

        assertEquals("FSDT", CodePages.DEFAULT.decode(execute(loader, "NOFILE", program).payload().commarea()));
        assertRejected("EXEC CICS READ FILE('F') SET(WS-PGM) RIDFLD(WS-ABCODE) END-EXEC",
                "unsupported READ FILE option: SET");
        assertRejected("EXEC CICS READNEXT FILE('F') INTO(WS-GRP) RIDFLD(WS-ABCODE) UPDATE END-EXEC",
                "unsupported READNEXT FILE option: UPDATE");
        assertRejected("EXEC CICS READ FILE('F') INTO(WS-GRP) RIDFLD(WS-ABCODE) GTEQ EQUAL END-EXEC",
                "GTEQ and EQUAL are mutually exclusive");
        assertRejected("EXEC CICS READ FILE('F') INTO(WS-GRP) RIDFLD(WS-ABCODE) GENERIC END-EXEC",
                "GENERIC requires KEYLENGTH");
        assertRejected("EXEC CICS READ FILE('F') INTO(WS-GRP) RIDFLD(WS-ABCODE) LENGTH(6) END-EXEC",
                "READ FILE LENGTH requires a data name");
        assertRejected("EXEC CICS READ FILE('F') INTO(WS-GRP) RIDFLD(WS-ABCODE) LENGTH(WS-RESP) END-EXEC",
                "LENGTH must be a halfword binary data area");
        assertRejected("EXEC CICS READ FILE('F') INTO(WS-GRP) RIDFLD(WS-SHORT) RRN END-EXEC",
                "RRN requires a 4-byte RIDFLD");
        assertRejected("EXEC CICS DELETE FILE('F') NUMREC(WS-LEN) END-EXEC",
                "without RIDFLD takes no KEYLENGTH");
        assertRejected("EXEC CICS STARTBR FILE('F') END-EXEC", "STARTBR FILE requires RIDFLD");
    }

    @Test
    @DisplayName("ENQで資源を得てDEQで返し、LENGTHの無い形と域を越えるLENGTHは断る")
    void enqueuesAndDequeues() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "ENQDEQ", List.of(
                "MOVE 'NAMEDCOUNTER' TO WS-CHAN",
                "EXEC CICS ENQ RESOURCE(WS-CHAN) LENGTH(16) RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(NORMAL) MOVE 'E' TO LK-AREA(1:1) END-IF",
                "EXEC CICS ENQ RESOURCE(WS-CHAN) LENGTH(16) NOSUSPEND RESP(WS-RESP) END-EXEC",
                "EXEC CICS DEQ RESOURCE(WS-CHAN) LENGTH(16) RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(NORMAL) MOVE 'D' TO LK-AREA(2:1) END-IF"));

        TaskCompletion result = execute(loader, "ENQDEQ", program);

        assertEquals("EDIT", CodePages.DEFAULT.decode(result.payload().commarea()));
        assertRejected("EXEC CICS ENQ RESOURCE(WS-CHAN) END-EXEC", "ENQ requires LENGTH");
        assertRejected("EXEC CICS ENQ RESOURCE(WS-CHAN) LENGTH(17) END-EXEC",
                "ENQ LENGTH 17 exceeds the RESOURCE data area of 16 bytes");
        assertRejected("EXEC CICS DEQ RESOURCE(WS-CHAN) LENGTH(16) LUW END-EXEC",
                "unsupported DEQ option: LUW");
    }

    @Test
    @DisplayName("PUT CONTAINERで作ったchannelからGET CONTAINERで読み戻し、無いcontainerはRESPに返る")
    void putsAndGetsContainers() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "CONTNR", List.of(
                "MOVE 'CH1' TO WS-CHAN",
                "MOVE 'DATA' TO WS-CONT",
                "MOVE 'ABCD' TO WS-ABCODE",
                "MOVE 3 TO WS-FLEN",
                "EXEC CICS PUT CONTAINER('C1') CHANNEL(WS-CHAN) FROM (WS-ABCODE) FLENGTH(WS-FLEN) END-EXEC",
                "MOVE 4 TO WS-FLEN",
                "EXEC CICS GET CONTAINER('C1') CHANNEL(WS-CHAN) INTO(LK-AREA) FLENGTH(WS-FLEN)"
                        + " RESP(WS-RESP) END-EXEC",
                "IF WS-FLEN NOT = 3 GOBACK END-IF",
                "EXEC CICS GET CONTAINER(WS-CONT) CHANNEL('CH1') INTO(WS-SHORT) RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = DFHRESP(CONTAINERERR) MOVE 'E' TO LK-AREA(4:1) END-IF"));

        TaskCompletion result = execute(loader, "CONTNR", program);

        assertEquals("ABCE", CodePages.DEFAULT.decode(result.payload().commarea()));
        assertRejected("EXEC CICS GET CONTAINER('C1') INTO(WS-SHORT) FLENGTH(3) END-EXEC",
                "GET CONTAINER FLENGTH requires a data name");
        assertRejected("EXEC CICS PUT CONTAINER('C1') FROM(WS-SHORT) APPEND END-EXEC",
                "unsupported PUT CONTAINER option: APPEND");
        assertRejected("EXEC CICS PUT CONTAINER(WS-PGM) FROM(WS-SHORT) END-EXEC",
                "CONTAINER data area must be a 16-byte alphanumeric item");
    }

    @Test
    @DisplayName("ABEND ABCODE(データ名)は実行時の値をcodeにし、BIF DEEDITは数字を右へ詰める")
    void abendsWithDataAreaCodeAndDeedits() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "ABDATA", List.of(
                "MOVE '1,234.50' TO WS-DATE",
                "EXEC CICS BIF DEEDIT FIELD(WS-DATE) END-EXEC",
                "IF WS-DATE NOT = '0000123450' GOBACK END-IF",
                "MOVE 'B999' TO WS-ABCODE",
                "EXEC CICS ABEND ABCODE(WS-ABCODE) NODUMP END-EXEC"));

        CicsAbend failure = assertThrows(CicsAbend.class, () -> execute(loader, "ABDATA", program));

        assertEquals("B999", failure.code().value());
        assertRejected("EXEC CICS ABEND ABCODE(WS-PGM) END-EXEC",
                "ABCODE data area must be exactly 4 bytes");
        assertRejected("EXEC CICS ABEND ABCODE('B123') ABCODE(WS-ABCODE) END-EXEC",
                "duplicate ABCODE option");
        assertRejected("EXEC CICS BIF DEEDIT FIELD(WS-RESP) END-EXEC",
                "BIF DEEDIT FIELD must be an alphanumeric");
    }

    @Test
    @DisplayName("USINGを書かないCICS programはDFHCOMMAREAを暗黙の引数にし、COMMAREAの無いtaskも起動できる")
    void implicitDfhcommareaParameter() throws ReflectiveOperationException {
        String source = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. NOUSING.",
                "       DATA DIVISION.",
                "       LINKAGE SECTION.",
                "       01  DFHCOMMAREA PIC X(8).",
                "       PROCEDURE DIVISION.",
                "           IF EIBCALEN = 0",
                "               EXEC CICS RETURN END-EXEC",
                "           END-IF",
                "           MOVE 'DONE' TO DFHCOMMAREA(1:4)",
                "           EXEC CICS RETURN TRANSID('NXT1')",
                "                COMMAREA(DFHCOMMAREA) LENGTH(4)",
                "           END-EXEC.") + "\n";
        CobolCompiler.Result result = CobolCompiler.standard().compile("NOUSING.cbl", source);
        assertTrue(result.succeeded(), result.diagnostics().toString());
        GeneratedLoader loader = new GeneratedLoader();
        Class<?> type = loader.define(result.className(), result.classFile());
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("NOUSING", () -> {
                    try {
                        return (CobolProgram) type.getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                })
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("NOUSING"), Duration.ofSeconds(5), 16, 0, 0, 0, true);
        CobolCicsTaskProgram programs = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2);

        TaskCompletion withArea = programs.execute(definition,
                CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")), task(), (a, b) -> { });
        TaskCompletion withoutArea = programs.execute(definition, CicsPayload.empty(), task(),
                (a, b) -> { });

        assertEquals("DONE", CodePages.DEFAULT.decode(withArea.payload().commarea()));
        assertEquals(Optional.empty(), withoutArea.nextTransaction());
        assertEquals(0, withoutArea.payload().commareaLength());
    }

    @Test
    @DisplayName("PROGRAMのデータ域が名前として正しくなければPGMIDERRへ丸めず失敗する")
    void rejectsInvalidProgramNameInDataArea() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "BADNAME", List.of(
                "MOVE ' CHILD' TO WS-PGM",
                "EXEC CICS LINK PROGRAM(WS-PGM) RESP(WS-RESP) END-EXEC"));

        CicsTaskStateException failure = assertThrows(CicsTaskStateException.class,
                () -> execute(loader, "BADNAME", program));

        assertTrue(failure.getMessage().contains("valid program name"), failure.getMessage());
    }

    @Test
    @DisplayName("RETURN TRANSID IMMEDIATEは次taskを端末入力なしで始める指定をtask結果へ残す")
    void returnImmediateMarksCompletion() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> immediate = compile(loader, "RETIMM", List.of(
                "EXEC CICS RETURN TRANSID('OMEN') IMMEDIATE "
                        + "COMMAREA(LK-AREA) LENGTH(4) END-EXEC"));
        Supplier<CobolProgram> ordinary = compile(loader, "RETORD", List.of(
                "EXEC CICS RETURN TRANSID('OMEN') COMMAREA(LK-AREA) LENGTH(4) END-EXEC"));

        TaskCompletion marked = execute(loader, "RETIMM", immediate);
        TaskCompletion unmarked = execute(loader, "RETORD", ordinary);

        assertEquals(Optional.of(TransId.of("OMEN")), marked.nextTransaction());
        assertTrue(marked.immediate());
        assertFalse(unmarked.immediate());
        assertRejected("EXEC CICS RETURN IMMEDIATE END-EXEC", "RETURN IMMEDIATE requires TRANSID");
        assertRejected("EXEC CICS LINK PROGRAM('CHILD') IMMEDIATE END-EXEC",
                "IMMEDIATE is only supported by RETURN");
        assertThrows(IllegalArgumentException.class, () -> new dev.cobolonjava.cics.ReturnCommand(
                Optional.empty(), CicsPayload.empty(), true));
    }

    @Test
    @DisplayName("EIBTASKN・EIBDATE・EIBTIME・EIBAID・EIBCPOSN・EIBTRMIDを読み取り専用で参照する")
    void readsTaskAndTerminalEibFields() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "EIBEXT", List.of(
                "MOVE 'FAIL' TO LK-AREA",
                "IF EIBTASKN = 123 AND EIBDATE = 126253 AND EIBTIME = 130506 "
                        + "AND EIBAID = X'00' AND EIBCPOSN = 0 AND EIBTRMID = LOW-VALUES "
                        + "MOVE 'PASS' TO LK-AREA",
                "EXEC CICS RETURN TRANSID('NXT1') COMMAREA(LK-AREA) LENGTH(4) END-EXEC"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("EIBEXT", program)
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("EIBEXT"), Duration.ofSeconds(5),
                16, 0, 0, 0, true);
        CicsTaskContext numbered = new CicsTaskContext(
                new CicsTaskId("task_000000000005"), TransId.of("TX01"), "compiler-test",
                Instant.parse("2026-09-10T04:05:06Z"), java.util.OptionalInt.of(123),
                Optional.of(java.time.ZoneId.of("Asia/Tokyo")));

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2)
                .execute(definition, CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        numbered, (action, ignored) -> { });

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
        assertRejected("MOVE 1 TO EIBTASKN", "EIBTASKN is read-only");
        assertRejected("MOVE X'7D' TO EIBAID", "EIBAID is read-only");
    }

    @Test
    @DisplayName("初期対応外のCICSオプションと動的PROGRAMはfail-closedで拒否する")
    void rejectsUnsupportedOptionsAndDynamicTargets() {
        assertRejected("EXEC CICS LINK PROGRAM(WS-RESP) COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "PROGRAM data area must be alphanumeric");
        assertRejected("EXEC CICS LINK PROGRAM('CHILD') PROGRAM(WS-PGM) END-EXEC",
                "duplicate PROGRAM option");
        assertRejected("EXEC CICS RETURN PROGRAM(WS-PGM) END-EXEC",
                "RETURN does not accept PROGRAM");
        assertRejected("EXEC CICS XCTL PROGRAM('NEXTPGM') SYNCONRETURN END-EXEC",
                "SYNCONRETURN is only supported by LINK");
        assertRejected("EXEC CICS LINK PROGRAM('CHILD') LENGTH(4) END-EXEC",
                "LENGTH requires COMMAREA");
        assertRejected("EXEC CICS RETURN TRANSID('TOO-LONG') END-EXEC",
                "TRANSID must contain 1 to 4");
    }

    @Test
    @DisplayName("COMMAREAのLENGTHがデータ項目を越える場合は翻訳を拒否する")
    void rejectsCommareaLengthBeyondDataItem() {
        CobolCompiler.Result result = compileResult("BADLEN", List.of(
                "EXEC CICS RETURN TRANSID('NXT1') COMMAREA(LK-AREA) LENGTH(5) END-EXEC"));

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.message().contains("LENGTH exceeds COMMAREA")),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("生成COBOLのABENDはcode、CANCEL、NODUMPをtask異常へ写像する")
    void executesGeneratedCicsAbend() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "ABENDPGM", List.of(
                "EXEC CICS ABEND ABCODE('B123') CANCEL NODUMP END-EXEC"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("ABENDPGM", program)
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("ABENDPGM"), Duration.ofSeconds(5),
                16, 0, 0, 0, true);

        CicsAbend failure = assertThrows(CicsAbend.class, () -> new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2)
                .execute(definition, CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> { }));

        assertEquals("B123", failure.code().value());
        assertEquals(true, failure.cancelHandlers());
        assertEquals(false, failure.dumpRequested());
    }

    @Test
    @DisplayName("HANDLE ABEND LABELは明示ABENDを同じprogramの段落へ移す")
    void handlesExplicitAbendAtCobolLabel() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "HABEND", List.of(
                "EXEC CICS HANDLE ABEND LABEL(ON-ABEND) END-EXEC",
                "EXEC CICS ABEND ABCODE('B123') NODUMP END-EXEC",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK",
                "ON-ABEND",
                "MOVE 'PASS' TO LK-AREA",
                "GOBACK"));

        TaskCompletion result = execute(loader, "HABEND", program);

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("ASSIGN ABCODEはabend exit内で現在の4文字codeを返す")
    void assignsCurrentAbendCodeInsideHandler() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "ABASSIGN", List.of(
                "EXEC CICS HANDLE ABEND LABEL(ON-ABEND) END-EXEC",
                "EXEC CICS ABEND ABCODE('B123') NODUMP END-EXEC",
                "GOBACK",
                "ON-ABEND",
                "EXEC CICS ASSIGN ABCODE(WS-ABCODE) END-EXEC",
                "MOVE WS-ABCODE TO LK-AREA",
                "GOBACK"));

        TaskCompletion result = execute(loader, "ABASSIGN", program);

        assertEquals("B123", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("ASSIGN ABCODEはabend未発生時に空白4文字を返す")
    void assignsSpacesBeforeAnyAbend() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "ABBLANK", List.of(
                "EXEC CICS ASSIGN ABCODE(WS-ABCODE) END-EXEC",
                "MOVE WS-ABCODE TO LK-AREA",
                "GOBACK"));

        TaskCompletion result = execute(loader, "ABBLANK", program);

        assertEquals("    ", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("ASSIGN ABCODEは4byte英数字の書込可能領域だけを受け付ける")
    void rejectsInvalidAssignAbcodeReceivers() {
        assertRejected("EXEC CICS ASSIGN ABCODE(WS-SHORT) END-EXEC",
                "must be a 4-byte alphanumeric data area");
        assertRejected("EXEC CICS ASSIGN ABCODE(WS-RESP) END-EXEC",
                "must be a 4-byte alphanumeric data area");
        assertRejected("EXEC CICS ASSIGN ABCODE(EIBTRNID) END-EXEC",
                "EIBTRNID is read-only");
        assertRejected("EXEC CICS ASSIGN ABCODE('B123') END-EXEC",
                "unsupported or malformed EXEC CICS block");
        assertRejected("EXEC CICS ASSIGN ABCODE(WS-ABCODE) RESP(WS-RESP) END-EXEC",
                "unsupported ASSIGN option: RESP");
        assertRejected("EXEC CICS ASSIGN APPLID(WS-ABCODE) END-EXEC",
                "must be a 8-byte alphanumeric data area");
        assertRejected("EXEC CICS ASSIGN PROGRAM(WS-PGM) PROGRAM(WS-APPL) END-EXEC",
                "duplicate ASSIGN option: PROGRAM");
        assertRejected("EXEC CICS ASSIGN SYSID(WS-PGM) END-EXEC",
                "unsupported ASSIGN option: SYSID");
    }

    @Test
    @DisplayName("ASSIGN PROGRAMはLINK levelごとにCICSが起動したprogramを、APPLIDは構成値を返す")
    void assignsProgramPerLinkLevelAndConfiguredApplid() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> main = compile(loader, "MAIN", List.of(
                "EXEC CICS ASSIGN PROGRAM(WS-PGM) APPLID(WS-APPL) END-EXEC",
                "IF WS-PGM NOT = 'MAIN' OR WS-APPL NOT = 'CICSA1' GOBACK END-IF",
                "EXEC CICS LINK PROGRAM('CHILD') COMMAREA(LK-AREA) END-EXEC",
                "EXEC CICS ASSIGN PROGRAM(WS-PGM) END-EXEC",
                "IF WS-PGM NOT = 'MAIN' OR LK-AREA NOT = 'KID ' GOBACK END-IF",
                "EXEC CICS XCTL PROGRAM('NEXTPGM') COMMAREA(LK-AREA) END-EXEC"));
        Supplier<CobolProgram> child = compile(loader, "CHILD", List.of(
                "EXEC CICS ASSIGN PROGRAM(WS-PGM) END-EXEC",
                "IF WS-PGM = 'CHILD' MOVE 'KID ' TO LK-AREA END-IF",
                "GOBACK"));
        Supplier<CobolProgram> next = compile(loader, "NEXTPGM", List.of(
                "EXEC CICS ASSIGN PROGRAM(WS-PGM) END-EXEC",
                "IF WS-PGM = 'NEXTPGM' MOVE 'DONE' TO LK-AREA END-IF",
                "EXEC CICS RETURN TRANSID('NXT1') COMMAREA(LK-AREA) END-EXEC"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("MAIN", main)
                .cobolProgram("CHILD", child)
                .cobolProgram("NEXTPGM", next)
                .build();

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 8,
                dev.cobolonjava.cics.CicsEnvironment.withApplid("CICSA1"))
                .execute(definition(), CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> { });

        assertEquals("DONE", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("ASKTIMEは地方時の1900年起点ミリ秒とEIB日時を置き、FORMATTIMEは区切りの有無で書く文字数を決める")
    void asksAndFormatsTimeInHostZone() {
        long expectedAbstime = Duration.between(
                java.time.LocalDateTime.of(1900, 1, 1, 0, 0),
                java.time.LocalDateTime.of(2026, 9, 10, 13, 5, 6)).toMillis();
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "TIMEPGM", List.of(
                "MOVE 'FAIL' TO LK-AREA",
                "EXEC CICS ASKTIME ABSTIME(WS-ABS) END-EXEC",
                "EXEC CICS FORMATTIME ABSTIME(WS-ABS) DDMMYYYY(WS-DATE) TIME(WS-TIME) "
                        + "DATESEP END-EXEC",
                "IF WS-ABS = " + expectedAbstime + " AND WS-DATE = '10/09/2026' "
                        + "AND WS-TIME = 130506 AND EIBTIME = 130506 AND EIBDATE = 126253 "
                        + "MOVE 'PASS' TO LK-AREA END-IF",
                "EXEC CICS FORMATTIME ABSTIME(WS-ABS) YYYYMMDD(WS-DATE) END-EXEC",
                "IF WS-DATE NOT = '2026091026' MOVE 'NOSP' TO LK-AREA END-IF",
                "EXEC CICS RETURN TRANSID('NXT1') COMMAREA(LK-AREA) END-EXEC"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("TIMEPGM", program)
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("TIMEPGM"), Duration.ofSeconds(5),
                16, 0, 0, 0, true);
        CicsTaskContext zoned = new CicsTaskContext(
                new CicsTaskId("task_000000000006"), TransId.of("TX01"), "compiler-test",
                Instant.parse("2026-09-10T00:00:00Z"), java.util.OptionalInt.empty(),
                Optional.of(java.time.ZoneId.of("Asia/Tokyo")));
        dev.cobolonjava.cics.CicsEnvironment environment = dev.cobolonjava.cics.CicsEnvironment
                .unconfigured().withClock(java.time.Clock.fixed(
                        Instant.parse("2026-09-10T04:05:06Z"), java.time.ZoneOffset.UTC));

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2, environment)
                .execute(definition, CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        zoned, (action, ignored) -> { });

        // YYYYMMDD 区切りなしは8文字だけを書き、残り2文字 ("26") は直前の値のまま
        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("DELAYはFORの単位とINTERVALを待ちのportへ渡し、RESPへNORMALを置く")
    void delaysThroughTheIntervalPort() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "DELAYPGM", List.of(
                "MOVE 'FAIL' TO LK-AREA",
                "MOVE 2 TO WS-RESP2",
                "MOVE 99 TO WS-RESP",
                "EXEC CICS DELAY FOR SECONDS(WS-RESP2) RESP(WS-RESP) END-EXEC",
                "IF WS-RESP NOT = 0 GOBACK END-IF",
                "EXEC CICS DELAY INTERVAL(000001) END-EXEC",
                "EXEC CICS DELAY FOR MINUTES(0) SECONDS(3) MILLISECS(250) END-EXEC",
                "MOVE 'PASS' TO LK-AREA",
                "EXEC CICS RETURN TRANSID('NXT1') COMMAREA(LK-AREA) END-EXEC"));
        List<Duration> waits = new ArrayList<>();

        TaskCompletion result = runWithEnvironment(loader, "DELAYPGM", program,
                fixedAtTaskStart().withInterval(waits::add), Duration.ofSeconds(30));

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(1),
                Duration.ofMillis(3250)), waits);
    }

    @Test
    @DisplayName("DELAYは範囲外の値と、task期限を越える待ちを推測で進めず失敗させる")
    void rejectsDelaysOutOfRangeOrBeyondTheDeadline() {
        GeneratedLoader loader = new GeneratedLoader();
        List<Duration> waits = new ArrayList<>();
        Supplier<CobolProgram> range = compile(loader, "DELAYBAD", List.of(
                "EXEC CICS DELAY FOR MINUTES(60) SECONDS(1) END-EXEC"));
        Supplier<CobolProgram> tooLong = compile(loader, "DELAYLNG", List.of(
                "EXEC CICS DELAY FOR SECONDS(10) END-EXEC"));

        CicsTaskStateException outOfRange = assertThrows(CicsTaskStateException.class,
                () -> runWithEnvironment(loader, "DELAYBAD", range,
                        fixedAtTaskStart().withInterval(waits::add), Duration.ofSeconds(30)));
        CicsTaskStateException beyond = assertThrows(CicsTaskStateException.class,
                () -> runWithEnvironment(loader, "DELAYLNG", tooLong,
                        fixedAtTaskStart().withInterval(waits::add), Duration.ofSeconds(5)));

        assertTrue(outOfRange.getMessage().contains("MINUTES is out of range"),
                outOfRange.getMessage());
        assertTrue(beyond.getMessage().contains("task deadline"), beyond.getMessage());
        assertEquals(List.of(), waits);
        assertRejected("EXEC CICS DELAY TIME(120000) END-EXEC", "unsupported DELAY option: TIME");
        assertRejected("EXEC CICS DELAY SECONDS(5) END-EXEC", "require FOR");
        assertRejected("EXEC CICS DELAY FOR END-EXEC", "DELAY FOR requires");
        assertRejected("EXEC CICS DELAY FOR SECONDS(WS-DATE) END-EXEC",
                "DELAY SECONDS must be numeric");
    }

    private static dev.cobolonjava.cics.CicsEnvironment fixedAtTaskStart() {
        return dev.cobolonjava.cics.CicsEnvironment.unconfigured().withClock(java.time.Clock.fixed(
                task().startedAt(), java.time.ZoneOffset.UTC));
    }

    private static TaskCompletion runWithEnvironment(
            GeneratedLoader loader, String programId, Supplier<CobolProgram> program,
            dev.cobolonjava.cics.CicsEnvironment environment, Duration timeout) {
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram(programId, program)
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of(programId), timeout, 16, 0, 0, 0, true);
        return new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2, environment)
                .execute(definition, CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> { });
    }

    @Test
    @DisplayName("時間命令は受取域の形を翻訳時に検査し、地方時が無ければ実行時に失敗する")
    void rejectsTimeCommandsWithoutShapeOrZone() {
        assertRejected("EXEC CICS ASKTIME ABSTIME(WS-RESP) END-EXEC",
                "PACKED-DECIMAL(15)");
        assertRejected("EXEC CICS FORMATTIME ABSTIME(WS-ABS) YYDDD(WS-DATE) END-EXEC",
                "unsupported FORMATTIME option: YYDDD");
        assertRejected("EXEC CICS FORMATTIME ABSTIME(WS-ABS) DDMMYYYY(WS-SHORT) DATESEP END-EXEC",
                "at least 10 bytes");
        assertRejected("EXEC CICS FORMATTIME ABSTIME(WS-ABS) TIME(WS-TIME) DATESEP END-EXEC",
                "DATESEP requires a date format option");
        assertRejected("EXEC CICS FORMATTIME ABSTIME(WS-ABS) TIME(WS-RESP) END-EXEC",
                "alphanumeric or an unsigned DISPLAY integer");

        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "NOZONE", List.of(
                "EXEC CICS ASKTIME ABSTIME(WS-ABS) END-EXEC"));
        CicsTaskStateException failure = assertThrows(CicsTaskStateException.class,
                () -> execute(loader, "NOZONE", program));
        assertTrue(failure.getMessage().contains("host time zone"), failure.getMessage());
    }

    @Test
    @DisplayName("APPLIDを構成していないregionのASSIGN APPLIDは推測した名前を返さず失敗する")
    void assignApplidRequiresConfiguration() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "NOAPPL", List.of(
                "EXEC CICS ASSIGN APPLID(WS-APPL) END-EXEC"));

        CicsTaskStateException failure = assertThrows(CicsTaskStateException.class,
                () -> execute(loader, "NOAPPL", program));

        assertTrue(failure.getMessage().contains("configured APPLID"), failure.getMessage());
    }

    @Test
    @DisplayName("ABEND CANCELは登録済みHANDLE ABENDを全levelで迂回する")
    void abendCancelBypassesRegisteredHandler() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> main = compile(loader, "HABCAN", List.of(
                "EXEC CICS HANDLE ABEND LABEL(ON-ABEND) END-EXEC",
                "EXEC CICS LINK PROGRAM('HABCANCH') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "GOBACK",
                "ON-ABEND",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK"));
        Supplier<CobolProgram> child = compile(loader, "HABCANCH", List.of(
                "EXEC CICS ABEND ABCODE('B123') CANCEL NODUMP END-EXEC",
                "GOBACK"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("HABCAN", main)
                .cobolProgram("HABCANCH", child)
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("HABCAN"), Duration.ofSeconds(5),
                16, 0, 0, 0, true);

        CicsAbend failure = assertThrows(
                CicsAbend.class, () -> new CobolCicsTaskProgram(
                        CobolRuntime.builder(catalog).classLoader(loader).build(), 2)
                        .execute(definition,
                                CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                                task(), (action, ignored) -> { }));

        assertEquals("B123", failure.code().value());
        assertEquals(true, failure.cancelHandlers());
    }

    @Test
    @DisplayName("HANDLE ABENDの省略時CANCELはRESETで直前のexitを再有効化する")
    void resetsCancelledAbendHandler() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "HABRESET", List.of(
                "EXEC CICS HANDLE ABEND LABEL(ON-ABEND) END-EXEC",
                "EXEC CICS HANDLE ABEND END-EXEC",
                "EXEC CICS HANDLE ABEND RESET END-EXEC",
                "EXEC CICS ABEND ABCODE('B345') NODUMP END-EXEC",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK",
                "ON-ABEND",
                "MOVE 'PASS' TO LK-AREA",
                "GOBACK"));

        TaskCompletion result = execute(loader, "HABRESET", program);

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("LINK先のABENDは最初の上位level HANDLE ABENDへ移る")
    void handlesLinkedProgramAbendAtCallingLevel() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> main = compile(loader, "HABMAIN", List.of(
                "EXEC CICS HANDLE ABEND LABEL(ON-ABEND) END-EXEC",
                "EXEC CICS LINK PROGRAM('HABCHILD') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK",
                "ON-ABEND",
                "EXEC CICS ASSIGN ABCODE(WS-ABCODE) END-EXEC",
                "MOVE WS-ABCODE TO LK-AREA",
                "GOBACK"));
        Supplier<CobolProgram> child = compile(loader, "HABCHILD", List.of(
                "EXEC CICS ABEND ABCODE('B234') NODUMP END-EXEC"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("HABMAIN", main)
                .cobolProgram("HABCHILD", child)
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("HABMAIN"), Duration.ofSeconds(5),
                16, 0, 0, 0, true);

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2)
                .execute(definition,
                        CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> { });

        assertEquals("B234", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("通常CALL先のABENDは同じLINK levelの登録元LABELへ戻る")
    void handlesCalledProgramAbendAtRegisteringProgram() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> main = compile(loader, "HABCALL", List.of(
                "EXEC CICS HANDLE ABEND LABEL(ON-ABEND) END-EXEC",
                "CALL 'HABCALLCH' USING LK-AREA",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK",
                "ON-ABEND",
                "MOVE 'PASS' TO LK-AREA",
                "GOBACK"));
        Supplier<CobolProgram> child = compile(loader, "HABCALLCH", List.of(
                "EXEC CICS ABEND ABCODE('B456') NODUMP END-EXEC"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("HABCALL", main)
                .cobolProgram("HABCALLCH", child)
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("HABCALL"), Duration.ofSeconds(5),
                16, 0, 0, 0, true);

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2)
                .execute(definition,
                        CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> { });

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("abend exitは実行前に無効化され再ABENDで再入しない")
    void preventsRecursiveReentryIntoAbendHandler() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "HABRECUR", List.of(
                "EXEC CICS HANDLE ABEND LABEL(ON-ABEND) END-EXEC",
                "EXEC CICS ABEND ABCODE('B111') NODUMP END-EXEC",
                "GOBACK",
                "ON-ABEND",
                "EXEC CICS ABEND ABCODE('B222') NODUMP END-EXEC"));

        CicsAbend failure = assertThrows(
                CicsAbend.class, () -> execute(loader, "HABRECUR", program));

        assertEquals("B222", failure.code().value());
    }

    @Test
    @DisplayName("ABENDの予約code、未定義のABCODEデータ名、重複flagを翻訳時に拒否する")
    void rejectsUnsupportedAbendForms() {
        assertRejected("EXEC CICS ABEND ABCODE('A123') END-EXEC",
                "must not start with reserved letter A");
        assertRejected("EXEC CICS ABEND ABCODE(WS-CODE) END-EXEC",
                "undefined data item: WS-CODE");
        assertRejected("EXEC CICS ABEND CANCEL CANCEL END-EXEC",
                "duplicate EXEC CICS option");
        assertRejected("EXEC CICS HANDLE ABEND PROGRAM('EXITPGM') END-EXEC",
                "accepts LABEL, CANCEL, or RESET");
        assertRejected("EXEC CICS HANDLE ABEND LABEL(NOWHERE) END-EXEC",
                "undefined CICS abend handler");
        assertRejected("EXEC CICS HANDLE ABEND CANCEL RESET END-EXEC",
                "accepts LABEL, CANCEL, or RESET");
    }

    @Test
    @DisplayName("生成COBOLからtask-local EIBのTRANSID、CALEN、RESPを参照する")
    void readsImplicitEibFields() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> main = compile(loader, "EIBMAIN", List.of(
                "MOVE 99 TO WS-RESP WS-RESP2",
                "EXEC CICS LINK PROGRAM('EIBCHILD') COMMAREA(LK-AREA) LENGTH(4) "
                        + "RESP(WS-RESP) RESP2(WS-RESP2) END-EXEC",
                "IF EIBCALEN = 4 AND EIBFN NOT = LOW-VALUES "
                        + "AND EIBRCODE = LOW-VALUES AND EIBRESP = 0 AND EIBRESP2 = 0 "
                        + "AND WS-RESP = 0 AND WS-RESP2 = 0 "
                        + "MOVE EIBTRNID TO LK-AREA",
                "EXEC CICS RETURN TRANSID('NXT1') COMMAREA(LK-AREA) LENGTH(4) END-EXEC"));
        Supplier<CobolProgram> child = compile(loader, "EIBCHILD", List.of("GOBACK"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("EIBMAIN", main)
                .cobolProgram("EIBCHILD", child)
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("EIBMAIN"), Duration.ofSeconds(5),
                16, 0, 0, 0, true);

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2)
                .execute(definition, CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> { });

        assertEquals("TX01", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("EIB fieldをCOBOL文の受取側に指定すると翻訳を拒否する")
    void rejectsWritesToEibFields() {
        assertRejected("MOVE 1 TO EIBRESP", "EIBRESP is read-only");
        assertRejected("ADD 1 TO EIBRESP2", "EIBRESP2 is read-only");
        assertRejected("MOVE 'AA' TO EIBFN", "EIBFN is read-only");
        assertRejected("MOVE LOW-VALUES TO EIBRCODE", "EIBRCODE is read-only");
        assertRejected("CALL 'CHILD' USING EIBTRNID", "EIBTRNID is read-only");
        assertRejected("EXEC CICS LINK PROGRAM('CHILD') COMMAREA(EIBTRNID) "
                + "LENGTH(4) END-EXEC", "EIBTRNID is read-only");
    }

    @Test
    @DisplayName("RESP指定時は非正常結果をfullword項目へ返してCOBOL処理を継続する")
    void returnsHandledResponseToCobolFields() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "RESPMAIN", List.of(
                "EXEC CICS LINK PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) "
                        + "RESP(WS-RESP) RESP2(WS-RESP2) END-EXEC",
                "IF WS-RESP = 27 AND WS-RESP2 = 1 MOVE 'PASS' TO LK-AREA",
                "GOBACK"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("RESPMAIN", program)
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("RESPMAIN"), Duration.ofSeconds(5),
                16, 0, 0, 0, true);

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2)
                .execute(definition, CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> { });

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("NOHANDLE指定時は非正常結果をEIBへ残して次のCOBOL文へ進む")
    void suppressesDefaultHandlingWithNohandle() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "NOHMAIN", List.of(
                "EXEC CICS LINK PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) NOHANDLE END-EXEC",
                "IF EIBRESP = DFHRESP(PGMIDERR) AND EIBRESP2 = 1 MOVE 'PASS' TO LK-AREA",
                "GOBACK"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("NOHMAIN", program)
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("NOHMAIN"), Duration.ofSeconds(5),
                16, 0, 0, 0, true);

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2)
                .execute(definition, CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> { });

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("HANDLE CONDITIONはPGMIDERR発生時に登録した段落へ制御を移す")
    void transfersToHandleConditionParagraph() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "HANDMAIN", List.of(
                "EXEC CICS HANDLE CONDITION PGMIDERR(ON-ERROR) END-EXEC",
                "EXEC CICS LINK PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK",
                "ON-ERROR",
                "IF EIBRESP = DFHRESP(PGMIDERR) AND EIBRESP2 = 1 MOVE 'PASS' TO LK-AREA",
                "GOBACK"));

        TaskCompletion result = execute(loader, "HANDMAIN", program);

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("未登録XCTL先のPGMIDERRは移送元programのhandlerで処理する")
    void handlesMissingXctlTargetBeforeProgramTransfer() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "XCTLMISS", List.of(
                "EXEC CICS HANDLE CONDITION PGMIDERR(ON-ERROR) END-EXEC",
                "EXEC CICS XCTL PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK",
                "ON-ERROR",
                "IF EIBRESP = DFHRESP(PGMIDERR) AND EIBRESP2 = 1 MOVE 'PASS' TO LK-AREA",
                "GOBACK"));

        TaskCompletion result = execute(loader, "XCTLMISS", program);

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("RESP指定XCTLは未登録先でも移送元の次のCOBOL文へ進む")
    void returnsMissingXctlResponseToCallingStatement() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "XCTLRESP", List.of(
                "EXEC CICS XCTL PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) "
                        + "RESP(WS-RESP) RESP2(WS-RESP2) END-EXEC",
                "IF WS-RESP = 27 AND WS-RESP2 = 1 MOVE 'PASS' TO LK-AREA",
                "GOBACK"));

        TaskCompletion result = execute(loader, "XCTLRESP", program);

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("HANDLE CONDITION ERRORは個別処置のない非正常conditionを受け止める")
    void transfersToGeneralizedErrorHandler() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "ERRMAIN", List.of(
                "EXEC CICS HANDLE CONDITION ERROR(ON-ERROR) END-EXEC",
                "EXEC CICS LINK PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK",
                "ON-ERROR",
                "IF EIBRESP = DFHRESP(PGMIDERR) MOVE 'PASS' TO LK-AREA",
                "GOBACK"));

        TaskCompletion result = execute(loader, "ERRMAIN", program);

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("一つのHANDLE CONDITIONに列挙した個別handlerをERRORより優先する")
    void registersMultipleConditionsInOneCommand() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "MULTHND", List.of(
                "EXEC CICS HANDLE CONDITION ERROR(ON-ERROR) PGMIDERR(ON-PGMID) END-EXEC",
                "EXEC CICS LINK PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK",
                "ON-ERROR",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK",
                "ON-PGMID",
                "MOVE 'PASS' TO LK-AREA",
                "GOBACK"));

        TaskCompletion result = execute(loader, "MULTHND", program);

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("RESPは登録済みHANDLE CONDITIONをそのcommandだけ抑止する")
    void responseOptionBypassesRegisteredHandler() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "HRESP", List.of(
                "EXEC CICS HANDLE CONDITION PGMIDERR(ON-ERROR) END-EXEC",
                "EXEC CICS LINK PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) "
                        + "RESP(WS-RESP) END-EXEC",
                "IF WS-RESP = 27 MOVE 'PASS' TO LK-AREA",
                "GOBACK",
                "ON-ERROR",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK"));

        TaskCompletion result = execute(loader, "HRESP", program);

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("IGNORE CONDITIONは登録済みhandlerを置き換えて次の文へ進む")
    void ignoreConditionOverridesRegisteredHandler() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "IGNMAIN", List.of(
                "EXEC CICS HANDLE CONDITION PGMIDERR(ON-ERROR) END-EXEC",
                "EXEC CICS IGNORE CONDITION PGMIDERR END-EXEC",
                "EXEC CICS LINK PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "MOVE 'PASS' TO LK-AREA",
                "GOBACK",
                "ON-ERROR",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK"));

        TaskCompletion result = execute(loader, "IGNMAIN", program);

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("一つのIGNORE CONDITIONに列挙した個別conditionとERRORを継続させる")
    void ignoresGeneralizedErrorCondition() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "IGNERR", List.of(
                "EXEC CICS HANDLE CONDITION ERROR(ON-ERROR) END-EXEC",
                "EXEC CICS IGNORE CONDITION ERROR PGMIDERR END-EXEC",
                "EXEC CICS LINK PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "MOVE 'PASS' TO LK-AREA",
                "GOBACK",
                "ON-ERROR",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK"));

        TaskCompletion result = execute(loader, "IGNERR", program);

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("PUSH HANDLE後の変更はPOP HANDLEで退避前のcondition処置へ戻る")
    void restoresConditionHandlersWithPushAndPop() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "PUSHPOP", List.of(
                "EXEC CICS HANDLE CONDITION PGMIDERR(ON-ERROR) END-EXEC",
                "EXEC CICS PUSH HANDLE END-EXEC",
                "EXEC CICS IGNORE CONDITION PGMIDERR END-EXEC",
                "EXEC CICS POP HANDLE END-EXEC",
                "EXEC CICS LINK PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK",
                "ON-ERROR",
                "MOVE 'PASS' TO LK-AREA",
                "GOBACK"));

        TaskCompletion result = execute(loader, "PUSHPOP", program);

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("PUSH HANDLE中は退避したcondition handlerを実行しない")
    void suspendsConditionHandlerBetweenPushAndPop() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "PUSHSUSP", List.of(
                "EXEC CICS HANDLE CONDITION PGMIDERR(ON-ERROR) END-EXEC",
                "EXEC CICS PUSH HANDLE END-EXEC",
                "EXEC CICS LINK PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "GOBACK",
                "ON-ERROR",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK"));

        CicsTaskStateException failure = assertThrows(
                CicsTaskStateException.class,
                () -> execute(loader, "PUSHSUSP", program));

        assertTrue(failure.getMessage().contains("RESP=27"));
    }

    @Test
    @DisplayName("対応するPUSH HANDLEのないPOP HANDLEは実行時に拒否する")
    void rejectsPopHandleWithoutPush() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "BADPOP", List.of(
                "EXEC CICS POP HANDLE END-EXEC",
                "GOBACK"));

        CicsTaskStateException failure = assertThrows(
                CicsTaskStateException.class,
                () -> execute(loader, "BADPOP", program));

        assertTrue(failure.getMessage().contains("no matching PUSH HANDLE"));
    }

    @Test
    @DisplayName("handler名を省略したHANDLE CONDITIONはCICS既定処置へ戻す")
    void handleConditionWithoutParagraphRestoresDefault() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "RSTMAIN", List.of(
                "EXEC CICS HANDLE CONDITION PGMIDERR(ON-ERROR) END-EXEC",
                "EXEC CICS HANDLE CONDITION PGMIDERR END-EXEC",
                "EXEC CICS LINK PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "GOBACK",
                "ON-ERROR",
                "GOBACK"));

        CicsTaskStateException failure = assertThrows(
                CicsTaskStateException.class,
                () -> execute(loader, "RSTMAIN", program));

        assertTrue(failure.getMessage().contains("RESP=27"));
    }

    @Test
    @DisplayName("個別conditionのhandler省略はERROR handlerより優先して既定処置を選ぶ")
    void explicitDefaultConditionBypassesGeneralizedError() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "DEFMAIN", List.of(
                "EXEC CICS HANDLE CONDITION ERROR(ON-ERROR) END-EXEC",
                "EXEC CICS HANDLE CONDITION PGMIDERR END-EXEC",
                "EXEC CICS LINK PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "GOBACK",
                "ON-ERROR",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK"));

        CicsTaskStateException failure = assertThrows(
                CicsTaskStateException.class,
                () -> execute(loader, "DEFMAIN", program));

        assertTrue(failure.getMessage().contains("RESP=27"));
    }

    @Test
    @DisplayName("LINK先programは呼出元のcondition handlerを継承しない")
    void doesNotInheritConditionHandlerAcrossLinkLevel() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> main = compile(loader, "LVLMAIN", List.of(
                "EXEC CICS HANDLE CONDITION PGMIDERR(CALLER-ERROR) END-EXEC",
                "EXEC CICS LINK PROGRAM('LVLCHILD') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "GOBACK",
                "CALLER-ERROR",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK"));
        Supplier<CobolProgram> child = compile(loader, "LVLCHILD", List.of(
                "EXEC CICS LINK PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "GOBACK"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("LVLMAIN", main)
                .cobolProgram("LVLCHILD", child)
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("LVLMAIN"), Duration.ofSeconds(5),
                16, 0, 0, 0, true);

        CicsTaskStateException failure = assertThrows(CicsTaskStateException.class,
                () -> new CobolCicsTaskProgram(
                        CobolRuntime.builder(catalog).classLoader(loader).build(), 2)
                        .execute(definition,
                                CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                                task(), (action, ignored) -> { }));

        assertTrue(failure.getMessage().contains("RESP=27"));
    }

    @Test
    @DisplayName("通常CALLを重ねた先のconditionは同じLINK levelの登録元handler段落へ戻る")
    void transfersConditionToCallingProgramWithinLinkLevel() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> main = compile(loader, "CALLMAIN", List.of(
                "EXEC CICS HANDLE CONDITION PGMIDERR(CALLER-ERROR) END-EXEC",
                "CALL 'CALLMID' USING LK-AREA",
                "MOVE 'FAIL' TO LK-AREA",
                "GOBACK",
                "CALLER-ERROR",
                "MOVE 'PASS' TO LK-AREA",
                "GOBACK"));
        Supplier<CobolProgram> middle = compile(loader, "CALLMID", List.of(
                "CALL 'CALLSUB' USING LK-AREA",
                "GOBACK"));
        Supplier<CobolProgram> child = compile(loader, "CALLSUB", List.of(
                "EXEC CICS LINK PROGRAM('MISSING') COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "GOBACK"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("CALLMAIN", main)
                .cobolProgram("CALLMID", middle)
                .cobolProgram("CALLSUB", child)
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("CALLMAIN"), Duration.ofSeconds(5),
                16, 0, 0, 0, true);

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2)
                .execute(definition,
                        CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> { });

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("未対応condition、存在しないhandler、複数conditionを翻訳時に拒否する")
    void rejectsUnsupportedConditionHandlerForms() {
        assertRejected("EXEC CICS HANDLE CONDITION NOTFND(ON-ERROR) END-EXEC",
                "limited to PGMIDERR");
        assertRejected("EXEC CICS HANDLE CONDITION PGMIDERR(NOWHERE) END-EXEC",
                "undefined CICS condition handler");
        assertRejected("EXEC CICS HANDLE CONDITION PGMIDERR(ON-ERROR) "
                        + "NOTFND(ON-ERROR) END-EXEC",
                "limited to PGMIDERR, MAPFAIL and ERROR");
        assertRejected("EXEC CICS IGNORE CONDITION PGMIDERR(ON-ERROR) END-EXEC",
                "does not accept a handler paragraph");
        assertRejected("EXEC CICS HANDLE CONDITION PGMIDERR(ON-ERROR) "
                        + "PGMIDERR(ON-ERROR) END-EXEC",
                "duplicate CICS condition");
        assertRejected("EXEC CICS HANDLE CONDITION END-EXEC",
                "requires at least one condition");
        assertRejected("EXEC CICS HANDLE CONDITION PGMIDERR(ON-ERROR)ERROR(ON-ERROR) END-EXEC",
                "must be separated by whitespace");
        assertRejected("EXEC CICS HANDLE CONDITION "
                        + String.join(" ", List.of(
                        "C01", "C02", "C03", "C04", "C05", "C06", "C07", "C08", "C09",
                        "C10", "C11", "C12", "C13", "C14", "C15", "C16", "C17"))
                        + " END-EXEC",
                "no more than 16 conditions");
        assertRejected("EXEC CICS PUSH HANDLE EXTRA END-EXEC",
                "unsupported or malformed EXEC CICS block");
        assertRejected("EXEC CICS POP CONDITION END-EXEC",
                "unsupported or malformed EXEC CICS block");
    }

    @Test
    @DisplayName("DFHRESPの対応condition名を翻訳時のfullword値として利用する")
    void translatesSupportedDfhrespConditions() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, "DFHRMAIN", List.of(
                "MOVE DFHRESP(PGMIDERR) TO WS-RESP",
                "IF DFHRESP(NORMAL) = 0 AND WS-RESP = 27 MOVE 'PASS' TO LK-AREA",
                "GOBACK"));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("DFHRMAIN", program)
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("DFHRMAIN"), Duration.ofSeconds(5),
                16, 0, 0, 0, true);

        TaskCompletion result = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2)
                .execute(definition, CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> { });

        assertEquals("PASS", CodePages.DEFAULT.decode(result.payload().commarea()));
    }

    @Test
    @DisplayName("未分類のDFHRESP condition名を推測せず翻訳時に拒否する")
    void rejectsUnsupportedDfhrespCondition() {
        // NOTFND は公開の表で数を確かめたので受ける。表から読めなかった NOTOPEN は断る
        assertRejected("IF EIBRESP = DFHRESP(NOTOPEN) CONTINUE",
                "unsupported CICS condition name: NOTOPEN");
    }

    @Test
    @DisplayName("宣言済みのDFHRESPという表名は通常のCOBOLデータ項目として優先する")
    void preservesDeclaredDataItemNamedDfhresp() {
        List<String> source = List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. DATAITEM.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 DFHRESP PIC S9(8) COMP OCCURS 2 TIMES.",
                "01 WS-RESP PIC S9(8) COMP.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    MOVE DFHRESP(1) TO WS-RESP.",
                "    GOBACK.");

        CobolCompiler.Result result = CobolCompiler.standard().compile("DATAITEM.cbl",
                source.stream().map(line -> "       " + line + "\n")
                        .reduce("", String::concat));

        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
    }

    @Test
    @DisplayName("NOHANDLEの重複指定を翻訳時に拒否する")
    void rejectsDuplicateNohandle() {
        assertRejected("EXEC CICS SYNCPOINT NOHANDLE NOHANDLE END-EXEC",
                "duplicate EXEC CICS option");
    }

    @Test
    @DisplayName("RESP2単独指定とfullword binaryでない受取項目を拒否する")
    void rejectsInvalidResponseOptions() {
        assertRejected("EXEC CICS SYNCPOINT RESP2(WS-RESP2) END-EXEC",
                "RESP2 requires RESP");
        assertRejected("EXEC CICS SYNCPOINT RESP(LK-AREA) END-EXEC",
                "must be a 4-byte binary integer");
        assertRejected("EXEC CICS SYNCPOINT RESP(EIBRESP) END-EXEC",
                "EIBRESP is read-only");
    }

    private static void assertRejected(String command, String expected) {
        CobolCompiler.Result result = compileResult("REJECT", List.of(command));
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.message().contains(expected)),
                result.diagnostics().toString());
    }

    private static Supplier<CobolProgram> compile(
            GeneratedLoader loader, String programId, List<String> procedure) {
        CobolCompiler.Result result = compileResult(programId, procedure);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        Class<?> type = loader.define(result.className(), result.classFile());
        return () -> {
            try {
                return (CobolProgram) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("cannot create generated program", failure);
            }
        };
    }

    private static TaskCompletion execute(
            GeneratedLoader loader, String programId, Supplier<CobolProgram> program) {
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram(programId, program)
                .build();
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of(programId), Duration.ofSeconds(5),
                16, 0, 0, 0, true);
        return new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2)
                .execute(definition, CicsPayload.ofCommarea(CodePages.DEFAULT.encode("INIT")),
                        task(), (action, ignored) -> { });
    }

    private static CobolCompiler.Result compileResult(String programId, List<String> procedure) {
        List<String> source = new ArrayList<>(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. " + programId + ".",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-RESP PIC S9(8) COMP.",
                "01 WS-RESP2 PIC S9(8) COMP.",
                "01 WS-ABCODE PIC X(4).",
                "01 WS-SHORT PIC X(3).",
                "01 WS-PGM PIC X(8).",
                "01 WS-APPL PIC X(8).",
                "01 WS-ABS PIC S9(15) COMP-3.",
                "01 WS-DATE PIC X(10).",
                "01 WS-TIME PIC 9(6).",
                "01 WS-CHAN PIC X(16).",
                "01 WS-CONT PIC X(16).",
                "01 WS-FLEN PIC S9(8) COMP.",
                "01 WS-LEN PIC S9(4) COMP.",
                "01 WS-CHILD PIC X(16).",
                "01 WS-FETCHCH PIC X(16).",
                "01 WS-COMPST PIC S9(8) COMP.",
                "01 WS-GRP.",
                "   03 WS-QTIME PIC 9(6).",
                "LINKAGE SECTION.",
                "01 LK-AREA PIC X(4).",
                "PROCEDURE DIVISION USING LK-AREA.",
                "MAIN-START."));
        procedure.forEach(line -> addWrapped(source, line + "."));
        return CobolCompiler.standard().compile(programId + ".cbl", source.stream()
                .map(line -> "       " + line + "\n")
                .reduce("", String::concat));
    }

    /** 固定形式の72桁を越えないよう、EXEC commandを空白位置で継続行へ分ける。 */
    private static void addWrapped(List<String> source, String logicalLine) {
        StringBuilder physical = new StringBuilder("    ");
        for (String word : logicalLine.split(" ")) {
            if (physical.length() > 4 && physical.length() + 1 + word.length() > 60) {
                source.add(physical.toString());
                physical = new StringBuilder("    ");
            }
            if (physical.length() > 4) {
                physical.append(' ');
            }
            physical.append(word);
        }
        source.add(physical.toString());
    }

    private static CicsTransactionDefinition definition() {
        return new CicsTransactionDefinition(
                TransId.of("TX01"), ProgramId.of("MAIN"), Duration.ofSeconds(5),
                16, 0, 0, 0, true);
    }

    private static CicsTaskContext task() {
        return new CicsTaskContext(
                new CicsTaskId("task_000000000004"), TransId.of("TX01"),
                "compiler-test", Instant.parse("2026-09-10T03:00:00Z"));
    }
}
