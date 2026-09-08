package dev.cobolonjava.runtime.picture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.decimal.Decimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 数値編集のゴールデンテスト (要件 FR-030)。
 *
 * <p>ホストではこの処理が {@code ED} / {@code EDMK} 命令として実現されているため、
 * ここは Hercules オラクルによって V2 へ引き上げられる代表的な領域である (要件 4.3 節)。
 * 現時点の期待値は仕様に基づくものであり V1 である。
 */
@Tag("V1")
class NumericEditorTest {

    private static String edit(String picture, String value) {
        Picture p = PictureParser.parse(picture);
        byte[] out = NumericEditor.edit(Decimal.parse(value), p, CodePages.IBM_1047);
        assertEquals(p.size(), out.length, "編集結果の長さは PICTURE のバイト長と一致する");
        return CodePages.IBM_1047.decode(out);
    }

    @ParameterizedTest(name = "PIC {0} に {1} を編集すると [{2}]")
    @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
            // 区切りの直後から期待値が始まる。末尾の空白も期待値の一部である。
            // ゼロ抑制 (Z)
            "ZZZ,ZZ9.99 | 1234.56  |  1,234.56",
            "ZZZ,ZZ9.99 | 0.05     |      0.05",
            "ZZZ,ZZ9.99 | 0        |      0.00",
            "ZZZ,ZZ9.99 | 99999.99 | 99,999.99",
            // 小切手保護 (*)
            "**,**9.99  | 12.34    |****12.34",
            "**,**9.99  | 1234.56  |*1,234.56",
            // 浮動挿入 (通貨記号)
            "$$$,$$9.99 | 1234.56  | $1,234.56",
            "$$$,$$9.99 | 1.23     |     $1.23",
            "$$$,$$9.99 | 99999.99 |$99,999.99",
            // 浮動挿入 (符号)
            "----9      | -123     | -123",
            "----9      | 123      |  123",
            "----9      | -12345   |-2345",
            // 固定の符号
            "+9(4)      | -123     |-0123",
            "+9(4)      | 123      |+0123",
            "-9(4)      | -123     |-0123",
            "-9(4)      | 123      | 0123",
            // CR / DB
            "9(3).99CR  | -12.34   |012.34CR",
            "9(3).99CR  | 12.34    |012.34  ",
            "9(3).99DB  | -12.34   |012.34DB",
            // 単純挿入
            "99/99/99   | 311225   |31/12/25",
            "99B99      | 1234     |12 34",
            // 浮動挿入・抑制の並びの<b>すぐ右にある</b>挿入文字も、その並びの一部である。
            // 消さないと「   $,987.65」になる (NC105A の EDIT-TEST-F1-124 から F1-129)
            "$$$,999.99 | 987.65   |   $987.65",
            "$$$B999.99 | 123.45   |   $123.45",
            "+++,999.99 | 321.01   |   +321.01",
            "---,999.99 | -12.98   |   -012.98",
            "***,999.99 | 567.43   |****567.43",
            "ZZZ,999.99 | 0        |    000.00",
            // 並びの中にある挿入文字は、有効数字が始まれば残る
            "ZZ,ZZZ.99  | 1234.56  | 1,234.56",
            "ZZ,ZZZ.99  | 12.34    |    12.34",
    })
    @DisplayName("数値編集のゴールデン値 (FR-030)")
    void editing(String picture, String value, String expected) {
        assertEquals(expected, edit(picture.trim(), value.trim()));
    }

    @Test
    @DisplayName("全桁が Z で値がゼロなら項目全体が空白になる (FR-030)")
    void allZeroSuppressedBecomesSpaces() {
        assertEquals("    ", edit("ZZZZ", "0"));
        assertEquals("   1", edit("ZZZZ", "1"));
    }

    @Test
    @DisplayName("全桁が * で値がゼロなら小数点を残してアスタリスクになる (FR-030)")
    void allAsteriskSuppressedKeepsDecimalPoint() {
        assertEquals("****", edit("****", "0"));
        assertEquals("***.**", edit("***.**", "0"));
    }

    @Test
    @DisplayName("BLANK WHEN ZERO は値がゼロのとき項目全体を空白にする (FR-030)")
    void blankWhenZero() {
        Picture p = PictureParser.parse("ZZZ.99").withBlankWhenZero(true);
        byte[] out = NumericEditor.edit(Decimal.zero(2), p, CodePages.IBM_1047);
        assertEquals("      ", CodePages.IBM_1047.decode(out));

        byte[] nonZero = NumericEditor.edit(Decimal.parse("1.50"), p, CodePages.IBM_1047);
        assertEquals("  1.50", CodePages.IBM_1047.decode(nonZero));
    }

    @Test
    @DisplayName("編集結果は EBCDIC のバイト列である (FR-050)")
    void outputIsEbcdic() {
        byte[] out = NumericEditor.edit(Decimal.parse("1"), PictureParser.parse("ZZ9"),
                CodePages.IBM_1047);
        // "  1" = 0x40 0x40 0xF1
        assertEquals("4040F1", dev.cobolonjava.runtime.TestSupport.hex(out));
    }
}
