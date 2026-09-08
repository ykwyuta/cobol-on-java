package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.Storage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 組み込み関数を翻訳して実行し、記憶域の中身で確かめる (要件 FR-070)。
 *
 * <p>ここに並ぶのは<b>値が一意に決まる</b>関数だけである。三角関数や対数は結果の桁数が
 * 処理系の決めごとであり、まだ実装していない (暫定判断 P-065)。
 */
@Tag("V1")
class IntrinsicFunctionTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(IntrinsicFunctionTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static CobolCompiler.Result compile(List<String> storage, String... procedure) {
        return CobolCompiler.standard().compile(FILE,
                FixedFormatSource.program(storage, procedure));
    }

    /** 時計を固定する。実行のたびに変わる値は試験に書けない (要件 FR-204)。 */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-04T13:45:07.890Z"), ZoneOffset.UTC);

    private static String run(List<String> storage, String... procedure) {
        return run(ProgramContext.standard().withClock(FIXED), storage, procedure);
    }

    private static String run(ProgramContext context, List<String> storage,
                              String... procedure) {
        CobolCompiler.Result result = compile(storage, procedure);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            Storage executed = program.runFresh(context);
            return CodePages.DEFAULT.decode(executed.array());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
    }

    /**
     * 数値を返す関数を 1 つ流し、受取項目の綴りを返す。
     *
     * <p>受取項目は<b>符号を分けて持つ</b>。ゾーン 10 進は末尾の桁に符号を混ぜるので、
     * そのままでは期待値が {@code 00040{} のようになって読めない。
     */
    private static String computed(String expression) {
        return run(List.of("01 WS-N PIC S9(6)V99 SIGN IS LEADING SEPARATE VALUE 0."),
                "COMPUTE WS-N = " + expression + ".");
    }

    /** 日付は 8 桁になるので、受取項目を広げて流す。 */
    private static String date(String expression) {
        return run(List.of("01 WS-D PIC S9(9) SIGN IS LEADING SEPARATE VALUE 0."),
                "COMPUTE WS-D = " + expression + ".");
    }

    /** 8 バイトの英数字項目 1 個だけを持つプログラムを流す。 */
    private static String moved(String source) {
        return run(List.of("01 WS-A PIC X(8) VALUE SPACES."), "MOVE " + source + " TO WS-A.");
    }

    @Test
    @DisplayName("LENGTH は項目の文字位置の数である (FR-070)")
    void lengthCountsCharacterPositions() {
        assertEquals("+00000400", computed("FUNCTION LENGTH(\"ABCD\")"));
        assertEquals("       +00000700",
                run(List.of("01 WS-X PIC X(7).", "01 WS-N PIC S9(6)V99 SIGN IS LEADING SEPARATE VALUE 0."),
                        "COMPUTE WS-N = FUNCTION LENGTH(WS-X)."));
    }

    @Test
    @DisplayName("UPPER-CASE と LOWER-CASE は英字だけを写す (FR-070)")
    void caseFunctionsMapLettersOnly() {
        assertEquals("GIZZARD ", moved("FUNCTION UPPER-CASE(\"giZZard\")"));
        assertEquals("gizzard ", moved("FUNCTION LOWER-CASE(\"giZZard\")"));
        assertEquals("A1-B2   ", moved("FUNCTION UPPER-CASE(\"a1-b2\")"),
                "英字でない文字はそのまま残る");
    }

    @Test
    @DisplayName("関数は入れ子にできる (FR-070)")
    void functionsNest() {
        assertEquals("GIZZARD ", moved("FUNCTION UPPER-CASE(FUNCTION UPPER-CASE(\"giZZard\"))"));
    }

    @Test
    @DisplayName("REVERSE はバイトの並びを逆にする (FR-070)")
    void reverseTurnsTheBytesAround() {
        assertEquals("erugif  ", moved("FUNCTION REVERSE(\"figure\")"));
    }

    @Test
    @DisplayName("CHAR と ORD は照合順序の位置で対になる (FR-070)")
    void charAndOrdAreInverses() {
        // EBCDIC の "A" は 0xC1 なので、照合順序では 194 番目である
        assertEquals("+00019400", computed("FUNCTION ORD(\"A\")"));
        assertEquals("A       ", moved("FUNCTION CHAR(194)"));
    }

    @Test
    @DisplayName("MAX / MIN / SUM / RANGE は引数をいくつでも取る (FR-070)")
    void foldingFunctionsTakeAnyNumberOfArguments() {
        assertEquals("+00230400", computed("FUNCTION MAX(-4, 7, 2304, 3, -8)"));
        assertEquals("-00000800", computed("FUNCTION MIN(-4, 7, 2304, 3, -8)"));
        assertEquals("+00001000", computed("FUNCTION SUM(1, 2, 3, 4)"));
        assertEquals("+00231200", computed("FUNCTION RANGE(-4, 7, 2304, 3, -8)"));
    }

    @Test
    @DisplayName("ORD-MAX と ORD-MIN は何番目かを返す (FR-070)")
    void ordinalFunctionsReturnThePosition() {
        assertEquals("+00000300", computed("FUNCTION ORD-MAX(-4, 7, 2304, 3)"));
        assertEquals("+00000400", computed("FUNCTION ORD-MIN(4, 1, 9, -3)"));
    }

    @Test
    @DisplayName("INTEGER は超えない最大の整数、INTEGER-PART は 0 の側へ切る (FR-070)")
    void theTwoIntegerFunctionsDifferOnNegatives() {
        assertEquals("+00000100", computed("FUNCTION INTEGER(1.5)"));
        assertEquals("-00000200", computed("FUNCTION INTEGER(-1.5)"));
        assertEquals("+00000100", computed("FUNCTION INTEGER-PART(1.5)"));
        assertEquals("-00000100", computed("FUNCTION INTEGER-PART(-1.5)"));
    }

    @Test
    @DisplayName("MOD の符号は除数に、REM の符号は被除数に従う (FR-070)")
    void modAndRemDifferInSign() {
        assertEquals("+00000400", computed("FUNCTION MOD(-11, 5)"));
        assertEquals("-00000100", computed("FUNCTION REM(-11, 5)"));
        assertEquals("-00000400", computed("FUNCTION MOD(11, -5)"));
        assertEquals("+00000100", computed("FUNCTION REM(11, -5)"));
    }

    @Test
    @DisplayName("FACTORIAL は階乗である (FR-070)")
    void factorialMultipliesDownward() {
        assertEquals("+00000100", computed("FUNCTION FACTORIAL(0)"));
        assertEquals("+00072000", computed("FUNCTION FACTORIAL(6)"));
    }

    @Test
    @DisplayName("NUMVAL は数字の綴りを読む (FR-070)")
    void numvalReadsDigits() {
        assertEquals("+00012345", computed("FUNCTION NUMVAL(\"  123.45\")"));
        assertEquals("-00001230", computed("FUNCTION NUMVAL(\"-12.3\")"));
        assertEquals("-00001230", computed("FUNCTION NUMVAL(\"12.3CR\")"));
    }

    @Test
    @DisplayName("NUMVAL-C は通貨記号と桁区切りを落とす (FR-070)")
    void numvalCDropsTheCurrencySign() {
        assertEquals("+09302100", computed("FUNCTION NUMVAL-C(\"$93,021\", \"$\")"));
        assertEquals("+00000500", computed("FUNCTION NUMVAL-C(\"$5\", \"$\")"));
    }

    @Test
    @DisplayName("引数は算術式でよく、コンマの有無では区切らない (FR-070)")
    void argumentsMayBeArithmeticExpressions() {
        // 「11 - 5」は 1 個の引数、「11, -5」は 2 個である。分けているのは空白のほうである
        assertEquals("+00000600", computed("FUNCTION MAX(11 - 5)"));
        assertEquals("+00001100", computed("FUNCTION MAX(11, -5)"));
    }

    @Test
    @DisplayName("MEDIAN と MIDRANGE は割り切れる (FR-070)")
    void medianAndMidrangeDivideExactly() {
        assertEquals("+00000300", computed("FUNCTION MEDIAN(1, 5, 3)"));
        assertEquals("+00000400", computed("FUNCTION MEDIAN(1, 5, 3, 9)"),
                "偶数個なら真ん中 2 つの平均である");
        assertEquals("+00000250", computed("FUNCTION MEDIAN(1, 4)"),
                "2 で割ると小数桁が 1 つ増える");
        assertEquals("+00000500", computed("FUNCTION MIDRANGE(1, 5, 3, 9)"));
    }

    @Test
    @DisplayName("通日は 1601 年 1 月 1 日から数える (FR-070)")
    void dayNumbersCountFromTheStartOf1601() {
        assertEquals("+000000001", date("FUNCTION INTEGER-OF-DATE(16010101)"));
        assertEquals("+000000001", date("FUNCTION INTEGER-OF-DAY(1601001)"));
        assertEquals("+016010101", date("FUNCTION DATE-OF-INTEGER(1)"));
        assertEquals("+001601001", date("FUNCTION DAY-OF-INTEGER(1)"));
    }

    @Test
    @DisplayName("日付と通日は行って戻る (FR-070)")
    void dateAndDayNumberAreInverses() {
        assertEquals("+000155475", date("FUNCTION INTEGER-OF-DATE(20260904)"));
        assertEquals("+020260904", date("FUNCTION DATE-OF-INTEGER(155475)"));
        // 2026-09-04 は年の 247 日目である
        assertEquals("+002026247", date("FUNCTION DAY-OF-INTEGER(155475)"));
        assertEquals("+020260904", run(
                List.of("01 WS-D PIC S9(9) SIGN IS LEADING SEPARATE VALUE 0."),
                "COMPUTE WS-D = FUNCTION INTEGER-OF-DATE(20260904)",
                "COMPUTE WS-D = FUNCTION DATE-OF-INTEGER(WS-D)."),
                "行って戻ると元の日付になる");
    }

    @Test
    @DisplayName("暦に無い日は 0 になる (FR-070)")
    void anImpossibleDateIsZero() {
        assertEquals("+000000000", date("FUNCTION INTEGER-OF-DATE(20260231)"),
                "2026 年 2 月 31 日は無い");
        assertEquals("+000000000", date("FUNCTION INTEGER-OF-DATE(15001231)"),
                "1601 年より前は扱わない");
        assertEquals("+000000000", date("FUNCTION DATE-OF-INTEGER(0)"));
    }

    @Test
    @DisplayName("CURRENT-DATE は 21 文字である (FR-070, FR-204)")
    void currentDateIsTwentyOneCharacters() {
        // YYYYMMDDhhmmsscc に、協定世界時からのずれ 5 文字が続く
        assertEquals("2026090413450789+0000", run(
                List.of("01 WS-T PIC X(21)."), "MOVE FUNCTION CURRENT-DATE TO WS-T."));
    }

    @Test
    @DisplayName("WHEN-COMPILED も 21 文字である (FR-070)")
    void whenCompiledHasTheSameShape() {
        String value = run(List.of("01 WS-T PIC X(21)."),
                "MOVE FUNCTION WHEN-COMPILED TO WS-T.");
        // 翻訳した時刻そのものは試験に書けない。形だけを確かめる
        assertEquals(21, value.length());
        assertTrue(value.substring(0, 16).chars().allMatch(Character::isDigit), value);
        assertTrue(value.charAt(16) == '+' || value.charAt(16) == '-', value);
    }

    /** 近似が入る関数は、小数 6 桁の受取項目で確かめる。 */
    private static String approx(String expression) {
        return run(List.of("01 WS-N PIC S9(4)V9(6) SIGN IS LEADING SEPARATE VALUE 0."),
                "COMPUTE WS-N = " + expression + ".");
    }

    @Test
    @DisplayName("SQRT は 10 進のまま正しく丸める (FR-070, FR-071)")
    void squareRootIsRoundedInDecimal() {
        assertEquals("+0002000000", approx("FUNCTION SQRT(4)"));
        assertEquals("+0001414213", approx("FUNCTION SQRT(2)"));
        assertEquals("+0000000000", approx("FUNCTION SQRT(-1)"), "負の引数は 0 である");
    }

    @Test
    @DisplayName("三角関数と対数はラジアンと自然対数である (FR-070)")
    void theTranscendentalFunctionsFollowTheUsualDefinitions() {
        assertEquals("+0000000000", approx("FUNCTION SIN(0)"));
        assertEquals("+0001000000", approx("FUNCTION COS(0)"));
        assertEquals("+0000000000", approx("FUNCTION TAN(0)"));
        assertEquals("+0001000000", approx("FUNCTION LOG(2.718281828459045)"));
        assertEquals("+0002000000", approx("FUNCTION LOG10(100)"));
        assertEquals("+0001570796", approx("FUNCTION ASIN(1)"));
        assertEquals("+0000000000", approx("FUNCTION ACOS(1)"));
        assertEquals("+0000785398", approx("FUNCTION ATAN(1)"));
    }

    @Test
    @DisplayName("値域の外の引数は 0 になる (FR-070)")
    void anArgumentOutsideTheDomainIsZero() {
        assertEquals("+0000000000", approx("FUNCTION ASIN(2)"));
        assertEquals("+0000000000", approx("FUNCTION LOG(0)"));
    }

    @Test
    @DisplayName("MEAN と VARIANCE は個数で割る (FR-070)")
    void meanAndVarianceDivideByTheCount() {
        assertEquals("+0002000000", approx("FUNCTION MEAN(1, 2, 3)"));
        // 母分散である。個数から 1 を引かない
        assertEquals("+0000666666", approx("FUNCTION VARIANCE(1, 2, 3)"));
        assertEquals("+0000816496", approx("FUNCTION STANDARD-DEVIATION(1, 2, 3)"));
    }

    @Test
    @DisplayName("ANNUITY は利率 0 なら 1 / 期数である (FR-070)")
    void annuityWithoutInterestIsOneOverThePeriods() {
        // NIST CCVS85 が 0.249995 〜 の範囲で判定している値である
        assertEquals("+0000250000", approx("FUNCTION ANNUITY(0, 4)"));
        assertEquals("+0002912589", approx("FUNCTION ANNUITY(2.9, 4)"),
                "CCVS85 は 2.91252 〜 2.91264 の範囲で判定している");
    }

    @Test
    @DisplayName("PRESENT-VALUE は将来の金額を割り引く (FR-070)")
    void presentValueDiscountsTheFutureAmounts() {
        // 割引率 0 ならそのままの合計になる
        assertEquals("+0030000000", approx("FUNCTION PRESENT-VALUE(0, 10, 10, 10)"));
        assertEquals("+0009090909", approx("FUNCTION PRESENT-VALUE(0.1, 10)"));
    }

    @Test
    @DisplayName("RANDOM は 0 以上 1 未満で、同じ種は同じ並びになる (FR-070, FR-204)")
    void randomIsRepeatableFromItsSeed() {
        String first = approx("FUNCTION RANDOM(1)");
        assertEquals(first, approx("FUNCTION RANDOM(1)"), "同じ種なら同じ値である");
        assertTrue(first.startsWith("+0000"), first);
    }

    @Test
    @DisplayName("ALL と書いた添字は反復の数だけ引数へ広がる (FR-070)")
    void anAllSubscriptExpandsToEveryOccurrence() {
        List<String> table = List.of(
                "01 WS-T.",
                "   05 WS-E OCCURS 4 TIMES PIC 9.",
                "01 WS-N PIC S9(6)V99 SIGN IS LEADING SEPARATE VALUE 0.");
        assertEquals("+00000900", run(table,
                "MOVE 3 TO WS-E (1) MOVE 9 TO WS-E (2)",
                "MOVE 1 TO WS-E (3) MOVE 7 TO WS-E (4)",
                "COMPUTE WS-N = FUNCTION MAX(WS-E (ALL)).").substring(4));
        assertEquals("+00002000", run(table,
                "MOVE 3 TO WS-E (1) MOVE 9 TO WS-E (2)",
                "MOVE 1 TO WS-E (3) MOVE 7 TO WS-E (4)",
                "COMPUTE WS-N = FUNCTION SUM(WS-E (ALL)).").substring(4));
    }

    @Test
    @DisplayName("次元が 2 つあれば組み合わせすべてに広がる (FR-070)")
    void anAllSubscriptSpansEveryDimension() {
        assertEquals("+00000600", run(
                List.of("01 WS-T.",
                        "   05 WS-ROW OCCURS 2 TIMES.",
                        "      10 WS-E OCCURS 3 TIMES PIC 9 VALUE 1.",
                        "01 WS-N PIC S9(6)V99 SIGN IS LEADING SEPARATE VALUE 0."),
                "COMPUTE WS-N = FUNCTION SUM(WS-E (ALL, ALL)).").substring(6),
                "2 行 3 列で 1 が 6 個である");
    }

    @Test
    @DisplayName("ALL は組み込み関数の引数にしか書けない (FR-070)")
    void anAllSubscriptIsOnlyForFunctionArguments() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-T.",
                        "   05 WS-E OCCURS 4 TIMES PIC 9.",
                        "01 WS-N PIC 9."),
                "MOVE WS-E (ALL) TO WS-N.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("ALL may be written"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("MAX と MIN は英数字の引数も取れる (FR-070, FR-054)")
    void maxAndMinAlsoCompareText() {
        // 比べ方は照合順序である。返すのは引数そのものである。
        // EBCDIC では英小文字が英大文字より小さいので、ASCII とは答えが逆になる
        assertEquals("R  ", moved("FUNCTION MAX(\"R\", \"I\", \"a\")").substring(0, 3));
        assertEquals("a  ", moved("FUNCTION MIN(\"R\", \"I\", \"a\")").substring(0, 3));
        assertEquals("+00000100", computed("FUNCTION ORD-MAX(\"R\", \"I\", \"a\")"));
        assertEquals("+00000300", computed("FUNCTION ORD-MIN(\"R\", \"I\", \"a\")"));
    }

    @Test
    @DisplayName("数値と英数字の引数は混ぜられない (FR-070)")
    void theArgumentsOfMaxMustAllBeOfOneClass() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-N PIC S9(6)V99."), "COMPUTE WS-N = FUNCTION MAX(1, \"a\").");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("all be alphanumeric"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("引数を分けているのはコンマである (FR-070)")
    void aCommaSeparatesArgumentsBeforeAParenthesis() {
        // 「B, (C + 1)」は 2 個、「B (C + 1)」は B を添字付けした 1 個である。
        // コンマは飾りだが、開き括弧の前だけは意味を持つ
        assertEquals("+00000400", run(
                List.of("01 WS-A PIC 9 VALUE 2.",
                        "01 WS-B PIC 9 VALUE 3.",
                        "01 WS-N PIC S9(6)V99 SIGN IS LEADING SEPARATE VALUE 0."),
                "COMPUTE WS-N = FUNCTION MAX(WS-A, (WS-B + 5) / 2).").substring(2));
    }

    @Test
    @DisplayName("引数の中の除算は、文全体の桁数で打ち切る (FR-047, FR-070)")
    void aDivisionInsideAnArgumentUsesTheStatementScale() {
        // 引数だけを見て桁数を決めると、被除数 8 の小数桁は 0 なので 8 / 2.1 が 3 になり、
        // 平方根が 1.732 になる。受取項目の 7 桁を見れば 3.8095238 になり 1.9518 が出る。
        // IF136A の F-SQRT-16 がそこだけを確かめている
        // 検査スイートは範囲で見る (1.95172 〜 1.95188)。平方根の桁数は暫定判断 P-075
        assertEquals("+000019518001",
                run(List.of("01 WS-N PIC S9(5)V9(7) SIGN IS LEADING SEPARATE VALUE 0."),
                        "COMPUTE WS-N = FUNCTION SQRT(8 / 2.1)."));
    }

    @Test
    @DisplayName("整数を返す関数が、周りの式の小数部を消さない (FR-047, FR-070)")
    void anIntegerFunctionDoesNotEatTheSurroundingScale() {
        // 中間結果の総桁数を上限へ収めるとき、削るのは小数部だけである。整数を返す関数を
        // 上限の 30 桁で数えると、和の節が 6 桁あふれて小数部が消える。
        // IF111A の F-INTEGER-20 がそこだけを確かめている
        assertEquals("+000064000000",
                run(List.of("01 WS-N PIC S9(5)V9(7) SIGN IS LEADING SEPARATE VALUE 0.",
                            "01 I    PIC S9(5)V9(5) VALUE 3.4."),
                        "COMPUTE WS-N = FUNCTION INTEGER(3.2) + I.").substring(0, 13));
    }

    @Test
    @DisplayName("知らない関数は断る (FR-070)")
    void anUnknownFunctionIsRefused() {
        // 近い値を黙って返すより、書けないと言うほうがよい
        CobolCompiler.Result result = compile(
                List.of("01 WS-N PIC S9(6)V99."), "COMPUTE WS-N = FUNCTION NO-SUCH-ONE(4).");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("FUNCTION NO-SUCH-ONE"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("引数の数が合わなければ断る (FR-070)")
    void theNumberOfArgumentsIsChecked() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-N PIC S9(4)V99."), "COMPUTE WS-N = FUNCTION MOD(4).");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("takes 2 arguments"),
                result.diagnostics().toString());
    }
}
