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
 * 数字編集項目から数値項目への転記 (要件 FR-060、de-editing)。
 *
 * <p>編集は「値 → 見せ方」の変換である。それを<b>逆にたどる</b>。通貨記号もコンマも
 * 空白も値には関わらない。符号は {@code CR} / {@code DB} / {@code -} が表す。
 */
@Tag("V1")
class DeEditGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(DeEditGenerationTest.class.getClassLoader());
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
    @DisplayName("編集した文字の並びから値を取り戻す (FR-060)")
    void anEditedItemGivesBackItsValue() {
        assertEquals("-0123.45|", run(
                List.of("01 WS-EDIT PIC $(4)9.99CR.",
                        "01 WS-NUM  PIC S9(4)V99.",
                        "01 WS-SHOW PIC -9(4).9(2)."),
                "    MOVE -123.45 TO WS-EDIT",
                "    MOVE WS-EDIT TO WS-NUM",
                "    MOVE WS-NUM TO WS-SHOW",
                "    DISPLAY WS-SHOW."));
    }

    @Test
    @DisplayName("コンマも通貨記号も値には関わらない (FR-060)")
    void separatorsAndCurrencyDoNotChangeTheValue() {
        assertEquals("1234567|", run(
                List.of("01 WS-EDIT PIC $Z,ZZZ,ZZ9.",
                        "01 WS-NUM  PIC 9(7)."),
                "    MOVE 1234567 TO WS-EDIT",
                "    MOVE WS-EDIT TO WS-NUM",
                "    DISPLAY WS-NUM."));
    }

    @Test
    @DisplayName("空白だけなら 0 である (FR-060)")
    void anAllBlankEditedItemIsZero() {
        assertEquals("000|", run(
                List.of("01 WS-EDIT PIC ZZ9 BLANK WHEN ZERO.",
                        "01 WS-NUM  PIC 9(3)."),
                "    MOVE 0 TO WS-EDIT",
                "    MOVE WS-EDIT TO WS-NUM",
                "    DISPLAY WS-NUM."));
    }

    @Test
    @DisplayName("数字だけの英数字定数は符号なしの整数として読む (FR-060)")
    void anAllDigitLiteralMovesIntoANumericItem() {
        assertEquals("12345|", run(
                List.of("01 WS-NUM PIC 9(5)."),
                "    MOVE '12345' TO WS-NUM",
                "    DISPLAY WS-NUM."));
    }
}
