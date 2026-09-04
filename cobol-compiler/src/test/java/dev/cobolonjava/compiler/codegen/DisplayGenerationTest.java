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

/** {@code DISPLAY} を翻訳して実行し、出た文字を確かめる。 */
@Tag("V1")
class DisplayGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(DisplayGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static CobolCompiler.Result compile(List<String> storage, String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.")) {
            sb.append("       ").append(line).append('\n');
        }
        for (String line : storage) {
            sb.append("       ").append(line).append('\n');
        }
        sb.append("       PROCEDURE DIVISION.\n");
        for (String line : procedure) {
            sb.append("       ").append(line).append('\n');
        }
        return CobolCompiler.standard().compile(FILE, sb.toString());
    }

    /** 翻訳して実行し、{@code DISPLAY} が出した文字を返す。改行は {@code |} に置き換える。 */
    private static String output(List<String> storage, String... procedure) {
        CobolCompiler.Result result = compile(storage, procedure);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            program.runFresh(ProgramContext.capturing(sink));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
        return sink.toString(StandardCharsets.UTF_8)
                .replace(System.lineSeparator(), "|");
    }

    @Test
    @DisplayName("DISPLAY は項目の中身を文字として出す (FR-060)")
    void displayWritesTheContentOfItsItem() {
        assertEquals("HELLO|", output(
                List.of("01 WS-A PIC X(5) VALUE 'HELLO'."), "DISPLAY WS-A."));
    }

    @Test
    @DisplayName("定数もそのまま出せる (FR-060)")
    void aLiteralCanBeDisplayed() {
        assertEquals("HELLO, WORLD|", output(List.of("01 WS-A PIC X."),
                "DISPLAY 'HELLO, WORLD'."));
    }

    @Test
    @DisplayName("被演算子を並べると続けて出る (FR-060)")
    void severalOperandsAreWrittenInSequence() {
        // 途中で行を改めない。改めると 1 つの DISPLAY が複数行になってしまう
        assertEquals("A=1|", output(
                List.of("01 WS-A PIC X VALUE '1'."), "DISPLAY 'A=' WS-A."));
    }

    @Test
    @DisplayName("WITH NO ADVANCING は行を改めない (FR-060)")
    void withNoAdvancingKeepsTheLine() {
        assertEquals("AB|", output(List.of("01 WS-A PIC X."),
                "DISPLAY 'A' WITH NO ADVANCING",
                "DISPLAY 'B'."));
    }

    @Test
    @DisplayName("COMP-3 の項目は読める形へ直して出す (FR-060)")
    void aPackedItemIsConvertedToItsDisplayForm() {
        // 記憶域のバイトをそのまま出しても読めない
        assertEquals("00123|", output(
                List.of("01 WS-N PIC 9(5) COMP-3 VALUE 123."), "DISPLAY WS-N."));
    }

    @Test
    @DisplayName("2 進項目も読める形へ直して出す (FR-060)")
    void aBinaryItemIsConvertedToo() {
        assertEquals("0300|", output(
                List.of("01 WS-N PIC 9(4) COMP VALUE 300."), "DISPLAY WS-N."));
    }

    @Test
    @DisplayName("符号は最後の桁に重ねて出る (FR-031, FR-060)")
    void theSignIsOverpunchedOnTheLastDigit() {
        // -12 は "01K" になる。K は EBCDIC の D2、すなわち負のゾーンを持つ 2 である。
        // 見た目は驚くが、参照実装の DISPLAY はこの形である
        assertEquals("01K|01K|", output(
                List.of("01 WS-P PIC S9(3) COMP-3 VALUE -12.",
                        "01 WS-D PIC S9(3) VALUE -12."),
                "DISPLAY WS-P",
                "DISPLAY WS-D."));
    }

    @Test
    @DisplayName("DISPLAY 項目はそのまま出す (FR-060)")
    void aDisplayItemIsWrittenAsItIs() {
        assertEquals("00123|", output(
                List.of("01 WS-N PIC 9(5) VALUE 123."), "DISPLAY WS-N."));
    }

    @Test
    @DisplayName("添字を書いた項目も出せる (FR-024, FR-060)")
    void aSubscriptedItemCanBeDisplayed() {
        assertEquals("B|", output(
                List.of("01 WS-I PIC 9(3) COMP VALUE 2.",
                        "01 WS-T.",
                        "   05 WS-E OCCURS 3 TIMES PIC X."),
                "MOVE 'A' TO WS-E (1) MOVE 'B' TO WS-E (2) MOVE 'C' TO WS-E (3)",
                "DISPLAY WS-E (WS-I)."));
    }

    @Test
    @DisplayName("繰り返しの中で出せる (FR-061, FR-060)")
    void displayWorksInsideALoop() {
        assertEquals("1|2|3|", output(
                List.of("01 WS-I PIC 9 VALUE 1."),
                "PERFORM UNTIL WS-I > 3",
                "    DISPLAY WS-I",
                "    ADD 1 TO WS-I",
                "END-PERFORM."));
    }

    @Test
    @DisplayName("UPON の出力先指定はまだ扱えないと報告する (FR-060)")
    void displayUponIsReportedAsUnsupported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-A PIC X."), "DISPLAY 'X' UPON SYSERR.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("UPON"),
                result.diagnostics().toString());
    }
}
