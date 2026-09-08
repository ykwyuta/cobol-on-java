package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 外から立てる切り替え ({@code UPSI}) (要件 FR-135)。
 *
 * <p>ジョブが立てたところをプログラムが読む。記憶域を見ないので、プログラムから
 * 書き換えることはできない。
 */
@Tag("V1")
class SwitchConditionTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(SwitchConditionTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static final String SOURCE = source(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. UPSITEST.",
            "ENVIRONMENT DIVISION.",
            "CONFIGURATION SECTION.",
            "SPECIAL-NAMES.",
            "    UPSI-0 IS SW-1",
            "        ON STATUS IS FIRST-ON",
            "        OFF STATUS IS FIRST-OFF",
            "    UPSI-3 IS SW-4",
            "        ON IS FOURTH-ON.",
            "DATA DIVISION.",
            "PROCEDURE DIVISION.",
            "MAIN-START.",
            "    IF FIRST-ON DISPLAY 'ON-1' END-IF",
            "    IF FIRST-OFF DISPLAY 'OFF-1' END-IF",
            "    IF FOURTH-ON DISPLAY 'ON-4' END-IF",
            "    STOP RUN.");

    private static String source(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            FixedFormatSource.append(sb, line);
        }
        return sb.toString();
    }

    /** 立てる切り替えを決めて動かす。 */
    private static String run(int... on) {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, SOURCE);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ProgramContext context = ProgramContext.capturing(sink);
        for (int index : on) {
            context.switchState(index, true);
        }
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            ((CobolProgram) type.getDeclaredConstructor().newInstance()).runFresh(context);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot load the generated program", e);
        }
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    @Test
    @DisplayName("初めはすべて切れている (FR-135)")
    void everySwitchStartsOff() {
        assertEquals("OFF-1|", run());
    }

    @Test
    @DisplayName("立てた切り替えだけが真になる (FR-135)")
    void onlyTheSwitchesThatAreSetReadAsOn() {
        assertEquals("ON-1|", run(0));
        assertEquals("OFF-1|ON-4|", run(3));
        assertEquals("ON-1|ON-4|", run(0, 3));
    }

    @Test
    @DisplayName("プログラムからも動かせる (FR-135)")
    void aProgramMayAlsoSetTheSwitch() {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SETUPSI.",
                "ENVIRONMENT DIVISION.",
                "CONFIGURATION SECTION.",
                "SPECIAL-NAMES.",
                "    UPSI-0 IS SW-1 ON STATUS IS FIRST-ON.",
                "DATA DIVISION.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    IF FIRST-ON DISPLAY 'ON' ELSE DISPLAY 'OFF' END-IF",
                "    SET SW-1 TO ON",
                "    IF FIRST-ON DISPLAY 'ON' ELSE DISPLAY 'OFF' END-IF",
                "    STOP RUN."));
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            ((CobolProgram) type.getDeclaredConstructor().newInstance())
                    .runFresh(ProgramContext.capturing(sink));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot load the generated program", e);
        }

        assertEquals("OFF|ON|",
                sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|"));
    }

    @Test
    @DisplayName("UPSI-0 から UPSI-7 のほかは誤りとして報告する (FR-135)")
    void anUnknownSwitchNameIsReported() {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. BADSWITCH.",
                "ENVIRONMENT DIVISION.",
                "CONFIGURATION SECTION.",
                "SPECIAL-NAMES.",
                "    UPSI-9 IS SW-X ON STATUS IS X-ON.",
                "DATA DIVISION.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    STOP RUN."));

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("unknown switch name"),
                () -> result.diagnostics().toString());
    }
}
