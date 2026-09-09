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
 * 66 レベルの {@code RENAMES} (要件 FR-021)。
 *
 * <p>記憶域を<b>重ねない</b>。すでにある記述の一部に、別の名前と別の切り方を与えるだけ
 * である。{@code REDEFINES} と違って新しい場所を取らない。
 */
@Tag("V1")
class RenamesGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(RenamesGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static CobolCompiler.Result compile(List<String> storage, String... procedure) {
        return CobolCompiler.standard().compile(FILE,
                FixedFormatSource.program(storage, procedure));
    }

    private static String run(List<String> storage, String... procedure) {
        CobolCompiler.Result result = compile(storage, procedure);
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

    private static final List<String> RECORD = List.of(
            "01 WS-REC.",
            "    05 WS-A PIC XXX VALUE 'AAA'.",
            "    05 WS-B PIC XXX VALUE 'BBB'.",
            "    05 WS-C PIC XXX VALUE 'CCC'.",
            "66 WS-AB RENAMES WS-A THRU WS-B.",
            "66 WS-JUST-B RENAMES WS-B.");

    @Test
    @DisplayName("THRU を書けば、始まりから終わりまでが 1 つの群になる (FR-021)")
    void aThruRangeBecomesOneGroup() {
        assertEquals("AAABBB|", run(RECORD, "    DISPLAY WS-AB."));
    }

    @Test
    @DisplayName("THRU を書かなければ、その項目の別名である (FR-021)")
    void withoutThruItIsJustAnotherName() {
        assertEquals("BBB|", run(RECORD, "    DISPLAY WS-JUST-B."));
    }

    @Test
    @DisplayName("別名へ書けば、元の項目が変わる。記憶域は 1 つである (FR-021)")
    void writingThroughTheAliasChangesTheOriginal() {
        assertEquals("XXXYYY|XXX|YYY|", run(RECORD,
                "    MOVE 'XXXYYY' TO WS-AB",
                "    DISPLAY WS-AB",
                "    DISPLAY WS-A",
                "    DISPLAY WS-B."));
    }

    @Test
    @DisplayName("記述の中に無い名前は誤りとして報告する (FR-021)")
    void anItemOutsideTheRecordIsReported() {
        CobolCompiler.Result result = compile(List.of(
                "01 WS-REC.",
                "    05 WS-A PIC XXX.",
                "01 WS-OTHER PIC XXX.",
                "66 WS-BAD RENAMES WS-OTHER."),
                "    DISPLAY WS-A.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("not in the record"),
                () -> result.diagnostics().toString());
    }
}
