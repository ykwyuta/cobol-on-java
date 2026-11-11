package dev.cobolonjava.runtime.codepage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 表で引く CCSID 1390 / 1399。
 *
 * <p>期待値は ICU の表と、Zeus の検査スイート (ZN112A、ZN113A) と同じである。
 * 実機とは突き合わせていない。
 */
class TableCharsetTest {

    @Test
    @DisplayName("1399 は英小文字を持ち (939 と同じ位置)、日本語を SO / SI で囲む")
    void encodesMixedTextIn1399() {
        CodePage page = CodePages.forName("1399");
        assertArrayEquals(new byte[] {(byte) 0x81, (byte) 0x82, (byte) 0x83},
                page.encode("abc"));
        assertArrayEquals(new byte[] {(byte) 0xC1, 0x0E, 0x45, 0x62, 0x0F, (byte) 0xC2},
                page.encode("A日B"));
        assertEquals("A日B", page.decode(new byte[] {(byte) 0xC1, 0x0E, 0x45, 0x62, 0x0F,
            (byte) 0xC2}));
    }

    @Test
    @DisplayName("1390 は 930 と同じく片仮名を 1 バイトに持つ")
    void keepsKatakanaInSingleBytesIn1390() {
        CodePage page = CodePages.forName("1390");
        // 半角の ア は 930 / 1390 で X'81'
        assertArrayEquals(new byte[] {(byte) 0x81}, page.encode("ｱ"));
        assertEquals("ｱ", page.decode(new byte[] {(byte) 0x81}));
    }

    @Test
    @DisplayName("表に無い字は書けない")
    void refusesUnmappableCharacters() {
        assertFalse(CodePages.forName("1399").canEncode("😀"));
    }
}
