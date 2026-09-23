package dev.cobolonjava.pli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SYSPRINT への {@code PUT} (Enterprise PL/I Language Reference, "PUT list-directed" と
 * "PRINT attribute"、Chapter 4 "Target: CHARACTER")。
 *
 * <p>どの試験も、以前の実装 (項目を区切りなしで連結し、数を最短の形で書き、PUT 1 つを 1 行に
 * していた) では通らない形にしてある。
 */
class PutStatementTest {

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(PutStatementTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    /** 本体を主手続きに包んで動かし、出力を行の並び ('\n' 区切り) で返す。 */
    private static String run(String body) throws Exception {
        PliCompiler.Result result = PliCompiler.standard().compile("PUTS.pli",
                "PUTS: PROCEDURE OPTIONS(MAIN);\n" + body + "\nEND PUTS;\n");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        CobolProgram program = (CobolProgram) new GeneratedLoader()
                .define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        program.runFresh(ProgramContext.capturing(output));
        return output.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n");
    }

    private static String rejection(String body) {
        PliCompiler.Result result = PliCompiler.standard().compile("PUTS.pli",
                "PUTS: PROCEDURE OPTIONS(MAIN);\n" + body + "\nEND PUTS;\n");
        assertFalse(result.succeeded(), "expected the compiler to refuse: " + body);
        return result.diagnostics().toString();
    }

    @Test
    @DisplayName("FIXED BIN(p) は 10 進の精度 1+CEIL(p/3.32) に移し、幅 p+3 の欄に右寄せする")
    void fixedBinaryIsRightAdjustedInItsDeclaredWidth() throws Exception {
        // BIN(15) → DEC(6) → 幅 9、BIN(31) → DEC(11) → 幅 14
        assertEquals(String.format("%9s", "5") + "\n", run("""
                DCL N FIXED BIN(15) INIT(5);
                PUT SKIP LIST(N);
                """));
        assertEquals(String.format("%14s", "-42") + "\n", run("""
                DCL N FIXED BIN(31) INIT(0);
                N = N - 42;
                PUT SKIP LIST(N);
                """));
    }

    @Test
    @DisplayName("FIXED DEC(p,q) は幅 p+3 で、q 桁の小数と点の前の 0 を 1 つ持つ")
    void fixedDecimalKeepsItsScale() throws Exception {
        assertEquals(String.format("%10s", "12.50") + "\n", run("""
                DCL A FIXED DEC(7,2) INIT(12.5);
                PUT SKIP LIST(A);
                """));
        assertEquals(String.format("%8s", "0.05") + "\n", run("""
                DCL A FIXED DEC(5,2) INIT(0.05);
                PUT SKIP LIST(A);
                """));
    }

    @Test
    @DisplayName("10 進定数の精度は書かれた桁の数である。0.5 は DEC(2,1) なので幅 5")
    void decimalConstantsTakeTheirWrittenPrecision() throws Exception {
        assertEquals(String.format("%-24s%5s", String.format("%4s", "5"), "0.5") + "\n",
                run("PUT SKIP LIST(5, 0.5);"));
    }

    @Test
    @DisplayName("演算結果の精度は被演算子の属性から決まる。DEC(3)+DEC(3) は DEC(4)、BIN は 31 で頭打ち")
    void resultPrecisionFollowsTheOperands() throws Exception {
        assertEquals(String.format("%-24s%14s", String.format("%7s", "1998"), "2") + "\n", run("""
                DCL A FIXED DEC(3) INIT(999);
                DCL N FIXED BIN(31) INIT(1);
                PUT SKIP LIST(A + A, N + 1);
                """));
    }

    @Test
    @DisplayName("整数の FIXED BIN どうしの除算は切り捨てる。7/2 は 3 である")
    void fixedBinaryDivisionTruncates() throws Exception {
        assertEquals(String.format("%14s", "6") + "\n", run("""
                DCL A FIXED BIN(31) INIT(7);
                DCL B FIXED BIN(31) INIT(2);
                PUT SKIP LIST(A / B * B);
                """));
    }

    @Test
    @DisplayName("項目は左端と tab 位置 25, 49, 73, 97 に揃い、入らなければ次の行の左端へ行く")
    void itemsAlignOnTabPositions() throws Exception {
        String longItem = "PROCESSING ACCOUNTS FOR SORT CODE: ";
        assertEquals(String.format("%-48s%s", longItem, "987654") + "\n",
                run("PUT SKIP LIST('" + longItem + "', '987654');"));
        // 5 つ目は 97 桁目。6 つ目の tab (121) は行幅 120 を超えるので次の行
        assertEquals(String.format("%-24s%-24s%-24s%-24s%s", "A", "B", "C", "D", "E")
                        + "\nF\n",
                run("PUT SKIP LIST('A', 'B', 'C', 'D', 'E', 'F');"));
    }

    @Test
    @DisplayName("SKIP の無い PUT は同じ行に続き、SKIP は書く前に改行する")
    void putWithoutSkipContinuesTheLine() throws Exception {
        assertEquals(String.format("%-24s%s", "A", "B") + "\nC\n", run("""
                PUT LIST('A');
                PUT LIST('B');
                PUT SKIP LIST('C');
                """));
    }

    @Test
    @DisplayName("SKIP(2) は 1 行を空ける")
    void skipCountLeavesBlankLines() throws Exception {
        assertEquals("A\n\nB\n", run("""
                PUT SKIP LIST('A');
                PUT SKIP(2) LIST('B');
                """));
    }

    @Test
    @DisplayName("行幅 120 を超えた文字は次の行へ送る")
    void excessCharactersGoToTheNextLine() throws Exception {
        assertEquals("X".repeat(120) + "\nXXXX\n",
                run("PUT SKIP LIST(REPEAT('X', 123));"));
    }

    @Test
    @DisplayName("ABS は x と同じ属性を持つ。FIXED BIN(15) の -5 は幅 9 の 5 になる")
    void absKeepsTheAttributesOfItsArgument() throws Exception {
        assertEquals(String.format("%9s", "5") + "\n", run("""
                DCL N FIXED BIN(15) INIT(0);
                N = N - 5;
                PUT SKIP LIST(ABS(N));
                """));
    }

    @Test
    @DisplayName("CENTRE は余りの 1 桁を右に置き、3 つ目の引数で埋める (LRM の例)")
    void centreLeansLeftAndUsesThePadCharacter() throws Exception {
        assertEquals("***Feel the Power****\n",
                run("PUT SKIP LIST(CENTRE('Feel the Power', 21, '*'));"));
    }

    @Test
    @DisplayName("REPEAT(x, y) は x を y 回つなげ足すので y+1 個。y が 0 以下なら x そのもの")
    void repeatConcatenatesYMoreCopies() throws Exception {
        assertEquals("ABABAB\nAB\n", run("""
                PUT SKIP LIST(REPEAT('AB', 2));
                PUT SKIP LIST(REPEAT('AB', 0));
                """));
    }

    @Test
    @DisplayName("PUT STRING は左端から組み立てて文字の変数へ代入し、F(w,d) は四捨五入して右寄せする")
    void putStringBuildsTheLineWithFixedFormat() throws Exception {
        // BNKSTMT の合計行と同じ形。F(10,2) で 1234.565 は 1234.57 になる
        // 代入なので、残りの桁は空白で埋まる (前の中身は残らない)
        assertEquals(String.format("%-40s|%n%-40s|%n",
                        "  OPENING BALANCE:        $   1234.57", "  COUNT:   -3")
                        .replace(System.lineSeparator(), "\n"), run("""
                DCL LINE CHAR(40);
                DCL BAL FIXED DEC(12,3) INIT(1234.565);
                DCL N FIXED BIN(31) INIT(0);
                PUT STRING(LINE) EDIT('  OPENING BALANCE:        $', BAL) (A, F(10,2));
                PUT SKIP EDIT(LINE, '|') (A);
                N = N - 3;
                PUT STRING(LINE) EDIT('  COUNT:', N) (A, F(5));
                PUT SKIP EDIT(LINE, '|') (A);
                """));
    }

    @Test
    @DisplayName("PUT STRING の組み立てが変数に入りきらなければ止める。F の欄に入らなければ止める")
    void putStringOverflowIsAnError() {
        assertThrows(RuntimeException.class, () -> run("""
                DCL SHORT CHAR(3);
                PUT STRING(SHORT) EDIT('ABCD') (A);
                """));
        assertThrows(RuntimeException.class, () -> run("""
                DCL S CHAR(10);
                PUT STRING(S) EDIT(123456) (F(3));
                """));
    }

    @Test
    @DisplayName("連結と CHAR も同じ規則で文字にする。資産が TRIM(CHAR(n)) と書くのはこのためである")
    void concatenationUsesTheSameConversion() throws Exception {
        assertEquals("N=        5\nN=5\n", run("""
                DCL N FIXED BIN(15) INIT(5);
                PUT SKIP LIST('N=' || N);
                PUT SKIP LIST('N=' || TRIM(CHAR(N)));
                """));
    }

    @Test
    @DisplayName("ビット列は引用符で囲んで B を付ける")
    void bitStringsAreQuoted() throws Exception {
        assertEquals("'1'B\n", run("PUT SKIP LIST(1 = 1);"));
    }

    @Test
    @DisplayName("EDIT は tab に揃えず、A(w) は w 桁に詰め、X(w) は空白を置く")
    void editDirectedUsesTheFormatList() throws Exception {
        assertEquals("AB   CD\n", run("PUT SKIP EDIT('AB', 'CDEF') (A(3), X(2), A(2));"));
        // 値が書式より多ければ、書式並びの頭へ戻る
        assertEquals("ABC\n", run("PUT SKIP EDIT('A', 'B', 'C') (A);"));
    }

    @Test
    @DisplayName("まだ持たない選択子と書式項目は、読み飛ばさずに翻訳で断る")
    void unsupportedFormsAreRefused() {
        assertTrue(rejection("PUT LINE(5) LIST('A');").contains("PUT option LINE"));
        assertTrue(rejection("PUT FILE(REPORT) LIST('A');").contains("PUT FILE(REPORT)"));
        assertTrue(rejection("PUT SKIP EDIT(1) (E(10,3));").contains("format item E"));
        assertTrue(rejection("PUT SKIP(0) LIST('A');").contains("SKIP(0)"));
        assertTrue(rejection("""
                DCL S CHAR(10);
                PUT STRING(S) LIST('A');
                """).contains("PUT STRING"));
    }

    @Test
    @DisplayName("PAGE は次の行を新しいページの頭にし、改ページ文字 (ANS の '1') を置く")
    void pageStartsANewPage() throws Exception {
        assertEquals("A\n\fB\n", run("""
                PUT SKIP LIST('A');
                PUT PAGE;
                PUT LIST('B');
                """));
        // PAGE のあとの SKIP は新しいページの 1 行目から数える
        assertEquals("A\n\f\nB\n", run("""
                PUT SKIP LIST('A');
                PUT PAGE;
                PUT SKIP LIST('B');
                """));
    }

    @Test
    @DisplayName("PAGESIZE 60 行を超えると、ENDPAGE の既定の動きで改ページする")
    void theSixtyFirstLineStartsANewPage() throws Exception {
        StringBuilder expected = new StringBuilder();
        for (int i = 1; i <= 61; i++) {
            expected.append(i == 61 ? "\f" : "").append("L\n");
        }
        assertEquals(expected.toString(), run("""
                DCL I FIXED BIN(31);
                DO I = 1 TO 61;
                  PUT SKIP LIST('L');
                END;
                """));
    }

    @Test
    @DisplayName("LIMITS の既定は FIXEDDEC(15,31)。式に 15 桁を超える被演算子があれば上限は 31")
    void wideOperandsRaiseTheDecimalLimit() throws Exception {
        // DEC(20) + DEC(20) は DEC(21)、幅 24。以前は 15 桁で打ち切っていた (幅 18)
        assertEquals(String.format("%24s", "2") + "\n", run("""
                DCL A FIXED DEC(20) INIT(1);
                PUT SKIP LIST(A + A);
                """));
    }

    @Test
    @DisplayName("*PROCESS LIMITS(FIXEDDEC(15)) なら、広い被演算子があっても上限は 15")
    void limitsFixesTheDecimalLimit() throws Exception {
        assertEquals(String.format("%18s", "2") + "\n", runWith("LIMITS(FIXEDDEC(15))", """
                DCL A FIXED DEC(20) INIT(1);
                PUT SKIP LIST(A + A);
                """));
    }

    @Test
    @DisplayName("RULES(IBM) の BIN(15)/BIN(15) は BIN(31,16)、RULES(ANS) は BIN(31,0) で切り捨てる")
    void rulesDecideTheScaleOfBinaryDivision() throws Exception {
        String program = """
                DCL A FIXED BIN(15) INIT(7);
                DCL B FIXED BIN(15) INIT(2);
                PUT SKIP LIST(A / B);
                """;
        assertEquals(String.format("%14s", "3.50000") + "\n", run(program));
        assertEquals(String.format("%14s", "3") + "\n", runWith("RULES(ANS)", program));
    }

    @Test
    @DisplayName("RULES(ANS) は位取りのある 10 進と 2 進を 10 進で計算する。IBM は 2 進で計算する")
    void rulesDecideTheBaseOfMixedArithmetic() throws Exception {
        String program = """
                DCL D FIXED DEC(5,2) INIT(1.25);
                DCL B FIXED BIN(15) INIT(2);
                PUT SKIP LIST(D + B);
                """;
        // IBM: DEC(5,2) は BIN(18,7)、和は BIN(23,7)、文字にすると (8,3)
        assertEquals(String.format("%11s", "3.250") + "\n", run(program));
        // ANS: BIN(15) は DEC(5)、和は DEC(8,2)
        assertEquals(String.format("%11s", "3.25") + "\n", runWith("RULES(ANS)", program));
    }

    @Test
    @DisplayName("許されない LIMITS (FIXEDDEC(31,15)) は翻訳で断る")
    void invalidLimitsAreRefused() {
        PliCompiler.Result result = PliCompiler.standard().compile("L.pli", """
                *PROCESS LIMITS(FIXEDDEC(31,15));
                L: PROCEDURE OPTIONS(MAIN);
                END L;
                """);
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("FIXEDDEC(31,15)"));
    }

    /** *PROCESS を付けて動かす。 */
    private static String runWith(String process, String body) throws Exception {
        PliCompiler.Result result = PliCompiler.standard().compile("PUTS.pli",
                "*PROCESS " + process + ";\nPUTS: PROCEDURE OPTIONS(MAIN);\n" + body
                        + "\nEND PUTS;\n");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        CobolProgram program = (CobolProgram) new GeneratedLoader()
                .define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        program.runFresh(ProgramContext.capturing(output));
        return output.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n");
    }

    @Test
    @DisplayName("属性の分からない算術値 (文字から作った数) は、近い形を出さずに止める")
    void arithmeticWithoutAttributesIsNotWritten() {
        assertThrows(RuntimeException.class, () -> run("""
                DCL C CHAR(3) INIT('12');
                PUT SKIP LIST(C + 1);
                """));
    }
}
