package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskContext;
import dev.cobolonjava.cics.CicsTaskId;
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
    @DisplayName("初期対応外のCICSオプションと動的PROGRAMはfail-closedで拒否する")
    void rejectsUnsupportedOptionsAndDynamicTargets() {
        assertRejected("EXEC CICS LINK PROGRAM(WS-PGM) COMMAREA(LK-AREA) LENGTH(4) END-EXEC",
                "PROGRAM(WS-PGM)");
        assertRejected("EXEC CICS LINK PROGRAM('CHILD') COMMAREA(LK-AREA) LENGTH(4) RESP(WS-RESP) END-EXEC",
                "unsupported EXEC CICS option");
        assertRejected("EXEC CICS RETURN TRANSID('NXT1') COMMAREA(LK-AREA) END-EXEC",
                "numeric LENGTH");
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

    private static CobolCompiler.Result compileResult(String programId, List<String> procedure) {
        List<String> source = new ArrayList<>(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. " + programId + ".",
                "DATA DIVISION.",
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
