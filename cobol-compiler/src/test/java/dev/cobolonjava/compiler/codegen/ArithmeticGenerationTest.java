package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 算術文を翻訳して実行し、結果をゾーン 10 進の綴りで確かめる。
 *
 * <p>期待値は EBCDIC のバイト列を復号した文字列で書く。{@code PIC 9(3)V99} なら
 * {@code "01250"} のように、小数点を含まない格納された数字の並びになる。
 */
@Tag("V1")
class ArithmeticGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(ArithmeticGenerationTest.class.getClassLoader());
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

    /** 翻訳して実行し、記憶域の全体を EBCDIC から復号した文字列で返す。 */
    private static String run(List<String> storage, String... procedure) {
        CobolCompiler.Result result = compile(storage, procedure);
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

    private static final List<String> THREE = List.of(
            "01 WS-A PIC 9(3) VALUE 100.",
            "01 WS-B PIC 9(3) VALUE 020.",
            "01 WS-C PIC 9(3) VALUE 003.");

    @Test
    @DisplayName("ADD ... TO は受取項目に足し込む (FR-043)")
    void addToAccumulatesIntoItsReceiver() {
        assertEquals("100020123", run(THREE, "ADD WS-A WS-B TO WS-C."));
    }

    @Test
    @DisplayName("ADD ... GIVING は受取項目を置き換える (FR-043)")
    void addGivingReplacesItsReceiver() {
        assertEquals("100020120", run(THREE, "ADD WS-A WS-B GIVING WS-C."));
    }

    @Test
    @DisplayName("ADD ... TO ... GIVING では TO のあとも被演算子になる (FR-043)")
    void addToGivingTreatsTheToOperandAsAnAddend() {
        // WS-C = WS-A + WS-B。TO のあとの WS-B は受取項目ではない
        assertEquals("100020120", run(THREE, "ADD WS-A TO WS-B GIVING WS-C."));
    }

    @Test
    @DisplayName("SUBTRACT ... FROM は受取項目から引く (FR-043)")
    void subtractFromTakesItsReceiverAsTheMinuend() {
        // WS-A = WS-A - WS-B = 80
        assertEquals("080020003", run(THREE, "SUBTRACT WS-B FROM WS-A."));
    }

    @Test
    @DisplayName("SUBTRACT ... FROM ... GIVING は引かれる側を先に置く (FR-043)")
    void subtractGivingKeepsTheMinuendFirst() {
        // WS-C = WS-A - WS-B
        assertEquals("100020080", run(THREE, "SUBTRACT WS-B FROM WS-A GIVING WS-C."));
    }

    @Test
    @DisplayName("MULTIPLY ... BY は受取項目に掛ける (FR-043)")
    void multiplyByScalesItsReceiver() {
        assertEquals("100060003", run(THREE, "MULTIPLY WS-C BY WS-B."));
    }

    @Test
    @DisplayName("DIVIDE ... INTO は受取項目を割る (FR-043)")
    void divideIntoDividesItsReceiver() {
        // WS-B = WS-B / WS-C = 20 / 3 = 6 (切り捨て)
        assertEquals("100006003", run(THREE, "DIVIDE WS-C INTO WS-B."));
    }

    @Test
    @DisplayName("DIVIDE ... BY ... GIVING は書いた順に割る (FR-043)")
    void divideByFollowsTheWrittenOrder() {
        // WS-C = WS-A / WS-B = 100 / 20 = 5
        assertEquals("100020005", run(THREE, "DIVIDE WS-A BY WS-B GIVING WS-C."));
    }

    @Test
    @DisplayName("商の桁数は受取項目の小数部に合わせる (FR-043)")
    void theQuotientTakesTheScaleOfItsReceiver() {
        // 10 / 3 を PIC 9V99 へ入れると 3.33
        assertEquals("010003333", run(
                List.of("01 WS-A PIC 9(3) VALUE 010.",
                        "01 WS-B PIC 9(3) VALUE 003.",
                        "01 WS-C PIC 9V99."),
                "DIVIDE WS-A BY WS-B GIVING WS-C."));
    }

    @Test
    @DisplayName("ROUNDED は切り捨てではなく丸める (FR-044)")
    void roundedRoundsInsteadOfTruncating() {
        // 20 / 3 = 6.666... 切り捨てなら 6.66、丸めれば 6.67
        assertEquals("020003666", run(
                List.of("01 WS-A PIC 9(3) VALUE 020.",
                        "01 WS-B PIC 9(3) VALUE 003.",
                        "01 WS-C PIC 9V99."),
                "DIVIDE WS-A BY WS-B GIVING WS-C."));
        assertEquals("020003667", run(
                List.of("01 WS-A PIC 9(3) VALUE 020.",
                        "01 WS-B PIC 9(3) VALUE 003.",
                        "01 WS-C PIC 9V99."),
                "DIVIDE WS-A BY WS-B GIVING WS-C ROUNDED."));
    }

    @Test
    @DisplayName("受取項目ごとに ROUNDED を書き分けられる (FR-044)")
    void eachReceiverHasItsOwnRounding() {
        // WS-C は切り捨て、WS-D だけが丸められる
        assertEquals("020003666667", run(
                List.of("01 WS-A PIC 9(3) VALUE 020.",
                        "01 WS-B PIC 9(3) VALUE 003.",
                        "01 WS-C PIC 9V99.",
                        "01 WS-D PIC 9V99."),
                "DIVIDE WS-A BY WS-B GIVING WS-C WS-D ROUNDED."));
    }

    @Test
    @DisplayName("定数を被演算子にできる (FR-043)")
    void aLiteralCanBeAnOperand() {
        assertEquals("100020008", run(THREE, "ADD 5 TO WS-C."));
    }

    @Test
    @DisplayName("桁あふれは黙って切り捨てられる (FR-043)")
    void anOverflowingResultIsTruncated() {
        // 900 + 200 = 1100。PIC 9(3) には 100 だけが残る
        assertEquals("100", run(List.of("01 WS-A PIC 9(3) VALUE 900."), "ADD 200 TO WS-A."));
    }

    @Test
    @DisplayName("GIVING の受取項目は数字編集項目でよい (FR-041, FR-043)")
    void aGivingReceiverMayBeNumericEdited() {
        assertEquals("007  8", run(
                List.of("01 WS-A PIC 9(3) VALUE 007.", "01 WS-E PIC ZZ9."),
                "ADD WS-A 1 GIVING WS-E."));
    }

    @Test
    @DisplayName("数字編集項目への格納でも ROUNDED は効く (FR-041, FR-045)")
    void roundingHappensBeforeTheEditing() {
        // 24.68 を小数 1 桁へ丸めてから絵に当てはめる。切り捨てなら 24.6 になる
        assertEquals("01234 24.7", run(
                List.of("01 WS-A PIC 9(3)V99 VALUE 012.34.", "01 WS-E PIC ZZ9.9."),
                "MULTIPLY WS-A BY 2 GIVING WS-E ROUNDED."));
    }

    @Test
    @DisplayName("REMAINDER の受取項目も数字編集項目でよい (FR-041, FR-044)")
    void theRemainderReceiverMayBeNumericEdited() {
        // 174 / 16 = 10 あまり 14。剰余は切り捨てた商から求める
        assertEquals(" 10 14", run(
                List.of("01 WS-Q PIC ZZ9.", "01 WS-R PIC ZZ9."),
                "DIVIDE 16 INTO 174 GIVING WS-Q REMAINDER WS-R."));
    }

    @Test
    @DisplayName("GIVING を書かない受取項目に数字編集項目は書けない (FR-043)")
    void aReceiverThatJoinsTheComputationMustBeNumeric() {
        // 受取項目が計算に加わる形である。編集した文字列を読み戻して足すことはできない
        CobolCompiler.Result result = compile(
                List.of("01 WS-E PIC ZZ9."), "ADD 1 TO WS-E.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message()
                        .contains("requires a numeric receiver"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("数字編集項目でも ON SIZE ERROR は受取項目を変えない (FR-041, FR-043)")
    void aSizeErrorLeavesAnEditedReceiverAlone() {
        assertEquals("   X", run(
                List.of("01 WS-E PIC ZZ9.", "01 WS-F PIC X."),
                "ADD 900 500 GIVING WS-E",
                "    ON SIZE ERROR MOVE 'X' TO WS-F",
                "END-ADD."));
    }

    @Test
    @DisplayName("数値でない受取項目は誤りとして報告する (FR-043)")
    void aNonNumericReceiverIsReported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-A PIC 9(3) VALUE 100.", "01 WS-T PIC X(3)."),
                "ADD WS-A TO WS-T.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("numeric receiver"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("定数を受取項目にはできない (FR-043)")
    void aLiteralCannotReceiveTheResult() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-A PIC 9(3) VALUE 100."), "ADD WS-A TO 5.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("cannot receive"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("DIVIDE ... BY には GIVING が要る (FR-043)")
    void divideByRequiresGiving() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-A PIC 9(3) VALUE 100.", "01 WS-B PIC 9(3) VALUE 020."),
                "DIVIDE WS-A BY WS-B.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("requires GIVING"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("被演算子は文の実行前に 1 度だけ読む (FR-043、規格 6.11.4 GR2)")
    void theOperandsAreReadOnceBeforeAnyReceiverIsStored() {
        // 2 つ目の受取項目が被演算子 WS-A を書き換える。3 つ目以降が書き換えた
        // あとの値を読むと、別の計算になってしまう。
        // WS-A=100 WS-B=020 なので、どの受取項目も 100 / 20 = 5 である
        assertEquals("005020005005", run(
                List.of("01 WS-A PIC 9(3) VALUE 100.",
                        "01 WS-B PIC 9(3) VALUE 020.",
                        "01 WS-D PIC 9(3) VALUE 000.",
                        "01 WS-E PIC 9(3) VALUE 000."),
                "DIVIDE WS-B INTO WS-A GIVING WS-D WS-A WS-E."));
    }

    @Test
    @DisplayName("ON SIZE ERROR つきでも被演算子は 1 度だけ読む (FR-041, FR-043)")
    void theOperandsAreReadOnceWithASizeErrorPhraseToo() {
        // 受取項目 WS-A を書き換えたあとの値で計算し直すと、あふれない計算まで
        // あふれたことにしてしまう (NC172A の「WRONGLY AFFECTED BY SIZE ERROR」)
        assertEquals("005020005005N", run(
                List.of("01 WS-A PIC 9(3) VALUE 100.",
                        "01 WS-B PIC 9(3) VALUE 020.",
                        "01 WS-D PIC 9(3) VALUE 000.",
                        "01 WS-E PIC 9(3) VALUE 000.",
                        "01 WS-F PIC X VALUE 'N'."),
                "DIVIDE WS-B INTO WS-A GIVING WS-D WS-A WS-E",
                "    ON SIZE ERROR MOVE 'Y' TO WS-F."));
    }
}
