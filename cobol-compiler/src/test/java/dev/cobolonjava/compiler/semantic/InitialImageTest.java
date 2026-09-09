package dev.cobolonjava.compiler.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.parser.CobolParsing;
import dev.cobolonjava.compiler.source.Preprocessor;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 期待値は IBM-1047 のバイト列で書く。空白は {@code 40}、数字 {@code 0}〜{@code 9} は
 * {@code F0}〜{@code F9}、{@code A} {@code B} {@code C} {@code D} は {@code C1}〜{@code C4}、
 * {@code *} は {@code 5C}、{@code "} は {@code 7F} である。
 */
@Tag("V1")
class InitialImageTest {

    private static final String FILE = "MAIN.cbl";

    private static InitialImage.Result imageOf(String... entries) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.")) {
            sb.append("       ").append(line).append('\n');
        }
        for (String entry : entries) {
            sb.append("       ").append(entry).append('\n');
        }

        CobolParsing.Result parsed =
                CobolParsing.parse(Preprocessor.withoutCopybooks(), FILE, sb.toString());
        assertTrue(parsed.succeeded(), () -> "syntax errors: " + parsed.diagnostics());
        DataDivisionBuilder.Result built = DataDivisionBuilder.build(parsed.tree().programUnit(0));
        assertTrue(built.succeeded(), () -> "layout errors: " + built.diagnostics());
        return InitialImage.build(built.layout());
    }

    private static String hex(String name, String... entries) {
        InitialImage.Result result = imageOf(entries);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        return HexFormat.of().withUpperCase().formatHex(result.imageOf(name));
    }

    @Test
    @DisplayName("文字定数は左寄せで、残りは空白になる (FR-013)")
    void anAlphanumericValueIsLeftJustifiedAndPadded() {
        assertEquals("C1C2404040", hex("WS-A", "01 WS-A PIC X(5) VALUE 'AB'."));
    }

    @Test
    @DisplayName("JUSTIFIED RIGHT は<b>初期値には効かない</b> (FR-013)")
    void justifiedRightDoesNotAffectTheInitialValue() {
        // 規格がそう決めている (85 規格 JUSTIFIED 句の一般規則 (3))。右へ寄せるのは
        // 実行時の転記だけである。CCVS85 の NC107A は X(3) JUST VALUE "XY" が
        // "XY " になることを確かめている
        assertEquals("C1C24040", hex("WS-A", "01 WS-A PIC X(4) JUSTIFIED RIGHT VALUE 'AB'."));
    }

    @Test
    @DisplayName("01 レベルの REDEFINES は、長ければ記憶域を広げる (FR-021)")
    void alargerRedefinitionAtLevel01ExtendsTheArea() {
        // 01 レベルでファイル節の外なら、重ねる先より<b>長くてよい</b>。長ければ
        // そのぶん記憶域を広げなければならない。広げないと次の 01 レベルが重なり、
        // そちらへ書いたつもりのない値が<b>黙って壊れる</b>
        // (CCVS85 の NC107A: MOVE SPACE TO REDEF12 が次の 01 レベルを潰していた)
        InitialImage.Result image = imageOf(
                "01 WS-A PIC X(2).",
                "01 WS-B REDEFINES WS-A PIC X(8).",
                "01 WS-C PIC X(3) VALUE 'AAA'.");
        assertTrue(image.succeeded(), () -> image.diagnostics().toString());
        // WS-B が 8 バイトあるので、WS-C は 8 バイト目から始まる
        assertEquals(11, image.storage().length);
    }

    @Test
    @DisplayName("数値の符号化はランタイムの記述子が行う (FR-013, FR-031)")
    void numericValuesAreEncodedByTheRuntime() {
        assertEquals("F0F4F2", hex("WS-N", "01 WS-N PIC 9(3) VALUE 42."));
        assertEquals("012D", hex("WS-P", "01 WS-P PIC S9(3) COMP-3 VALUE -12."));
        assertEquals("012C", hex("WS-B", "01 WS-B PIC S9(4) COMP VALUE 300."));
    }

    @Test
    @DisplayName("小数点は PICTURE に合わせて位置を揃える (FR-013)")
    void theValueIsAlignedOnItsDecimalPoint() {
        assertEquals("F0F0F1F5F0", hex("WS-N", "01 WS-N PIC 9(3)V99 VALUE 1.5."));
    }

    @Test
    @DisplayName("図形定数は項目いっぱいまで埋める (FR-013)")
    void figurativeConstantsFillTheWholeItem() {
        assertEquals("404040", hex("WS-A", "01 WS-A PIC X(3) VALUE SPACES."));
        assertEquals("FFFFFF", hex("WS-A", "01 WS-A PIC X(3) VALUE HIGH-VALUES."));
        assertEquals("000000", hex("WS-A", "01 WS-A PIC X(3) VALUE LOW-VALUES."));
        assertEquals("F0F0F0", hex("WS-A", "01 WS-A PIC X(3) VALUE ZEROS."));
        assertEquals("7F7F7F", hex("WS-A", "01 WS-A PIC X(3) VALUE QUOTES."));
    }

    @Test
    @DisplayName("ALL は項目いっぱいまで繰り返し、途中で切る (FR-013)")
    void allRepeatsItsLiteralAndIsCutOff() {
        assertEquals("5C5C5C5C5C", hex("WS-A", "01 WS-A PIC X(5) VALUE ALL '*'."));
        assertEquals("C1C2C1C2C1", hex("WS-A", "01 WS-A PIC X(5) VALUE ALL 'AB'."));
    }

    @Test
    @DisplayName("数値項目の VALUE ZERO は 0 になる (FR-013)")
    void zeroIsANumericValueToo() {
        assertEquals("F0F0F0", hex("WS-N", "01 WS-N PIC 9(3) VALUE ZERO."));
        // 符号なし (S のない) パック 10 進の符号ニブルは F である
        assertEquals("0000000F", hex("WS-P", "01 WS-P PIC 9(7) COMP-3 VALUE ZERO."));
        assertEquals("0000000C", hex("WS-S", "01 WS-S PIC S9(7) COMP-3 VALUE ZERO."));
    }

    @Test
    @DisplayName("指定のない場所は空白で埋まる (P-025)")
    void whatHasNoValueIsFilledWithSpaces() {
        assertEquals("C1C2F0F0F74040", hex("WS-REC",
                "01 WS-REC.",
                "   05 WS-A PIC X(2) VALUE 'AB'.",
                "   05 WS-B PIC 9(3) VALUE 7.",
                "   05 WS-C PIC X(2)."));
    }

    @Test
    @DisplayName("OCCURS の中の VALUE はすべての反復に効く (FR-013)")
    void aValueInsideOccursAppliesToEveryOccurrence() {
        assertEquals("5CF15CF15CF1", hex("WS-REC",
                "01 WS-REC.",
                "   05 WS-T OCCURS 3 TIMES.",
                "      10 WS-X PIC X VALUE '*'.",
                "      10 WS-Y PIC 9 VALUE 1."));
    }

    @Test
    @DisplayName("REDEFINES で重ねた項目は初期値を塗り潰さない (FR-013, FR-021)")
    void aRedefiningItemDoesNotEraseTheValueBeneathIt() {
        // 素通しで書くと、重ねる先の初期値が空白になる
        assertEquals("C1C2C3C4", hex("WS-REC",
                "01 WS-REC.",
                "   05 WS-D PIC X(4) VALUE 'ABCD'.",
                "   05 WS-R REDEFINES WS-D.",
                "      10 WS-R1 PIC X(2).",
                "      10 WS-R2 PIC X(2)."));
    }

    @Test
    @DisplayName("01 レベルの REDEFINES も初期値を塗り潰さない (FR-013, FR-021)")
    void aRedefiningRecordDoesNotEraseTheValueBeneathIt() {
        // 記憶域そのものを見る。01 どうしは同じ位置に重なるので、値の無い側を
        // 書くと重ねる先の初期値が空白になる (NC116A がそれで S0C7 で落ちていた)
        InitialImage.Result result = imageOf(
                "01 WS-D PIC S9(4) VALUE +1234.",
                "01 WS-R REDEFINES WS-D PIC X(4).");
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        assertEquals("F1F2F3C4",
                HexFormat.of().withUpperCase().formatHex(result.storage()));
    }

    @Test
    @DisplayName("01 レベルの REDEFINES の中の VALUE は誤りとして報告する (FR-013, FR-021)")
    void aValueInARedefiningRecordIsReported() {
        InitialImage.Result result = imageOf(
                "01 WS-D PIC X(4) VALUE 'ABCD'.",
                "01 WS-R REDEFINES WS-D PIC X(4) VALUE 'WXYZ'.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("REDEFINES"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("群項目の VALUE は中身を一括で埋める (FR-013)")
    void aValueOnAGroupFillsItsWholeContent() {
        assertEquals("C1C2C34040", hex("WS-REC",
                "01 WS-REC VALUE 'ABC'.",
                "   05 WS-A PIC X(3).",
                "   05 WS-B PIC X(2)."));
    }

    @Test
    @DisplayName("COMP-1 は 16 進浮動小数点として符号化する (FR-032)")
    void aFloatingPointValueUsesHexadecimalFloating() {
        assertEquals("41100000", hex("WS-F", "01 WS-F COMP-1 VALUE 1."));
    }

    @Test
    @DisplayName("項目より長い VALUE は誤りとして報告する (FR-013)")
    void aValueLongerThanItsItemIsReported() {
        InitialImage.Result result = imageOf("01 WS-A PIC X(2) VALUE 'ABCD'.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("longer"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("桁に収まらない VALUE は誤りとして報告する (FR-013, FR-043)")
    void aValueThatDoesNotFitIsReported() {
        // 黙って上位桁を落とすと、宣言と初期値が食い違ったまま動き出す
        InitialImage.Result result = imageOf("01 WS-N PIC 9(3) VALUE 1234.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("does not fit"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("数値項目に文字定数を書いたら誤りとして報告する (FR-013)")
    void anAlphanumericValueOnANumericItemIsReported() {
        InitialImage.Result result = imageOf("01 WS-N PIC 9(3) VALUE 'ABC'.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("numeric VALUE"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("REDEFINES の中の VALUE は誤りとして報告する (FR-013, FR-021)")
    void aValueInsideARedefinesIsReported() {
        InitialImage.Result result = imageOf(
                "01 WS-REC.",
                "   05 WS-D PIC X(4).",
                "   05 WS-R REDEFINES WS-D PIC X(4) VALUE 'ABCD'.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("REDEFINES"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("誤りは元のソース上の位置を指す (FR-094, FR-183)")
    void diagnosticsPointAtTheOriginalSource() {
        InitialImage.Result result = imageOf(
                "01 WS-REC.",
                "   05 WS-A PIC X(2).",
                "   05 WS-B PIC X(2) VALUE 'ABCD'.");
        assertEquals(FILE, result.diagnostics().get(0).origin().fileName());
        assertEquals(7, result.diagnostics().get(0).origin().line());
    }
}
