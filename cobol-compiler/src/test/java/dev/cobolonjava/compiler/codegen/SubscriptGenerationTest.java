package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 実行時に決まる添字を翻訳して実行し、書き込まれた位置を確かめる。 */
@Tag("V1")
class SubscriptGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(SubscriptGenerationTest.class.getClassLoader());
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

    private static String run(List<String> storage, String... procedure) {
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
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, sb.toString());
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            Storage executed = program.runFresh();
            return CodePages.DEFAULT.decode(executed.array());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
    }

    private static final List<String> TABLE = List.of(
            "01 WS-I PIC 9(3) COMP VALUE 1.",
            "01 WS-T.",
            "   05 WS-E OCCURS 5 TIMES PIC X VALUE '-'.");

    @Test
    @DisplayName("データ項目で書いた添字が位置を決める (FR-024)")
    void aVariableSubscriptDecidesTheOffset() {
        assertEquals("---X-", run(TABLE,
                "MOVE 4 TO WS-I",
                "MOVE 'X' TO WS-E (WS-I).").substring(2));
    }

    @Test
    @DisplayName("添字を変えながら表を埋められる (FR-024)")
    void aLoopCanFillTheWholeTable() {
        // 表を走査する、いちばん普通の書き方
        assertEquals("XXXXX", run(TABLE,
                "MOVE 1 TO WS-I",
                "PERFORM UNTIL WS-I > 5",
                "    MOVE 'X' TO WS-E (WS-I)",
                "    ADD 1 TO WS-I",
                "END-PERFORM.").substring(2));
    }

    @Test
    @DisplayName("添字は送出側にも書ける (FR-024)")
    void aVariableSubscriptWorksOnTheSendingSide() {
        assertEquals("ABCDEC", run(
                List.of("01 WS-I PIC 9(3) COMP VALUE 3.",
                        "01 WS-T.",
                        "   05 WS-E OCCURS 5 TIMES PIC X.",
                        "01 WS-OUT PIC X."),
                "MOVE 'A' TO WS-E (1) MOVE 'B' TO WS-E (2) MOVE 'C' TO WS-E (3)",
                "MOVE 'D' TO WS-E (4) MOVE 'E' TO WS-E (5)",
                "MOVE WS-E (WS-I) TO WS-OUT.").substring(2));
    }

    @Test
    @DisplayName("多次元の表でも位置が決まる (FR-024)")
    void aTwoDimensionalTableIsIndexedToo() {
        // 2 行 3 列。(2, 3) は 6 番目の要素になる
        assertEquals("-----X", run(
                List.of("01 WS-I PIC 9(3) COMP VALUE 2.",
                        "01 WS-J PIC 9(3) COMP VALUE 3.",
                        "01 WS-T.",
                        "   05 WS-ROW OCCURS 2 TIMES.",
                        "      10 WS-CELL OCCURS 3 TIMES PIC X VALUE '-'."),
                "MOVE 'X' TO WS-CELL (WS-I, WS-J).").substring(4));
    }

    @Test
    @DisplayName("添字に相対指定を書ける (FR-024, FR-025)")
    void aSubscriptMayBeRelative() {
        assertEquals("---X-", run(TABLE,
                "MOVE 3 TO WS-I",
                "MOVE 'X' TO WS-E (WS-I + 1).").substring(2));
        assertEquals("-X---", run(TABLE,
                "MOVE 3 TO WS-I",
                "MOVE 'X' TO WS-E (WS-I - 1).").substring(2));
    }

    @Test
    @DisplayName("符号を数字にくっつけて書いてもよい (FR-024, FR-025)")
    void theSignMayBeAttachedToTheNumber() {
        // COBOL では単項の符号は後ろに空白を置かない。字句の切れ目が変わるだけで
        // 意味は同じである
        assertEquals("---X-", run(TABLE,
                "MOVE 3 TO WS-I",
                "MOVE 'X' TO WS-E (WS-I +1).").substring(2));
    }

    @Test
    @DisplayName("相対指定でも範囲は確かめる (FR-024, FR-140)")
    void arelativeSubscriptIsRangeCheckedToo() {
        // 確かめるのは<b>足したあとの値</b>である
        CobolCompiler.Result result = compile(TABLE,
                "MOVE 5 TO WS-I",
                "MOVE 'X' TO WS-E (WS-I + 1).");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
    }

    @Test
    @DisplayName("定数と変数の添字を混ぜられる (FR-024)")
    void constantAndVariableSubscriptsMix() {
        assertEquals("---X--", run(
                List.of("01 WS-J PIC 9(3) COMP VALUE 1.",
                        "01 WS-T.",
                        "   05 WS-ROW OCCURS 2 TIMES.",
                        "      10 WS-CELL OCCURS 3 TIMES PIC X VALUE '-'."),
                "MOVE 'X' TO WS-CELL (2, WS-J).").substring(2));
    }

    @Test
    @DisplayName("算術文の受取項目にも添字を書ける (FR-024, FR-043)")
    void anArithmeticReceiverMayBeSubscripted() {
        assertEquals("003005", run(
                List.of("01 WS-I PIC 9(3) COMP VALUE 2.",
                        "01 WS-T.",
                        "   05 WS-N OCCURS 2 TIMES PIC 9(3) VALUE 3."),
                "ADD 2 TO WS-N (WS-I).").substring(2));
    }

    @Test
    @DisplayName("部分参照の開始位置も実行時に決められる (FR-026)")
    void aReferenceModificationStartMayBeVariable() {
        assertEquals("--XY-", run(
                List.of("01 WS-I PIC 9(3) COMP VALUE 3.",
                        "01 WS-A PIC X(5) VALUE ALL '-'."),
                "MOVE 'XY' TO WS-A (WS-I:2).").substring(2));
    }

    @Test
    @DisplayName("条件の中でも添字が使える (FR-024, FR-046)")
    void aSubscriptWorksInsideACondition() {
        assertEquals("T", run(
                List.of("01 WS-I PIC 9(3) COMP VALUE 2.",
                        "01 WS-T.",
                        "   05 WS-E OCCURS 3 TIMES PIC X VALUE 'B'.",
                        "01 WS-R PIC X."),
                "IF WS-E (WS-I) = 'B'",
                "    MOVE 'T' TO WS-R",
                "ELSE",
                "    MOVE 'F' TO WS-R",
                "END-IF.").substring(5));
    }
}
