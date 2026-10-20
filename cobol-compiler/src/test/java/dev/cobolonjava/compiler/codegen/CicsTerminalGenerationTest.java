package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.CicsEnvironment;
import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskContext;
import dev.cobolonjava.cics.CicsTaskId;
import dev.cobolonjava.cics.CicsTerminalScreen;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CobolCicsTaskProgram;
import dev.cobolonjava.cics.TaskCompletion;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.cics.bms.BmsAid;
import dev.cobolonjava.cics.bms.BmsMapsetCatalog;
import dev.cobolonjava.cics.bms.BmsTerminalInput;
import dev.cobolonjava.cics.bms.BmsParser;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.source.BmsCopyBookResolver;
import dev.cobolonjava.compiler.source.CicsSystemCopyBookResolver;
import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** BMS の記号マップ写し句を COPY した program から SEND 命令を発行する結合試験。 */
@Tag("V1")
class CicsTerminalGenerationTest {

    private static final class Loader extends ClassLoader {

        Loader() {
            super(CicsTerminalGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    @TempDir
    Path directory;

    private static String card(String body, boolean continued) {
        return continued ? String.format("%-71s*", body) : body;
    }

    private static final String BMS = String.join("\n",
            card("SCRSET   DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,STORAGE=AUTO,", true),
            card("               TIOAPFX=YES,EXTATT=YES,DSATTS=(COLOR,HILIGHT)", false),
            card("SCRMP    DFHMDI SIZE=(24,80)", false),
            card("         DFHMDF POS=(1,1),LENGTH=5,INITIAL='TITLE',ATTRB=(PROT,NORM)", false),
            card("CUSTNO   DFHMDF POS=(5,17),LENGTH=10,ATTRB=(NORM,NUM,IC)", false),
            card("MESSAGE  DFHMDF POS=(23,1),LENGTH=20,ATTRB=(BRT,PROT)", false),
            card("NAME     DFHMDF POS=(24,1),LENGTH=8,ATTRB=(UNPROT,NORM)", false),
            card("         DFHMSD TYPE=FINAL", false),
            card("         END", false)) + "\n";

    private CobolCompiler compiler() throws IOException {
        Files.writeString(directory.resolve("SCRSET.bms"), BMS);
        BmsCopyBookResolver bms = new BmsCopyBookResolver(directory);
        CicsSystemCopyBookResolver system = new CicsSystemCopyBookResolver();
        CopyBookResolver chain = (name, library) -> bms.resolve(name, library)
                .or(() -> system.resolve(name, library));
        return CobolCompiler.with(chain);
    }

    private static String program(String... procedure) {
        StringBuilder out = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SCREEN1.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "COPY SCRSET.",
                "COPY DFHAID.",
                "01  WS-RESP PIC S9(8) COMP.",
                "01  WS-TEXT PIC X(13) VALUE 'Session ended'.",
                "01  WS-MAPN PIC X(7) VALUE 'SCRMP'.",
                "01  WS-SETN PIC X(8) VALUE 'SCRSET'.",
                "01  WS-BADN PIC X(7) VALUE 'scrmp'.",
                "01  WS-RECV PIC X(10) VALUE ALL '.'.",
                "01  WS-RLEN PIC S9(4) COMP.",
                "LINKAGE SECTION.",
                "01  LK-AREA PIC X(4).",
                "PROCEDURE DIVISION USING LK-AREA.")) {
            out.append("       ").append(line).append('\n');
        }
        for (String line : procedure) {
            out.append("           ").append(line).append('\n');
        }
        return out.toString();
    }

    private static TaskCompletion run(CobolCompiler.Result result, String input) {
        return run(result, input, new CicsTaskContext(new CicsTaskId("task_terminal_gen"),
                TransId.of("TX01"), "terminal-test", Instant.parse("2026-09-10T03:00:00Z")));
    }

    private static CicsEnvironment environment() {
        return CicsEnvironment.unconfigured()
                .withMapsets(BmsMapsetCatalog.of(List.of(BmsParser.parse(BMS))));
    }

    private static TaskCompletion run(CobolCompiler.Result result, String input, CicsTaskContext task) {
        return run(result, input, task, environment());
    }

    private static TaskCompletion run(CobolCompiler.Result result, String input, CicsTaskContext task,
                                      CicsEnvironment environment) {
        assertTrue(result.succeeded(), result.diagnostics().toString());
        Loader loader = new Loader();
        Class<?> type = loader.define(result.className(), result.classFile());
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("SCREEN1", () -> {
                    try {
                        return (CobolProgram) type.getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                })
                .build();
        return new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2, environment)
                .execute(new CicsTransactionDefinition(TransId.of("TX01"), ProgramId.of("SCREEN1"),
                                Duration.ofSeconds(5), 16, 0, 0, 0, true),
                        CicsPayload.ofCommarea(CodePages.DEFAULT.encode(input)),
                        task, (action, ignored) -> { });
    }

    private static final String[] PROCEDURE = {
        // INTO を省けば map 名 + I の SCRMPI に入る
        "IF LK-AREA = 'RECV'",
        "    EXEC CICS RECEIVE MAP('SCRMP') MAPSET('SCRSET')",
        "         RESP(WS-RESP)",
        "    END-EXEC",
        "    MOVE 'FAIL' TO LK-AREA",
        "    IF WS-RESP = DFHRESP(MAPFAIL)",
        "        MOVE 'MFAL' TO LK-AREA",
        "    END-IF",
        "    IF WS-RESP = 0 AND EIBAID = DFHENTER AND CUSTNOL = 3",
        "       AND CUSTNOI = '042'",
        "        MOVE 'GOT ' TO LK-AREA",
        "    END-IF",
        "    EXEC CICS RETURN TRANSID('NXT1') COMMAREA(LK-AREA)",
        "    END-EXEC",
        "END-IF",
        // 端末制御の RECEIVE。書式の無い画面で打たれた入力を transaction ID から読む
        "IF LK-AREA = 'RAW ' OR 'RAW2'",
        "    MOVE 10 TO WS-RLEN",
        "    IF LK-AREA = 'RAW '",
        "        EXEC CICS RECEIVE INTO(WS-RECV) LENGTH(WS-RLEN)",
        "             RESP(WS-RESP) END-EXEC",
        "    ELSE",
        "        EXEC CICS RECEIVE INTO(WS-RECV) LENGTH(WS-RLEN)",
        "             MAXLENGTH(6) RESP(WS-RESP) END-EXEC",
        "    END-IF",
        "    MOVE 'FAIL' TO LK-AREA",
        "    IF WS-RESP = 0 AND WS-RLEN = 8 AND WS-RECV = 'TX01 ABC..'",
        "        MOVE 'GOT ' TO LK-AREA",
        "    END-IF",
        "    IF WS-RESP = DFHRESP(LENGERR) AND WS-RLEN = 8",
        "       AND WS-RECV = 'TX01 a....'",
        "        MOVE 'LERR' TO LK-AREA",
        "    END-IF",
        "    EXEC CICS RETURN TRANSID('NXT1') COMMAREA(LK-AREA)",
        "    END-EXEC",
        "END-IF",
        // 数字の LENGTH は FROM の先頭から送る長さ。WAIT は何も変えない (GenApp の lgipvs01)
        "IF LK-AREA = 'TXT5'",
        "    EXEC CICS SEND TEXT FROM(WS-TEXT) WAIT ERASE LENGTH(5)",
        "    END-EXEC",
        "    EXEC CICS RETURN END-EXEC",
        "END-IF",
        // 端末制御の SEND。MAP も TEXT も書かない
        "IF LK-AREA = 'DATA'",
        "    EXEC CICS SEND FROM(WS-TEXT) LENGTH(LENGTH OF WS-TEXT)",
        "         NOHANDLE ERASE",
        "    END-EXEC",
        "    EXEC CICS RETURN END-EXEC",
        "END-IF",
        "IF LK-AREA = 'TEXT'",
        // FROM 自身の長さを書いた LENGTH は、省いた形と同じである
        "    EXEC CICS SEND TEXT FROM(WS-TEXT) ERASE FREEKB",
        "         LENGTH(LENGTH OF WS-TEXT)",
        "    END-EXEC",
        "    EXEC CICS RETURN END-EXEC",
        "END-IF",
        "MOVE LOW-VALUES TO SCRMPO",
        "MOVE -1 TO CUSTNOL",
        "MOVE 'HELLO' TO MESSAGEO",
        // FROM を省けば map 名 + O の SCRMPO から送る
        "EXEC CICS SEND MAP('SCRMP') MAPSET('SCRSET')",
        "     ERASE CURSOR FREEKB",
        "     RESP(WS-RESP)",
        "END-EXEC",
        "IF WS-RESP = 0",
        "    EXEC CICS SEND CONTROL ALARM END-EXEC",
        "END-IF",
        "EXEC CICS RETURN TRANSID('NXT1') COMMAREA(LK-AREA)",
        "END-EXEC.",
    };

    @Test
    @DisplayName("SEND MAPは記号マップから画面を作り、SEND CONTROLを重ね、task結果へ画面を残す")
    void sendsMapAndControlToTheTaskResult() throws IOException {
        TaskCompletion result = run(compiler().compile("SCREEN1.cbl", program(PROCEDURE)), "MAP ");

        BmsScreenSnapshot screen = ((CicsTerminalScreen.MapScreen) result.screen().orElseThrow())
                .snapshot();
        assertEquals("TITLE", screen.fields().get(0).data());
        assertEquals(String.format("%-20s", "HELLO"), screen.field("MESSAGE", 1).orElseThrow().data());
        assertEquals(4 * 80 + 17, screen.cursorOffset());
        assertTrue(screen.keyboardRestored());
        assertTrue(screen.alarm());
        assertEquals(Optional.of(TransId.of("NXT1")), result.nextTransaction());
    }

    @Test
    @DisplayName("MAP / MAPSET をデータ名で書けば、実行時の値の map を送る (CardDemo の MAP(LIT-THISMAP))")
    void sendsMapNamedByDataArea() throws IOException {
        TaskCompletion result = run(compiler().compile("SCREEN1.cbl", program(
                "MOVE LOW-VALUES TO SCRMPO",
                "MOVE 'BY NAME' TO MESSAGEO",
                "EXEC CICS SEND MAP(WS-MAPN) MAPSET(WS-SETN)",
                "     FROM(SCRMPO) ERASE END-EXEC",
                "EXEC CICS RETURN END-EXEC.")), "MAP ");

        BmsScreenSnapshot screen = ((CicsTerminalScreen.MapScreen) result.screen().orElseThrow()).snapshot();
        assertEquals("SCRMP", screen.map());
        assertEquals(String.format("%-20s", "BY NAME"), screen.field("MESSAGE", 1).orElseThrow().data());
    }

    @Test
    @DisplayName("MAP のデータ域が名前の形でなければ (小文字を含む) 推測で直さず実行時に断る")
    void refusesMapDataAreasThatAreNotNames() throws IOException {
        CobolCompiler.Result compiled = compiler().compile("SCREEN1.cbl", program(
                "MOVE LOW-VALUES TO SCRMPO",
                "EXEC CICS SEND MAP(WS-BADN) MAPSET(WS-SETN)",
                "     FROM(SCRMPO) ERASE END-EXEC",
                "EXEC CICS RETURN END-EXEC."));

        RuntimeException refused = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> run(compiled, "MAP "));
        Throwable cause = refused;
        while (cause.getCause() != null && !String.valueOf(cause.getMessage()).contains("MAP data area")) {
            cause = cause.getCause();
        }
        assertTrue(String.valueOf(cause.getMessage()).contains("MAP data area does not contain a valid name"),
                () -> String.valueOf(refused));
    }

    @Test
    @DisplayName("RECEIVE MAPは要求の端末入力を直前の画面と照合して入力側の記号マップへ置き、CLEARはMAPFAIL")
    void receivesMapFromTheTerminalInput() throws IOException {
        CobolCompiler.Result compiled = compiler().compile("SCREEN1.cbl", program(PROCEDURE));
        BmsScreenSnapshot sent = ((CicsTerminalScreen.MapScreen) run(compiled, "MAP ").screen()
                .orElseThrow()).snapshot();
        CicsTaskContext base = new CicsTaskContext(new CicsTaskId("task_terminal_recv"),
                TransId.of("TX01"), "terminal-test", Instant.parse("2026-09-10T03:00:01Z"));

        TaskCompletion entered = run(compiled, "RECV", base.withTerminal(
                Optional.of(new BmsTerminalInput(BmsAid.ENTER, 4 * 80 + 20,
                        List.of(new BmsTerminalInput.FieldInput("CUSTNO", 1, "042")))),
                Optional.of(sent)));
        TaskCompletion cleared = run(compiled, "RECV", base.withTerminal(
                Optional.of(new BmsTerminalInput(BmsAid.CLEAR, -1, List.of())),
                Optional.of(sent)));

        assertEquals("GOT ", CodePages.DEFAULT.decode(entered.payload().commarea()));
        assertEquals("042       ", ((CicsTerminalScreen.MapScreen) entered.screen().orElseThrow())
                .snapshot().field("CUSTNO", 1).orElseThrow().data());
        assertEquals("MFAL", CodePages.DEFAULT.decode(cleared.payload().commarea()));
    }

    @Test
    @DisplayName("端末がUCTRANならRECEIVE MAPは英小文字を大文字にし、ASISなら変えない")
    void uppercasesInputForUctranTerminalsUnlessAsis() throws IOException {
        CobolCompiler compiler = compiler();
        CobolCompiler.Result plain = compiler.compile("SCREEN1.cbl", program(PROCEDURE));
        String[] asisProcedure = java.util.Arrays.stream(PROCEDURE)
                .map(line -> line.equals("         RESP(WS-RESP)") ? "         ASIS RESP(WS-RESP)" : line)
                .toArray(String[]::new);
        CobolCompiler.Result asis = compiler.compile("SCREEN1.cbl", program(asisProcedure));
        BmsScreenSnapshot sent = ((CicsTerminalScreen.MapScreen) run(plain, "MAP ").screen()
                .orElseThrow()).snapshot();
        CicsEnvironment environment = environment();
        environment.terminals().setUppercaseTranslation("T001", dev.cobolonjava.cics.CicsCvda.UCTRAN);
        CicsTaskContext task = new CicsTaskContext(new CicsTaskId("task_terminal_uctran"),
                TransId.of("TX01"), "terminal-test", Instant.parse("2026-09-10T03:00:02Z"))
                .withTerminalId(Optional.of("T001"))
                .withTerminal(Optional.of(new BmsTerminalInput(BmsAid.ENTER, 0,
                        List.of(new BmsTerminalInput.FieldInput("NAME", 1, "smith")))), Optional.of(sent));

        assertEquals("SMITH   ", ((CicsTerminalScreen.MapScreen) run(plain, "RECV", task, environment)
                .screen().orElseThrow()).snapshot().field("NAME", 1).orElseThrow().data());
        assertEquals("smith   ", ((CicsTerminalScreen.MapScreen) run(asis, "RECV", task, environment)
                .screen().orElseThrow()).snapshot().field("NAME", 1).orElseThrow().data());
    }

    @Test
    @DisplayName("SEND TEXTはmapを持たない画面を作る")
    void sendsText() throws IOException {
        TaskCompletion result = run(compiler().compile("SCREEN1.cbl", program(PROCEDURE)), "TEXT");

        assertEquals(Optional.of(new CicsTerminalScreen.TextScreen("Session ended", true, false)),
                result.screen());
    }

    @Test
    @DisplayName("端末制御のRECEIVEは書式の無い画面の入力を読み、長ければ切ってLENGERRにする (GenApp、P-200)")
    void receivesTypedInputFromAnUnformattedScreen() throws IOException {
        CobolCompiler.Result result = compiler().compile("SCREEN1.cbl", program(PROCEDURE));
        CicsEnvironment environment = environment();
        environment.terminals().setUppercaseTranslation("T001", dev.cobolonjava.cics.CicsCvda.UCTRAN);
        CicsTaskContext typed = new CicsTaskContext(new CicsTaskId("task_terminal_raw"),
                TransId.of("TX01"), "terminal-test", Instant.parse("2026-09-10T03:00:03Z"))
                .withTerminalId(Optional.of("T001"))
                .withTerminal(Optional.of(new BmsTerminalInput(BmsAid.ENTER, -1, List.of(),
                        Optional.of("TX01 abc"))), Optional.empty());

        // UCTRAN の端末なので大文字になる。INTO の残り 2 byte は元の '.' のまま
        assertEquals("GOT ", CodePages.DEFAULT.decode(
                run(result, "RAW ", typed, environment).payload().commarea()));
        environment.terminals().setUppercaseTranslation("T001", dev.cobolonjava.cics.CicsCvda.NOUCTRAN);
        // MAXLENGTH(6) で切り、LENGTH には元の長さ 8 を置く
        assertEquals("LERR", CodePages.DEFAULT.decode(
                run(result, "RAW2", typed, environment).payload().commarea()));
    }

    @Test
    @DisplayName("SEND TEXTの数字のLENGTHはFROMの先頭からその長さだけを送る")
    void sendsTheLeadingPartOfFromForANumericLength() throws IOException {
        TaskCompletion result = run(compiler().compile("SCREEN1.cbl", program(PROCEDURE)), "TXT5");

        assertEquals(Optional.of(new CicsTerminalScreen.TextScreen("Sessi", false, false)), result.screen());
    }

    @Test
    @DisplayName("端末制御のSENDはFROMを画面の先頭から並べ、キーボードを戻す (P-195)")
    void sendsTerminalData() throws IOException {
        TaskCompletion result = run(compiler().compile("SCREEN1.cbl", program(PROCEDURE)), "DATA");

        // SEND TEXT FREEKB と同じ画面になる。CTLCHAR を省いた WCC はキーボードを戻す
        assertEquals(Optional.of(new CicsTerminalScreen.TextScreen("Session ended", true, false)),
                result.screen());
    }

    @Test
    @DisplayName("SEND命令は合成規則を持たないoptionと、書き方の誤りを翻訳時に断る")
    void rejectsUnsupportedSendForms() throws IOException {
        CobolCompiler compiler = compiler();
        for (String[] rejected : List.of(
                new String[] {"EXEC CICS SEND MAP(WS-MAPN) MAPSET('SCRSET') END-EXEC",
                        "MAP data name requires FROM"},
                new String[] {"EXEC CICS SEND MAP('SCRMP') MAPONLY DATAONLY END-EXEC",
                        "mutually exclusive"},
                new String[] {"EXEC CICS SEND MAP('SCRMP') FROM(SCRMPO) ERASEAUP END-EXEC",
                        "unsupported SEND MAP option: ERASEAUP"},
                new String[] {"EXEC CICS SEND CONTROL CURSOR END-EXEC", "requires a position"},
                new String[] {"EXEC CICS SEND TEXT ERASE END-EXEC", "SEND TEXT requires FROM"},
                new String[] {"EXEC CICS SEND TEXT FROM(WS-TEXT) LENGTH(14) END-EXEC",
                        "must be 1 to the FROM data area of 13 bytes"},
                new String[] {"EXEC CICS SEND TEXT FROM(WS-TEXT) LENGTH(WS-RLEN) END-EXEC",
                        "an integer or LENGTH(LENGTH OF x)"},
                new String[] {"EXEC CICS SEND FROM(WS-TEXT) CTLCHAR(WS-RESP) END-EXEC",
                        "unsupported SEND DATA option: CTLCHAR"},
                new String[] {"EXEC CICS SEND MAP('SCRMP') FROM(WS-RESP) END-EXEC",
                        "alphanumeric or group"},
                // 72 桁を越えると END-EXEC が切れる。受けない option の診断は INTO の検査より先に出る
                new String[] {"EXEC CICS RECEIVE MAP('SCRMP') FROM(SCRMPI) END-EXEC",
                        "unsupported RECEIVE MAP option: FROM"},
                new String[] {"EXEC CICS RECEIVE MAP(WS-MAPN) MAPSET('SCRSET') END-EXEC",
                        "MAP data name requires INTO"})) {
            CobolCompiler.Result result = compiler.compile("SCREEN1.cbl",
                    program(rejected[0], "GOBACK."));

            assertFalse(result.succeeded(), rejected[0]);
            assertTrue(result.diagnostics().stream()
                            .anyMatch(diagnostic -> diagnostic.message().contains(rejected[1])),
                    rejected[0] + " " + result.diagnostics());
        }
    }
}
