package dev.cobolonjava.runtime.data;

import static dev.cobolonjava.runtime.TestSupport.assertHex;
import static dev.cobolonjava.runtime.TestSupport.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.decimal.DataException;
import dev.cobolonjava.runtime.decimal.Decimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("V1")
class PackedDecimalTest {

    @ParameterizedTest(name = "PIC S9({0}) COMP-3 は {1} バイト")
    @CsvSource({"1,1", "2,2", "3,2", "4,3", "5,3", "6,4", "7,4", "9,5", "18,10"})
    @DisplayName("バイト長は 桁数/2+1 (FR-031)")
    void byteLength(int digits, int expected) {
        assertEquals(expected, PackedDecimal.byteLength(digits));
    }

    @Test
    @DisplayName("符号ニブルは正が C、負が D、符号なしが F (FR-031)")
    void signNibbles() {
        assertHex("12345C", PackedDecimal.encode(Decimal.parse("12345"), 5, 0, true));
        assertHex("12345D", PackedDecimal.encode(Decimal.parse("-12345"), 5, 0, true));
        assertHex("12345F", PackedDecimal.encode(Decimal.parse("12345"), 5, 0, false));
    }

    @Test
    @DisplayName("桁数が偶数のときは先頭に未使用のゼロニブルが生じる (FR-031)")
    void evenDigitCountHasLeadingZeroNibble() {
        // PIC S9(4) COMP-3 は 3 バイト。ニブルは 5 個で、先頭 1 個は未使用となり 0 で埋まる。
        assertHex("01234C", PackedDecimal.encode(Decimal.parse("1234"), 4, 0, true));
    }

    @Test
    @DisplayName("小数点位置はバイト列に現れない (FR-031)")
    void scaleIsImplied() {
        // PIC S9(3)V99 COMP-3 の 123.45 は 12345C
        assertHex("12345C", PackedDecimal.encode(Decimal.parse("123.45"), 5, 2, true));
    }

    @Test
    @DisplayName("桁があふれた場合は上位桁が黙って切り捨てられる (FR-043 の SIZE ERROR 非指定時)")
    void highOrderTruncation() {
        assertHex("23456C", PackedDecimal.encode(Decimal.parse("123456"), 5, 0, true));
    }

    @Test
    @DisplayName("復号は広い符号ニブルを受理する (A C E F が正、B D が負)")
    void decodeAcceptsAllValidSignNibbles() {
        assertEquals(0, Decimal.parse("123").compareTo(
                PackedDecimal.decode(bytes("123C"), 0, NumProcMode.NOPFD)));
        assertEquals(0, Decimal.parse("123").compareTo(
                PackedDecimal.decode(bytes("123F"), 0, NumProcMode.NOPFD)));
        assertEquals(0, Decimal.parse("123").compareTo(
                PackedDecimal.decode(bytes("123A"), 0, NumProcMode.NOPFD)));
        assertEquals(0, Decimal.parse("-123").compareTo(
                PackedDecimal.decode(bytes("123D"), 0, NumProcMode.NOPFD)));
        assertEquals(0, Decimal.parse("-123").compareTo(
                PackedDecimal.decode(bytes("123B"), 0, NumProcMode.NOPFD)));
    }

    @Test
    @DisplayName("負のゼロは符号を保って復号される")
    void negativeZeroSurvivesDecode() {
        Decimal d = PackedDecimal.decode(bytes("000D"), 0, NumProcMode.NOPFD);
        assertEquals(-1, d.sign());
        assertEquals(0, d.signum());
        // 再符号化するとバイト列が一致する = MOVE でバイト列が変化しない
        assertHex("000D", PackedDecimal.encode(d, 3, 0, true));
    }

    @Test
    @DisplayName("NUMPROC(NOPFD) は不正な数字ニブルを S0C7 相当として検出する (FR-033, FR-141)")
    void invalidDigitNibbleIsDetected() {
        assertThrows(DataException.class,
                () -> PackedDecimal.decode(bytes("1A3C"), 0, NumProcMode.NOPFD));
        assertThrows(DataException.class,
                () -> PackedDecimal.decode(bytes("1233"), 0, NumProcMode.NOPFD),
                "0x3 は符号ニブルとして妥当でない");
    }

    @Test
    @DisplayName("NUMPROC(PFD) は検査しない (FR-033)")
    void pfdSkipsValidation() {
        // 例外にならないこと。値そのものは未定義領域であり provisional P-003 の扱いによる。
        PackedDecimal.decode(bytes("1A3C"), 0, NumProcMode.PFD);
    }

    @Test
    @DisplayName("符号化と復号は往復する")
    void roundTrip() {
        for (String v : new String[] {"0", "1", "-1", "99999", "-99999", "12345", "-12345"}) {
            Decimal original = Decimal.parse(v);
            byte[] encoded = PackedDecimal.encode(original, 5, 0, true);
            Decimal decoded = PackedDecimal.decode(encoded, 0, NumProcMode.NOPFD);
            assertEquals(0, original.compareTo(decoded), "round trip failed for " + v);
        }
    }
}
