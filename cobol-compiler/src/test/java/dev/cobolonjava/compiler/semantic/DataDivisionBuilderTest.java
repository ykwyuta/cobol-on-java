package dev.cobolonjava.compiler.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.parser.CobolParsing;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.Preprocessor;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.item.Usage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class DataDivisionBuilderTest {

    private static final String FILE = "MAIN.cbl";

    /** データ部の中身だけを渡し、前後の決まり文句を補って解析する。 */
    private static DataDivisionBuilder.Result build(String... entries) {
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
        return DataDivisionBuilder.build(parsed.tree().programUnit(0));
    }

    private static DataLayout layoutOf(String... entries) {
        DataDivisionBuilder.Result result = build(entries);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        return result.layout();
    }

    private static DataItem item(DataLayout layout, String name) {
        List<DataItem> found = layout.findAll(name);
        assertEquals(1, found.size(), () -> "expected exactly one " + name + ", got " + found);
        return found.get(0);
    }

    @Test
    @DisplayName("項目の長さはランタイムの記述子が決める (FR-030, FR-031)")
    void itemLengthsComeFromTheRuntime() {
        DataLayout layout = layoutOf(
                "01 WS-REC.",
                "   05 WS-TEXT  PIC X(3).",
                "   05 WS-PACK  PIC 9(5) COMP-3.",
                "   05 WS-BIN   PIC S9(4) COMP.",
                "   05 WS-ZONED PIC S9(7).");

        assertEquals(3, item(layout, "WS-TEXT").length());
        assertEquals(3, item(layout, "WS-PACK").length(), "5 桁のパック 10 進は 3 バイト");
        assertEquals(2, item(layout, "WS-BIN").length(), "4 桁の 2 進は半語");
        assertEquals(7, item(layout, "WS-ZONED").length(), "符号は最終バイトのゾーンに入る");
    }

    @Test
    @DisplayName("下位の項目は順に並び、群項目の長さはその合計になる (FR-020)")
    void elementaryItemsAreLaidOutInOrder() {
        DataLayout layout = layoutOf(
                "01 WS-REC.",
                "   05 WS-A PIC X(3).",
                "   05 WS-B PIC 9(5) COMP-3.",
                "   05 WS-C PIC S9(4) COMP.");

        assertEquals(0, item(layout, "WS-A").offset());
        assertEquals(3, item(layout, "WS-B").offset());
        assertEquals(6, item(layout, "WS-C").offset());
        assertEquals(8, item(layout, "WS-REC").length());
    }

    @Test
    @DisplayName("レベル番号は入れ子の深さではなく大小だけを表す (FR-020)")
    void levelNumbersOnlyExpressOrder() {
        // 01 の次が 05、その下が 10。番号が飛んでも入れ子は同じである
        DataLayout layout = layoutOf(
                "01 WS-REC.",
                "   05 WS-GROUP.",
                "      10 WS-X PIC X(2).",
                "      10 WS-Y PIC X(3).",
                "   05 WS-Z PIC X(4).");

        assertEquals(0, item(layout, "WS-GROUP").offset());
        assertEquals(5, item(layout, "WS-GROUP").length());
        assertEquals(2, item(layout, "WS-Y").offset());
        assertEquals(5, item(layout, "WS-Z").offset());
        assertEquals(9, item(layout, "WS-REC").length());
        assertEquals("WS-GROUP", item(layout, "WS-X").parent().name());
    }

    @Test
    @DisplayName("OCCURS は 1 回分の長さを繰り返す (FR-020)")
    void occursRepeatsTheLengthOfOneOccurrence() {
        DataLayout layout = layoutOf(
                "01 WS-REC.",
                "   05 WS-TABLE OCCURS 10 TIMES.",
                "      10 WS-KEY  PIC X(2).",
                "      10 WS-DATA PIC 9(3) COMP-3.",
                "   05 WS-TAIL PIC X.");

        DataItem table = item(layout, "WS-TABLE");
        assertEquals(4, table.length(), "1 回分は 2 + 2 バイト");
        assertEquals(10, table.occurs());
        assertEquals(40, table.totalLength());
        assertEquals(40, item(layout, "WS-TAIL").offset(), "反復の分だけ位置が進む");
        assertEquals(41, item(layout, "WS-REC").length());
    }

    @Test
    @DisplayName("OCCURS ... TO ... は最大の回数で記憶域を取る (FR-020)")
    void aVariableOccursReservesItsMaximum() {
        DataLayout layout = layoutOf(
                "01 WS-REC.",
                "   05 WS-COUNT PIC 9(3) COMP.",
                "   05 WS-TAB OCCURS 1 TO 5 DEPENDING ON WS-COUNT PIC X(2).");

        assertEquals(5, item(layout, "WS-TAB").occurs());
        assertEquals(12, item(layout, "WS-REC").length(), "2 + 5 x 2");
    }

    @Test
    @DisplayName("REDEFINES は記憶域を進めない (FR-020)")
    void redefinesDoesNotAdvanceTheStorage() {
        DataLayout layout = layoutOf(
                "01 WS-REC.",
                "   05 WS-DATE PIC 9(8).",
                "   05 WS-DATE-PARTS REDEFINES WS-DATE.",
                "      10 WS-YEAR  PIC 9(4).",
                "      10 WS-MONTH PIC 9(2).",
                "      10 WS-DAY   PIC 9(2).",
                "   05 WS-AFTER PIC X.");

        assertEquals(0, item(layout, "WS-DATE").offset());
        assertEquals(0, item(layout, "WS-DATE-PARTS").offset(), "重ねる先と同じ位置から始まる");
        assertEquals(4, item(layout, "WS-MONTH").offset());
        assertEquals(8, item(layout, "WS-AFTER").offset(), "重ねた分は位置を進めない");
        assertEquals(9, item(layout, "WS-REC").length());
    }

    @Test
    @DisplayName("群項目の長さはいちばん遠くまで届いた項目で決まる (FR-020)")
    void aGroupReachesAsFarAsItsFarthestItem() {
        // 重ねた側のほうが長い場合、群項目はそちらに合わせる
        DataLayout layout = layoutOf(
                "01 WS-REC.",
                "   05 WS-SHORT PIC X(2).",
                "   05 WS-LONG REDEFINES WS-SHORT PIC X(6).");

        assertEquals(6, item(layout, "WS-REC").length());
    }

    @Test
    @DisplayName("SIGN IS SEPARATE は 1 バイト増やす (FR-031)")
    void aSeparateSignAddsAByte() {
        DataLayout layout = layoutOf(
                "01 WS-REC.",
                "   05 WS-A PIC S9(5) SIGN IS LEADING SEPARATE.",
                "   05 WS-B PIC S9(5) SIGN IS TRAILING.");

        assertEquals(6, item(layout, "WS-A").length());
        assertEquals(SignPosition.LEADING_SEPARATE, item(layout, "WS-A").signPosition());
        assertEquals(5, item(layout, "WS-B").length());
        assertEquals(SignPosition.TRAILING, item(layout, "WS-B").signPosition());
    }

    @Test
    @DisplayName("PICTURE を持たない浮動小数点項目は 4 / 8 バイトである (FR-032)")
    void floatingPointItemsHaveAFixedLength() {
        DataLayout layout = layoutOf(
                "01 WS-REC.",
                "   05 WS-SHORT COMP-1.",
                "   05 WS-LONG  COMP-2.");

        assertEquals(4, item(layout, "WS-SHORT").length());
        assertEquals(8, item(layout, "WS-LONG").length());
        assertEquals(Usage.COMP_2, item(layout, "WS-LONG").usage());
        assertEquals(4, item(layout, "WS-LONG").offset());
    }

    @Test
    @DisplayName("数値編集項目の長さは編集後の桁数である (FR-033)")
    void anEditedItemIsAsLongAsItsEditedForm() {
        DataLayout layout = layoutOf(
                "01 WS-REC.",
                "   05 WS-MONEY PIC ZZ,ZZ9.99CR.");

        // Z Z , Z Z 9 . 9 9 と CR の 2 バイトで 11 バイト
        assertEquals(11, item(layout, "WS-MONEY").length());
    }

    @Test
    @DisplayName("FILLER は名前を持たない (FR-020)")
    void fillerHasNoName() {
        DataLayout layout = layoutOf(
                "01 WS-REC.",
                "   05 FILLER PIC X(4).",
                "   05 WS-A   PIC X.",
                "   05        PIC X(2).");

        assertEquals(4, item(layout, "WS-A").offset());
        assertEquals(7, item(layout, "WS-REC").length());
        assertNull(layout.records().get(0).children().get(0).name());
        assertNull(layout.records().get(0).children().get(2).name(), "名前の省略も FILLER と同じ");
    }

    @Test
    @DisplayName("88 レベルは記憶域を占めず直前の項目に付く (FR-020)")
    void conditionNamesBelongToTheItemBeforeThem() {
        DataLayout layout = layoutOf(
                "01 WS-REC.",
                "   05 WS-FLAG PIC X.",
                "      88 WS-YES VALUE 'Y'.",
                "      88 WS-NO  VALUE 'N' 'n'.",
                "      88 WS-DIGIT VALUE '0' THRU '9'.",
                "   05 WS-AFTER PIC X.");

        DataItem flag = item(layout, "WS-FLAG");
        assertEquals(3, flag.conditionNames().size());
        assertEquals("WS-YES", flag.conditionNames().get(0).name());
        assertEquals(2, flag.conditionNames().get(1).values().size());
        assertEquals(new LiteralValue.Text("9"), flag.conditionNames().get(2).values().get(0).to());
        assertEquals(1, item(layout, "WS-AFTER").offset(), "条件名は記憶域を占めない");
    }

    @Test
    @DisplayName("独立項目 77 は 01 と同じくそれ自身の記憶域を持つ (FR-020)")
    void anIndependentItemHasItsOwnStorage() {
        DataLayout layout = layoutOf(
                "01 WS-REC PIC X(4).",
                "77 WS-ALONE PIC 9(3) COMP-3.");

        assertEquals(2, layout.records().size());
        assertEquals(0, item(layout, "WS-ALONE").offset());
        assertEquals(2, item(layout, "WS-ALONE").length());
    }

    @Test
    @DisplayName("PICTURE のない基本項目は誤りとして報告する (FR-030)")
    void anElementaryItemWithoutAPictureIsReported() {
        DataDivisionBuilder.Result result = build("01 WS-A.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("PICTURE"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("未対応の USAGE は黙って通さない (P-006)")
    void anUnsupportedUsageIsReported() {
        DataDivisionBuilder.Result result = build(
                "01 WS-REC.",
                "   05 WS-P POINTER.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("POINTER"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("先行しない項目を REDEFINES したら誤りとして報告する (FR-020)")
    void redefiningSomethingThatDoesNotPrecedeIsReported() {
        DataDivisionBuilder.Result result = build(
                "01 WS-REC.",
                "   05 WS-A REDEFINES WS-B PIC X.",
                "   05 WS-B PIC X.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("REDEFINES"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("誤りは元のソース上の位置を指す (FR-094, FR-183)")
    void diagnosticsPointAtTheOriginalSource() {
        DataDivisionBuilder.Result result = build(
                "01 WS-REC.",
                "   05 WS-A PIC X.",
                "   05 WS-B POINTER.");

        Diagnostic first = result.diagnostics().get(0);
        assertEquals(FILE, first.origin().fileName());
        assertEquals(7, first.origin().line(), "決まり文句 4 行のあとの 3 行目");
    }
}
