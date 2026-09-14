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
        assertRejected("IF EIBRESP = DFHRESP(NOTFND) CONTINUE",
                "unsupported CICS condition name: NOTFND");
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
