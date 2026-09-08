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
    @DisplayName("知らない関数は断る (FR-070)")
    void anUnknownFunctionIsRefused() {
        // 近い値を黙って返すより、書けないと言うほうがよい
        CobolCompiler.Result result = compile(
                List.of("01 WS-N PIC S9(4)V99."), "COMPUTE WS-N = FUNCTION SQRT(4).");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("FUNCTION SQRT"),
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
