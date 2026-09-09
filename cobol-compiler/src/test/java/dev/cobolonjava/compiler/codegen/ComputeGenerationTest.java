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
 * {@code COMPUTE} を翻訳して実行し、記憶域で確かめる。
 *
 * <p>見どころは<b>中間結果の桁数</b>である (要件 5.5.1)。除算は有限桁で終わらないため、
 * どこで打ち切るかで結果が変わる。打ち切り位置は文全体から決まる。
 */
@Tag("V1")
class ComputeGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(ComputeGenerationTest.class.getClassLoader());
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

    /** 整数の受取項目 1 個。 */
    private static final List<String> INTEGER = List.of("01 WS-R PIC 9(5) VALUE 0.");

    /** 小数 2 桁の受取項目 1 個。 */
    private static final List<String> SCALED = List.of("01 WS-R PIC 9(3)V99 VALUE 0.");

    @Test
    @DisplayName("COMPUTE は式の値を受取項目へ入れる (FR-044)")
    void computeStoresTheValueOfItsExpression() {
        assertEquals("00005", run(INTEGER, "COMPUTE WS-R = 2 + 3."));
    }

    @Test
    @DisplayName("乗除は加減より先に計算する (FR-044)")
    void multiplicationBindsTighterThanAddition() {
        assertEquals("00014", run(INTEGER, "COMPUTE WS-R = 2 + 3 * 4."));
    }

    @Test
    @DisplayName("括弧は優先順位を変える (FR-044)")
    void parenthesesOverridePrecedence() {
        assertEquals("00020", run(INTEGER, "COMPUTE WS-R = (2 + 3) * 4."));
    }

    @Test
    @DisplayName("同じ優先順位は左から計算する (FR-044)")
    void operatorsOfEqualPrecedenceAssociateToTheLeft() {
        // 右から計算すると 10 - (3 - 2) = 9 になる
        assertEquals("00005", run(INTEGER, "COMPUTE WS-R = 10 - 3 - 2."));
    }

    @Test
    @DisplayName("単項の符号を書ける (FR-044)")
    void aUnaryOperatorMayLeadTheExpression() {
        assertEquals("00007", run(INTEGER, "COMPUTE WS-R = - 3 + 10."));
    }

    @Test
    @DisplayName("入れ子の括弧を書ける (FR-044)")
    void parenthesesNest() {
        assertEquals("00016", run(INTEGER, "COMPUTE WS-R = (1 + 2) * (3 + 4) - 5."));
    }

    @Test
    @DisplayName("式にはデータ項目を書ける (FR-044)")
    void dataItemsMayAppearInTheExpression() {
        assertEquals("00042" + "006" + "007", run(
                List.of("01 WS-R PIC 9(5) VALUE 0.",
                        "01 WS-A PIC 9(3) VALUE 6.",
                        "01 WS-B PIC 9(3) VALUE 7."),
                "COMPUTE WS-R = WS-A * WS-B."));
    }

    @Test
    @DisplayName("中間結果は受取項目の桁で切られない (5.5.1)")
    void anIntermediateResultIsNotLimitedByTheReceiver() {
        // 途中の 1800 は受取項目の 3 桁に収まらない。そこで切られていれば 800 - 1700 になる
        assertEquals("100", run(
                List.of("01 WS-R PIC 9(3) VALUE 0."),
                "COMPUTE WS-R = 900 + 900 - 1700."));
    }

    @Test
    @DisplayName("除算の商は dmax で打ち切る (5.5.1)")
    void aQuotientIsTruncatedAtDmax() {
        // dmax は受取項目の 2 桁。20 / 3 = 6.66... を 6.66 で打ち切る
        assertEquals("00666", run(SCALED, "COMPUTE WS-R = 20 / 3."));
    }

    @Test
    @DisplayName("ROUNDED があれば dmax が 1 増える (5.5.1)")
    void roundedAddsOneToDmax() {
        // 6.666 まで持ってから丸めるので 6.67。2 桁で打ち切ってから丸めれば 6.66 のままである
        assertEquals("00667", run(SCALED, "COMPUTE WS-R ROUNDED = 20 / 3."));
    }

    @Test
    @DisplayName("dmax は文全体から決まる (5.5.1)")
    void dmaxComesFromTheWholeStatement() {
        // 1 / 3 を 2 桁で打ち切ってから 100 を掛けるので 33.00 になる
        assertEquals("03300", run(SCALED, "COMPUTE WS-R = 1 / 3 * 100."));
    }

    @Test
    @DisplayName("式の別の場所にある小数桁が dmax を押し上げる (5.5.1)")
    void aScaledOperandElsewhereRaisesDmax() {
        // WS-A の 4 桁が dmax になる。1 / 3 を 0.3333 まで持つので 33.33 になる
        assertEquals("03333" + "00000", run(
                List.of("01 WS-R PIC 9(3)V99 VALUE 0.", "01 WS-A PIC 9V9(4) VALUE 0."),
                "COMPUTE WS-R = 1 / 3 * 100 + WS-A."));
    }

    @Test
    @DisplayName("除数の小数桁は dmax に数えない (5.5.1)")
    void aDivisorDoesNotRaiseDmax() {
        // WS-D は 4 桁だが除数なので数えない。dmax は受取項目の 2 桁のままである
        assertEquals("03300" + "30000", run(
                List.of("01 WS-R PIC 9(3)V99 VALUE 0.", "01 WS-D PIC 9V9(4) VALUE 3."),
                "COMPUTE WS-R = 1 / WS-D * 100."));
    }

    @Test
    @DisplayName("受取項目は複数書ける (FR-044)")
    void oneExpressionReachesEveryReceiver() {
        assertEquals("00005" + "00005", run(
                List.of("01 WS-A PIC 9(5) VALUE 0.", "01 WS-B PIC 9(5) VALUE 0."),
                "COMPUTE WS-A WS-B = 2 + 3."));
    }

    @Test
    @DisplayName("式は 1 度だけ評価する (FR-044)")
    void theExpressionIsEvaluatedOnlyOnce() {
        // 受取項目ごとに評価しなおすと WS-B は 3 になる
        assertEquals("002" + "002", run(
                List.of("01 WS-A PIC 9(3) VALUE 1.", "01 WS-B PIC 9(3) VALUE 0."),
                "COMPUTE WS-A WS-B = WS-A + 1."));
    }

    @Test
    @DisplayName("桁があふれたら ON SIZE ERROR へ行き受取項目は変わらない (FR-043)")
    void anOverflowRaisesSizeErrorAndLeavesTheReceiver() {
        assertEquals("007" + "1", run(
                List.of("01 WS-R PIC 9(3) VALUE 7.", "01 WS-F PIC 9 VALUE 0."),
                "COMPUTE WS-R = 999 + 999",
                "    ON SIZE ERROR MOVE 1 TO WS-F",
                "END-COMPUTE."));
    }

    @Test
    @DisplayName("あふれなければ NOT ON SIZE ERROR へ行く (FR-043)")
    void noOverflowTakesTheNotOnSizeErrorBranch() {
        assertEquals("002" + "1", run(
                List.of("01 WS-R PIC 9(3) VALUE 7.", "01 WS-F PIC 9 VALUE 0."),
                "COMPUTE WS-R = 1 + 1",
                "    NOT ON SIZE ERROR MOVE 1 TO WS-F",
                "END-COMPUTE."));
    }

    @Test
    @DisplayName("式の中の 0 除算も SIZE ERROR になる (FR-043)")
    void aDivisionByZeroInsideTheExpressionRaisesSizeError() {
        // 0 除算は式のどこにでも現れうる。割る前に除数を調べる形は取れない
        assertEquals("007" + "1" + "0", run(
                List.of("01 WS-R PIC 9(3) VALUE 7.",
                        "01 WS-F PIC 9 VALUE 0.",
                        "01 WS-Z PIC 9 VALUE 0."),
                "COMPUTE WS-R = 10 / WS-Z",
                "    ON SIZE ERROR MOVE 1 TO WS-F",
                "END-COMPUTE."));
    }

    @Test
    @DisplayName("整数のべき乗は正確に出る (FR-047)")
    void anIntegerPowerIsExact() {
        assertEquals("00008", run(INTEGER, "COMPUTE WS-R = 2 ** 3."));
        // べき乗は右から結ぶ。2 ** (3 ** 2) = 512
        assertEquals("00512", run(INTEGER, "COMPUTE WS-R = 2 ** 3 ** 2."));
        // 0 乗は 1 である
        assertEquals("00001", run(INTEGER, "COMPUTE WS-R = 7 ** 0."));
    }

    @Test
    @DisplayName("べき乗は掛け算より先に結ばれる (FR-047)")
    void powerBindsTighterThanMultiply() {
        assertEquals("00018", run(INTEGER, "COMPUTE WS-R = 2 * 3 ** 2."));
    }

    @Test
    @DisplayName("負のべきは逆数になる (FR-047)")
    void aNegativePowerIsTheReciprocal() {
        assertEquals("00025", run(SCALED, "COMPUTE WS-R = 2 ** -2."));
    }

    @Test
    @DisplayName("小数のべきは近似で出る (FR-047)")
    void aFractionalPowerIsApproximate() {
        // 9 の 0.5 乗は 3 である。対数を通るので答えに近似が入る
        assertEquals("00300", run(SCALED, "COMPUTE WS-R = 9 ** 0.5."));
    }

    @Test
    @DisplayName("英数字の受取項目は誤りとして報告する (FR-044)")
    void anAlphanumericReceiverIsReported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-R PIC X(5)."), "COMPUTE WS-R = 1 + 1.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message()
                        .contains("numeric or numeric-edited receiver"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("COMPUTE の受取項目は数字編集項目でよい (FR-041, FR-044)")
    void theReceiverMayBeNumericEdited() {
        assertEquals(" 12.35", run(
                List.of("01 WS-R PIC ZZ9.99."), "COMPUTE WS-R ROUNDED = 24.69 / 2."));
    }
}
