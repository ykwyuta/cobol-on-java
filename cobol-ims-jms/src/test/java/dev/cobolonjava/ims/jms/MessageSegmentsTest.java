package dev.cobolonjava.ims.jms;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** キューを流れる電文の形 (LL / ZZ 付き、ADR-0014、暫定判断 P-162)。 */
@Tag("V1")
class MessageSegmentsTest {

    private static final CodePage EBCDIC = CodePages.DEFAULT;

    @Test
    @DisplayName("セグメントは LL (LL と ZZ を含む長さ) と ZZ を付けて並び、読み戻すと本体に戻る")
    void segmentsCarryTheLengthAndFlagPrefix() {
        byte[] encoded = MessageSegments.encode(List.of(EBCDIC.encode("IBLOGIN1"), EBCDIC.encode("PW")));

        assertEquals(12 + 6, encoded.length);
        assertEquals(12, ((encoded[0] & 0xFF) << 8) | (encoded[1] & 0xFF));
        assertEquals(0, encoded[2]);
        assertEquals(0, encoded[3]);
        assertEquals(List.of("IBLOGIN1", "PW"),
                MessageSegments.decode(encoded).stream().map(EBCDIC::decode).toList());
    }

    @Test
    @DisplayName("2 進の欄を含むセグメントもバイトのまま往復する")
    void binaryFieldsSurviveTheRoundTrip() {
        byte[] segment = {0, 0, 0, 1, (byte) 0xF0, (byte) 0xC1, 0x0C};

        assertArrayEquals(segment, MessageSegments.decode(MessageSegments.encode(List.of(segment))).get(0));
    }

    @Test
    @DisplayName("長さの合わない電文は、途中まで読まずに断る")
    void aTruncatedMessageIsRefused() {
        // 長さが 12 と書いてあるのに 6 byte しかない
        byte[] truncated = {0, 12, 0, 0, 1, 2};
        IllegalArgumentException cut = assertThrows(IllegalArgumentException.class,
                () -> MessageSegments.decode(truncated));
        assertTrue(cut.getMessage().contains("does not fit"), cut.getMessage());

        assertThrows(IllegalArgumentException.class, () -> MessageSegments.decode(new byte[] {0, 12}));
        assertThrows(IllegalArgumentException.class, () -> MessageSegments.decode(new byte[0]));
        // LL が前置きより短い
        assertThrows(IllegalArgumentException.class, () -> MessageSegments.decode(new byte[] {0, 3, 0, 0}));
    }

    @Test
    @DisplayName("65535 byte を越えるセグメントは LL に入らないので断る")
    void anOversizedSegmentIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> MessageSegments.encode(List.of(new byte[0xFFFF + 1])));
    }
}
