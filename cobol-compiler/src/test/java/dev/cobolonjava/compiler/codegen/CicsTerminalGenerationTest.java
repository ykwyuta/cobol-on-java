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

    private static TaskCompletion run(CobolCompiler.Result result, String input, CicsTaskContext task) {
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
        CicsEnvironment environment = CicsEnvironment.unconfigured()
                .withMapsets(BmsMapsetCatalog.of(List.of(BmsParser.parse(BMS))));
        return new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(loader).build(), 2, environment)
                .execute(new CicsTransactionDefinition(TransId.of("TX01"), ProgramId.of("SCREEN1"),
                                Duration.ofSeconds(5), 16, 0, 0, 0, true),
                        CicsPayload.ofCommarea(CodePages.DEFAULT.encode(input)),
                        task, (action, ignored) -> { });
    }

    private static final String[] PROCEDURE = {
        "IF LK-AREA = 'RECV'",
        "    EXEC CICS RECEIVE MAP('SCRMP') MAPSET('SCRSET')",
        "         INTO(SCRMPI) RESP(WS-RESP)",
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
        "IF LK-AREA = 'TEXT'",
        "    EXEC CICS SEND TEXT FROM(WS-TEXT) ERASE FREEKB",
        "    END-EXEC",
        "    EXEC CICS RETURN END-EXEC",
        "END-IF",
        "MOVE LOW-VALUES TO SCRMPO",
        "MOVE -1 TO CUSTNOL",
        "MOVE 'HELLO' TO MESSAGEO",
        "EXEC CICS SEND MAP('SCRMP') MAPSET('SCRSET')",
        "     FROM(SCRMPO) ERASE CURSOR FREEKB",
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
    @DisplayName("SEND TEXTはmapを持たない画面を作る")
    void sendsText() throws IOException {
        TaskCompletion result = run(compiler().compile("SCREEN1.cbl", program(PROCEDURE)), "TEXT");

        assertEquals(Optional.of(new CicsTerminalScreen.TextScreen("Session ended", true, false)),
                result.screen());
    }

    @Test
    @DisplayName("SEND命令は合成規則を持たないoptionと、書き方の誤りを翻訳時に断る")
    void rejectsUnsupportedSendForms() throws IOException {
        CobolCompiler compiler = compiler();
        for (String[] rejected : List.of(
                new String[] {"EXEC CICS SEND MAP('SCRMP') ERASE END-EXEC", "requires FROM"},
                new String[] {"EXEC CICS SEND MAP('SCRMP') MAPONLY DATAONLY END-EXEC",
                        "mutually exclusive"},
                new String[] {"EXEC CICS SEND MAP('SCRMP') FROM(SCRMPO) ERASEAUP END-EXEC",
                        "unsupported SEND MAP option: ERASEAUP"},
                new String[] {"EXEC CICS SEND CONTROL CURSOR END-EXEC", "requires a position"},
                new String[] {"EXEC CICS SEND TEXT ERASE END-EXEC", "SEND TEXT requires FROM"},
                new String[] {"EXEC CICS SEND MAP('SCRMP') FROM(WS-RESP) END-EXEC",
                        "alphanumeric or group"},
                new String[] {"EXEC CICS RECEIVE MAP('SCRMP') INTO(SCRMPI) ASIS END-EXEC",
                        "unsupported RECEIVE MAP option: ASIS"},
                new String[] {"EXEC CICS RECEIVE MAP('SCRMP') END-EXEC",
                        "RECEIVE MAP requires INTO"})) {
            CobolCompiler.Result result = compiler.compile("SCREEN1.cbl",
                    program(rejected[0], "GOBACK."));

            assertFalse(result.succeeded(), rejected[0]);
            assertTrue(result.diagnostics().stream()
                            .anyMatch(diagnostic -> diagnostic.message().contains(rejected[1])),
                    rejected[0] + " " + result.diagnostics());
        }
    }
}
