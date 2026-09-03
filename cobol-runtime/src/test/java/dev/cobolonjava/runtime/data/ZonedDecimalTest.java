package dev.cobolonjava.runtime.data;

import static dev.cobolonjava.runtime.TestSupport.assertHex;
import static dev.cobolonjava.runtime.TestSupport.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.decimal.DataException;
import dev.cobolonjava.runtime.decimal.Decimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class ZonedDecimalTest {

    @Test
    @DisplayName("符号は既定では最終バイトのゾーンニブルに置かれる (FR-031)")
    void trailingSignInZoneNibble() {
        assertHex("F1F2F3F4C5", ZonedDecimal.encode(Decimal.parse("12345"), 5, 0,
                SignPosition.TRAILING, CodePages.IBM_1047));
        assertHex("F1F2F3F4D5", ZonedDecimal.encode(Decimal.parse("-12345"), 5, 0,
                SignPosition.TRAILING, CodePages.IBM_1047));
    }

    @Test
    @DisplayName("符号なし項目には絶対値が格納され、ゾーンは F のままになる")
    void unsignedStoresAbsoluteValue() {
        assertHex("F1F2F3F4F5", ZonedDecimal.encode(Decimal.parse("12345"), 5, 0,
                SignPosition.UNSIGNED, CodePages.IBM_1047));
        assertHex("F1F2F3F4F5", ZonedDecimal.encode(Decimal.parse("-12345"), 5, 0,
                SignPosition.UNSIGNED, CodePages.IBM_1047));
    }

    @Test
    @DisplayName("SIGN IS LEADING は先頭バイトのゾーンニブルに符号を置く (FR-031)")
    void leadingSign() {
        assertHex("D1F2F3F4F5", ZonedDecimal.encode(Decimal.parse("-12345"), 5, 0,
                SignPosition.LEADING, CodePages.IBM_1047));
        assertHex("C1F2F3F4F5", ZonedDecimal.encode(Decimal.parse("12345"), 5, 0,
                SignPosition.LEADING, CodePages.IBM_1047));
    }

    @Test
    @DisplayName("SIGN IS SEPARATE は符号専用の 1 バイトを追加する (FR-031)")
    void separateSignAddsAByte() {
        assertEquals(6, ZonedDecimal.byteLength(5, SignPosition.TRAILING_SEPARATE));
        // EBCDIC の '-' は 0x60、'+' は 0x4E
        assertHex("F1F2F3F4F560", ZonedDecimal.encode(Decimal.parse("-12345"), 5, 0,
                SignPosition.TRAILING_SEPARATE, CodePages.IBM_1047));
        assertHex("4EF1F2F3F4F5", ZonedDecimal.encode(Decimal.parse("12345"), 5, 0,
                SignPosition.LEADING_SEPARATE, CodePages.IBM_1047));
    }

    @Test
    @DisplayName("復号は符号位置以外のゾーンニブルを無視する (ホストの PACK 命令と同じ)")
    void decodeIgnoresNonSignZones() {
        // ゾーンが 0 のバイト列でも数字として読める
        Decimal d = ZonedDecimal.decode(bytes("0102030405"), 0,
                SignPosition.UNSIGNED, CodePages.IBM_1047, NumProcMode.NOPFD);
        assertEquals(0, Decimal.parse("12345").compareTo(d));
    }

    @Test
    @DisplayName("数字ニブルが 0〜9 でなければ S0C7 相当として検出する (FR-033, FR-141)")
    void invalidDigitIsDetected() {
        assertThrows(DataException.class, () -> ZonedDecimal.decode(bytes("F1F2FAF4F5"), 0,
                SignPosition.UNSIGNED, CodePages.IBM_1047, NumProcMode.NOPFD));
    }

    @Test
    @DisplayName("符号化と復号は往復する")
    void roundTrip() {
        for (SignPosition sp : SignPosition.values()) {
            for (String v : new String[] {"0", "1", "-1", "12345", "-12345"}) {
                Decimal original = Decimal.parse(v);
                byte[] encoded = ZonedDecimal.encode(original, 5, 0, sp, CodePages.IBM_1047);
                assertEquals(ZonedDecimal.byteLength(5, sp), encoded.length);
                Decimal decoded = ZonedDecimal.decode(encoded, 0, sp, CodePages.IBM_1047,
                        NumProcMode.NOPFD);
                Decimal expected = sp.isSigned() ? original : original.abs();
                assertEquals(0, expected.compareTo(decoded),
                        "round trip failed for " + v + " with " + sp);
            }
        }
    }
}
