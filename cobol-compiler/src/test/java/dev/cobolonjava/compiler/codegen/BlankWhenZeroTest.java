package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@code BLANK WHEN ZERO} を数字項目に書いたときの振る舞い (要件 FR-018)。
 *
 * <p>規格は「数字項目に {@code BLANK WHEN ZERO} を書いたときは、その項目の種別を
 * <b>数字編集</b>とみなす」と決めている。つまり {@code PIC 9} でも英数字定数と
 * 比べられ、{@code VALUE} に英数字定数を書け、零を入れると空白になる。
 * NC108M がまさにこの 3 つを順に確かめている。
 */
@Tag("V1")
class BlankWhenZeroTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(BlankWhenZeroTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String run(List<String> storage, String... procedure) {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE,
                FixedFormatSource.program(storage, procedure));
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            ((CobolProgram) type.getDeclaredConstructor().newInstance())
                    .runFresh(ProgramContext.capturing(sink));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot load the generated program", e);
        }
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    @Test
    @DisplayName("零を入れると空白になる (FR-018)")
    void zeroBecomesBlank() {
        assertEquals("[     ]|", run(
                List.of("01 WS-N PIC 9(5) BLANK WHEN ZERO."),
                "    MOVE 0 TO WS-N",
                "    DISPLAY '[' WS-N ']'."));
    }

    @Test
    @DisplayName("零でなければ数字がそのまま並ぶ (FR-018)")
    void aNonZeroValueIsStillShown() {
        assertEquals("[00042]|", run(
                List.of("01 WS-N PIC 9(5) BLANK WHEN ZERO."),
                "    MOVE 42 TO WS-N",
                "    DISPLAY '[' WS-N ']'."));
    }

    @Test
    @DisplayName("英数字定数を VALUE に書ける — 種別が数字編集になるから (FR-018)")
    void aNonNumericValueIsAccepted() {
        assertEquals("[5]|", run(
                List.of("01 WS-N PIC 9 BLANK WHEN ZERO VALUE \"5\"."),
                "    DISPLAY '[' WS-N ']'."));
    }

    @Test
    @DisplayName("句の並びは自由 — PICTURE より先に書いてもよい (FR-018)")
    void theClauseMayComeBeforeThePicture() {
        assertEquals("[ ]|", run(
                List.of("01 WS-N BLANK WHEN ZERO PICTURE IS 9 VALUE \"5\"."),
                "    MOVE ZERO TO WS-N",
                "    DISPLAY '[' WS-N ']'."));
    }

    @Test
    @DisplayName("英数字の項目には書けない (FR-018)")
    void anAlphanumericItemIsRefused() {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE,
                FixedFormatSource.program(
                        List.of("01 WS-T PIC X(3) BLANK WHEN ZERO."),
                        "    DISPLAY WS-T."));
        assertTrue(result.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("BLANK WHEN ZERO requires")),
                () -> "diagnostics: " + result.diagnostics());
    }
}
