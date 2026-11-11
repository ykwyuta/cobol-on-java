package dev.cobolonjava.pli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数の PICTURE の編集。期待値は Zeus の {@code crates/zeus-pli/src/picture.rs} の検査を
 * そのまま移した (同じ権利者の処理系)。実機と突き合わせた値ではない。
 */
class PliPictureTest {

    private static String edit(String picture, String value) {
        return PliPicture.parse(picture).edit(new BigDecimal(value)).text();
    }

    @Test
    @DisplayName("Zeus の picture.rs と同じ像に編集する")
    void editingMatchesZeus() {
        assertEquals("00123", edit("99999", "123"));
        assertEquals("12345", edit("999V99", "123.45"));
        assertEquals("  100", edit("ZZZ99", "100"));
        assertEquals("     ", edit("ZZZZZ", "0"));
        assertEquals("  123", edit("ZZZV99", "1.23"));
        assertEquals("**100", edit("*****", "100"));
        assertEquals("1,234", edit("9,999", "1234"));
        assertEquals("   12/34", edit("ZZ/ZZ/ZZ", "1234"));
        assertEquals("12.34", edit("ZZ.VZZ", "12.34"));
        assertEquals("  .03", edit("ZZV.ZZ", "0.03"));
        assertEquals("$123.45", edit("$999V.99", "123.45"));
        assertEquals("  $1.23", edit("$$$9V.99", "1.23"));
        assertEquals(" $1,234", edit("$$$,999", "1234"));
        assertEquals("+  2.45", edit("SZZZV.99", "2.45"));
        assertEquals("-123.45", edit("-999V.99", "-123.45"));
        assertEquals("123.45+", edit("999V.99S", "123.45"));
        assertEquals("    -4", edit("SS,SS9", "-4"));
        assertEquals("$12.34  ", edit("$ZZV.99DB", "12.34"));
        assertEquals("102A", edit("999I", "1021"));
        assertEquals("102J", edit("Z99R", "-1021"));
        assertEquals("  1  ", edit("YYYYY", "100"));
        assertEquals("12", edit("99F(2)", "1200"));
    }

    @Test
    @DisplayName("像から値を戻し、浮動小数の PICTURE は仮数の頭の桁が 0 でないように指数を決める")
    void valuesAndFloatPictures() {
        PliPicture signed = PliPicture.parse("S999V99");
        assertEquals(5, signed.precision);
        assertEquals(2, signed.scale);
        assertEquals(new BigDecimal("-123.45"), signed.value("-12345"));
        assertEquals(7, PliPicture.parse("$$$9V.99").length);
        PliPicture f = PliPicture.parse("S9V.999ES99");
        assertEquals(10, f.length);
        assertEquals("+1.234E+03", f.editFloat(new BigDecimal("1234.5")).text());
        assertEquals("-5.000E-02", f.editFloat(new BigDecimal("-0.05")).text());
        assertEquals("1250+0", PliPicture.parse("99V99KS9").editFloat(new BigDecimal("12.5"))
                .text());
    }
}
