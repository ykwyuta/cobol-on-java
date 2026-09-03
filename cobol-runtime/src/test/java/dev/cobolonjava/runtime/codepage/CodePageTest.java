package dev.cobolonjava.runtime.codepage;

import static dev.cobolonjava.runtime.TestSupport.assertHex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.UnsupportedCharsetException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class CodePageTest {

    @Test
    @DisplayName("EBCDIC の英数字は EBCDIC のバイト値で符号化される (FR-050)")
    void ebcdicEncoding() {
        assertHex("C1C2C3", CodePages.IBM_1047.encode("ABC"));
        assertHex("F0F1F2", CodePages.IBM_1047.encode("012"));
        assertEquals((byte) 0x40, CodePages.IBM_1047.space());
        assertEquals((byte) 0x4E, CodePages.IBM_1047.ch('+'));
        assertEquals((byte) 0x60, CodePages.IBM_1047.ch('-'));
    }

    @Test
    @DisplayName("数字のゾーンニブルは EBCDIC では F、ASCII では 3 (FR-031)")
    void zoneNibble() {
        assertEquals(0xF, CodePages.IBM_1047.zoneNibble());
        assertEquals((byte) 0xF7, CodePages.IBM_1047.digit(7));
        assertEquals(0x3, CodePages.ASCII.zoneNibble());
        assertEquals((byte) 0x37, CodePages.ASCII.digit(7));
    }

    @Test
    @DisplayName("EBCDIC の照合順序では英字が数字より小さい (FR-053)")
    void ebcdicCollatingSequence() {
        byte[] letter = CodePages.IBM_1047.encode("A");
        byte[] digit = CodePages.IBM_1047.encode("0");
        assertTrue(CodePages.IBM_1047.compare(letter, digit) < 0,
                "EBCDIC では 'A' (0xC1) < '0' (0xF0) でなければならない");

        // ASCII では逆になる。この違いがソート順の非互換の主因である。
        assertTrue(CodePages.ASCII.compare(CodePages.ASCII.encode("A"),
                CodePages.ASCII.encode("0")) > 0);
    }

    @Test
    @DisplayName("短いほうは空白で埋めて比較される")
    void comparePadsWithSpaces() {
        CodePage cp = CodePages.IBM_1047;
        assertEquals(0, cp.compare(cp.encode("AB"), cp.encode("AB  ")));
        assertTrue(cp.compare(cp.encode("AB"), cp.encode("ABA")) < 0);
    }

    @Test
    @DisplayName("未対応のコードページは黙って既定へ倒さずエラーにする (FR-181)")
    void unsupportedCodePageIsReported() {
        assertThrows(UnsupportedCharsetException.class, () -> CodePages.forName("IBM-1390"));
    }

    @Test
    @DisplayName("数値指定でもコードページを引ける")
    void lookupByNumber() {
        assertEquals(CodePages.IBM_1047, CodePages.forName("1047"));
        assertEquals(CodePages.IBM_930, CodePages.forName("CP930"));
    }
}
