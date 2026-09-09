package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.procedure.ProcedureDecision;
import dev.cobolonjava.runtime.procedure.ProcedureOutcome;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.program.ProgramNotFoundException;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** SECTION hookがCOBOL制御フローを広げずに作用することを固定する。 */
@Tag("V1")
class ProcedureHookGenerationTest {

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(ProcedureHookGenerationTest.class.getClassLoader());
        }

        private Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    @Test
    @DisplayName("明示的なPERFORM SECTIONだけをMockで置換する")
    void replacesAnExplicitSectionPerform() {
        CobolProgram program = compile(
                "MAIN-START.",
                "    PERFORM MOCKED",
                "    PERFORM REAL",
                "    GOBACK.",
                "MOCKED SECTION.",
                "MOCKED-P.",
                "    ADD 1 TO WS-N.",
                "REAL SECTION.",
                "REAL-P.",
                "    ADD 10 TO WS-N.");
        List<String> events = new ArrayList<>();
        ProgramContext context = ProgramContext.capturing(new java.io.ByteArrayOutputStream())
                .withProcedureHook(new dev.cobolonjava.runtime.procedure.ProcedureHook() {
                    @Override
                    public ProcedureDecision before(
                            dev.cobolonjava.runtime.procedure.ProcedureInvocation invocation) {
                        events.add("before:" + invocation.procedureId().name());
                        if (invocation.procedureId().name().equals("MOCKED")) {
                            invocation.workingStorage().view(0, 3)
                                    .setBytes(invocation.codePage().encode("100"));
                            return ProcedureDecision.RETURN;
                        }
                        return ProcedureDecision.PROCEED;
                    }

                    @Override
                    public void after(
                            dev.cobolonjava.runtime.procedure.ProcedureInvocation invocation,
                            ProcedureOutcome outcome) {
                        events.add("after:" + invocation.procedureId().name() + ":" + outcome);
                    }
                });

        Storage result = program.runFresh(context);

        assertEquals("110", CodePages.DEFAULT.decode(result.array()));
        assertEquals(List.of(
                "before:MOCKED", "after:MOCKED:MOCK_RETURN",
                "before:REAL", "after:REAL:REAL_RETURN"), events);
    }

    @Test
    @DisplayName("段落PERFORM、THRU、GO TO、fall-throughはSECTION hookを通らない")
    void doesNotBroadenTheHookToOtherControlTransfers() {
        assertEquals("001:0", runCountingHooks(
                "MAIN-START.",
                "    PERFORM PARA",
                "    GOBACK.",
                "PARA.",
                "    ADD 1 TO WS-N."));
        assertEquals("001:0", runCountingHooks(
                "MAIN-START.",
                "    PERFORM TARGET THRU TARGET",
                "    GOBACK.",
                "TARGET SECTION.",
                "TARGET-P.",
                "    ADD 1 TO WS-N."));
        assertEquals("001:0", runCountingHooks(
                "MAIN-START.",
                "    GO TO TARGET.",
                "TARGET SECTION.",
                "TARGET-P.",
                "    ADD 1 TO WS-N",
                "    GOBACK."));
        assertEquals("001:0", runCountingHooks(
                "MAIN-START.",
                "    CONTINUE.",
                "TARGET SECTION.",
                "TARGET-P.",
                "    ADD 1 TO WS-N",
                "    GOBACK."));
    }

    @Test
    @DisplayName("SECTIONから制御例外で抜けてもafter hookへ通知する")
    void reportsControlExceptionsToTheAfterHook() {
        CobolProgram program = compile(
                "MAIN-START.",
                "    PERFORM END-TASK",
                "    GOBACK.",
                "END-TASK SECTION.",
                "END-P.",
                "    STOP RUN.");
        List<ProcedureOutcome> outcomes = new ArrayList<>();
        ProgramContext context = ProgramContext.standard().withProcedureHook(new
                dev.cobolonjava.runtime.procedure.ProcedureHook() {
                    @Override
                    public ProcedureDecision before(
                            dev.cobolonjava.runtime.procedure.ProcedureInvocation invocation) {
                        return ProcedureDecision.PROCEED;
                    }

                    @Override
                    public void after(
                            dev.cobolonjava.runtime.procedure.ProcedureInvocation invocation,
                            ProcedureOutcome outcome) {
                        outcomes.add(outcome);
                    }
                });

        program.runFresh(context);

        assertEquals(List.of(ProcedureOutcome.STOP_RUN), outcomes);
    }

    @Test
    @DisplayName("after hookの失敗は元のCOBOL実行障害を置き換えない")
    void preservesThePrimaryFailureWhenTheAfterHookAlsoFails() {
        CobolProgram program = compile(
                "MAIN-START.",
                "    PERFORM FAILING",
                "    GOBACK.",
                "FAILING SECTION.",
                "FAIL-P.",
                "    CALL 'NO-SUCH-PROGRAM'.");
        ProgramContext context = ProgramContext.standard().withProcedureHook(new
                dev.cobolonjava.runtime.procedure.ProcedureHook() {
                    @Override
                    public ProcedureDecision before(
                            dev.cobolonjava.runtime.procedure.ProcedureInvocation invocation) {
                        return ProcedureDecision.PROCEED;
                    }

                    @Override
                    public void after(
                            dev.cobolonjava.runtime.procedure.ProcedureInvocation invocation,
                            ProcedureOutcome outcome) {
                        throw new IllegalStateException("after hook failed");
                    }
                });

        ProgramNotFoundException failure = assertThrows(ProgramNotFoundException.class,
                () -> program.runFresh(context));

        assertEquals(1, failure.getSuppressed().length);
        assertEquals("after hook failed", failure.getSuppressed()[0].getMessage());
    }

    private static String runCountingHooks(String... procedure) {
        CobolProgram program = compile(procedure);
        int[] calls = {0};
        ProgramContext context = ProgramContext.standard().withProcedureHook(invocation -> {
            calls[0]++;
            return ProcedureDecision.PROCEED;
        });
        Storage result = program.runFresh(context);
        return CodePages.DEFAULT.decode(result.array()) + ":" + calls[0];
    }

    private static CobolProgram compile(String... procedure) {
        StringBuilder source = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HOOKPGM.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-N PIC 9(3) VALUE 0.",
                "PROCEDURE DIVISION.")) {
            source.append("       ").append(line).append('\n');
        }
        for (String line : procedure) {
            source.append("       ").append(line).append('\n');
        }
        CobolCompiler.Result result = CobolCompiler.standard()
                .compile("HOOKPGM.cbl", source.toString());
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            return (CobolProgram) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot load generated program", e);
        }
    }
}
