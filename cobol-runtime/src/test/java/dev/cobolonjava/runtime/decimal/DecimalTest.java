package dev.cobolonjava.runtime.decimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("V1")
class DecimalTest {

    @ParameterizedTest(name = "{0} を小数 0 桁へ {1} で丸めると {2}")
    @CsvSource({
            "2.5,  NEAREST_AWAY_FROM_ZERO,  3",
            "2.5,  NEAREST_EVEN,            2",
            "2.5,  NEAREST_TOWARD_ZERO,     2",
            "2.5,  TOWARD_GREATER,          3",
            "2.5,  TOWARD_LESSER,           2",
            "2.5,  AWAY_FROM_ZERO,          3",
            "2.5,  TRUNCATION,              2",
            "3.5,  NEAREST_EVEN,            4",
            "-2.5, NEAREST_AWAY_FROM_ZERO, -3",
            "-2.5, NEAREST_EVEN,           -2",
            "-2.5, NEAREST_TOWARD_ZERO,    -2",
            "-2.5, TOWARD_GREATER,         -2",
            "-2.5, TOWARD_LESSER,          -3",
            "-2.5, AWAY_FROM_ZERO,         -3",
            "-2.5, TRUNCATION,             -2",
            "2.4,  NEAREST_AWAY_FROM_ZERO,  2",
            "2.6,  TRUNCATION,              2",
    })
    @DisplayName("ROUNDED 句の丸めモード (FR-042)")
    void roundingModes(String value, CobolRounding rounding, String expected) {
        assertEquals(expected, Decimal.parse(value).rescale(0, rounding).toString());
    }

    @Test
    @DisplayName("PROHIBITED は丸めが必要になった時点で誤りとする (FR-042)")
    void prohibitedRounding() {
        assertThrows(ArithmeticException.class,
                () -> Decimal.parse("2.5").rescale(0, CobolRounding.PROHIBITED));
        // 丸めが不要なら通る
        assertEquals("2", Decimal.parse("2.0").rescale(0, CobolRounding.PROHIBITED).toString());
    }

    @Test
    @DisplayName("負のゼロを表現できる。パック10進の -0 を MOVE で壊さないために必要")
    void negativeZeroIsRepresentable() {
        Decimal negZero = Decimal.zero(0).withSign(-1);
        assertTrue(negZero.isZero());
        assertEquals(-1, negZero.sign());
        assertEquals(0, negZero.signum(), "数値としての符号は 0");
        assertEquals(0, negZero.compareTo(Decimal.zero(0)), "数値比較では +0 と等しい");
        assertFalse(negZero.equals(Decimal.zero(0)), "バイト表現としては区別される");
    }

    @Test
    @DisplayName("演算結果がゼロのときの符号は正 (provisional P-001: Hercules 検証待ち)")
    void zeroResultOfArithmeticIsPositive() {
        Decimal r = Decimal.parse("5").subtract(Decimal.parse("5"));
        assertTrue(r.isZero());
        assertEquals(1, r.sign());
    }

    @Test
    @DisplayName("10 進演算は 2 進浮動小数を経由しない (FR-040)")
    void exactDecimalArithmetic() {
        // 0.1 + 0.2 が 0.30000000000000004 にならないこと
        assertEquals("0.3", Decimal.parse("0.1").add(Decimal.parse("0.2")).toString());
    }

    @Test
    @DisplayName("除算はゼロ除算を S0CB 相当として報告する (FR-141)")
    void divisionByZero() {
        assertThrows(DecimalDivideException.class,
                () -> Decimal.parse("1").divide(Decimal.zero(0), 2, CobolRounding.TRUNCATION));
    }

    @Test
    @DisplayName("storedDigits は P による桁位置指定を扱える (FR-030)")
    void storedDigitsHandlesScalingPositions() {
        // PIC 999PPP 相当: scale = -3。値 5000 は数字列 005 として格納される
        assertEquals("005", Decimal.parse("5000").storedDigits(3, -3));
        // PIC PPP999 相当: scale = 6。値 0.000123 は数字列 123
        assertEquals("123", Decimal.parse("0.000123").storedDigits(3, 6));
        // 通常の PIC 9(3)V99 相当
        assertEquals("12345", Decimal.parse("123.45").storedDigits(5, 2));
        // 上位桁のあふれは切り捨て
        assertEquals("2345", Decimal.parse("123.45").storedDigits(4, 2));
    }

    @Test
    @DisplayName("fitsInDigits は SIZE ERROR の判定に使える (FR-043)")
    void fitsInDigits() {
        assertTrue(Decimal.parse("999.99").fitsInDigits(5, 2));
        assertFalse(Decimal.parse("1000.00").fitsInDigits(5, 2));
        assertTrue(Decimal.parse("999.999").fitsInDigits(5, 2), "小数部の切り捨ては SIZE ERROR ではない");
        assertFalse(Decimal.parse("-1000.00").fitsInDigits(5, 2));
    }
}
