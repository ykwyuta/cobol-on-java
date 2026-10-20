package dev.cobolonjava.cics.tn3270;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.CicsTerminalScreen;
import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Color;
import dev.cobolonjava.cics.bms.BmsModel.Highlight;
import dev.cobolonjava.cics.bms.BmsModel.Position;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot.FieldState;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Tn3270DataStreamTest {

    private static final Tn3270Device MODEL_2 = Tn3270Device.of("IBM-3278-2").orElseThrow();
    private static final Tn3270Device MODEL_5_E = Tn3270Device.of("IBM-3279-5-E").orElseThrow();

    private static FieldState field(String name, int row, int column, int length, String data,
                                    EnumSet<BasicAttribute> attributes, Optional<Color> color,
                                    Optional<Highlight> highlight) {
        return new FieldState(Optional.ofNullable(name), 1, new Position(row, column), length, attributes, color,
                highlight, data, false);
    }

    private static int[] unsigned(byte[] bytes) {
        int[] out = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = bytes[i] & 0xFF;
        }
        return out;
    }

    @Test
    @DisplayName("24 x 80 の map は Erase/Write、属性 byte は保護・明るさ・MDT を 6 bit の表で符号化し、IC で cursor を置く")
    void writesMapWithEraseWrite() {
        BmsScreenSnapshot screen = new BmsScreenSnapshot("SET", "MAP", 24, 80, List.of(
                field(null, 1, 1, 2, "HI", EnumSet.of(BasicAttribute.PROT, BasicAttribute.BRT),
                        Optional.empty(), Optional.empty()),
                field("NAME", 2, 1, 3, "", EnumSet.of(BasicAttribute.UNPROT, BasicAttribute.NORM,
                        BasicAttribute.FSET), Optional.empty(), Optional.empty())),
                81, true, false);

        int[] stream = unsigned(Tn3270DataStream.screen(new CicsTerminalScreen.MapScreen(screen), MODEL_2, false,
                CodePages.IBM_037));

        // EW, WCC (keyboard restore + reset MDT = X'03' -> X'C3')
        // SBA 0 (X'4040'), SF 保護+明るい (X'28' -> X'E8'), "HI"
        // SBA 80 (行 2 の先頭: 1,16 -> X'C150'), SF 非保護+MDT (X'01' -> X'C1')
        // SBA 81 (X'C1D1'), IC
        assertArrayEquals(new int[] {0xF5, 0xC3, 0x11, 0x40, 0x40, 0x1D, 0xE8, 0xC8, 0xC9,
            0x11, 0xC1, 0x50, 0x1D, 0xC1, 0x11, 0xC1, 0xD1, 0x13}, stream);
    }

    @Test
    @DisplayName("拡張属性を受ける端末には色と強調を SFE で送り、受けない端末には SF だけを送る")
    void sendsExtendedAttributesOnlyToExtendedTerminals() {
        BmsScreenSnapshot screen = new BmsScreenSnapshot("SET", "MAP", 24, 80, List.of(
                field(null, 1, 1, 1, "X", EnumSet.of(BasicAttribute.ASKIP, BasicAttribute.DRK),
                        Optional.of(Color.TURQUOISE), Optional.of(Highlight.UNDERLINE))),
                -1, false, true);

        int[] extended = unsigned(Tn3270DataStream.screen(new CicsTerminalScreen.MapScreen(screen), MODEL_5_E, true,
                CodePages.IBM_037));
        int[] plain = unsigned(Tn3270DataStream.screen(new CicsTerminalScreen.MapScreen(screen), MODEL_5_E, false,
                CodePages.IBM_037));

        // WCC は alarm + reset MDT (X'05' -> X'C5')。キーボードは開けない。ASKIP + DRK = X'3C' -> X'7C'
        assertArrayEquals(new int[] {0xF5, 0xC5, 0x11, 0x40, 0x40, 0x29, 0x03, 0xC0, 0x7C, 0x41, 0xF4, 0x42, 0xF5,
            0xE7}, extended);
        assertArrayEquals(new int[] {0xF5, 0xC5, 0x11, 0x40, 0x40, 0x1D, 0x7C, 0xE7}, plain);
    }

    @Test
    @DisplayName("既定の大きさに入らない 27 x 132 の map は model 5 で Erase/Write Alternate、model 2 では断る")
    void usesAlternateSizeForWideMaps() {
        BmsScreenSnapshot screen = new BmsScreenSnapshot("SET", "WIDE", 27, 132, List.of(
                field("F", 2, 1, 1, "", EnumSet.of(BasicAttribute.UNPROT), Optional.empty(), Optional.empty())),
                -1, true, false);

        int[] stream = unsigned(Tn3270DataStream.screen(new CicsTerminalScreen.MapScreen(screen), MODEL_5_E, false,
                CodePages.IBM_037));

        assertEquals(0x7E, stream[0]);
        // 行 2 の先頭は 132 番地 (2,4 -> X'C2C4')。24 x 80 で数えれば 80 番地になる
        assertArrayEquals(new int[] {0x11, 0xC2, 0xC4}, Arrays.copyOfRange(stream, 2, 5));
        assertThrows(IllegalArgumentException.class, () -> Tn3270DataStream.screen(
                new CicsTerminalScreen.MapScreen(screen), MODEL_2, false, CodePages.IBM_037));
    }

    @Test
    @DisplayName("SEND TEXT の画面は書式なしで先頭から書き、画面に入らないぶんは落とす")
    void writesTextUnformatted() {
        int[] stream = unsigned(Tn3270DataStream.screen(new CicsTerminalScreen.TextScreen("A".repeat(2000),
                true, false), MODEL_2, false, CodePages.IBM_037));

        assertArrayEquals(new int[] {0xF5, 0xC3, 0x11, 0x40, 0x40}, Arrays.copyOf(stream, 5));
        assertEquals(5 + 1920, stream.length);
    }

    @Test
    @DisplayName("buffer address は 12 bit と 14 bit のどちらでも読める")
    void readsTwelveAndFourteenBitAddresses() {
        ByteArrayOutputStream twelve = new ByteArrayOutputStream();
        Tn3270DataStream.writeAddress(twelve, 1919, 1920);
        byte[] small = twelve.toByteArray();
        assertEquals(1919, Tn3270DataStream.readAddress(small[0] & 0xFF, small[1] & 0xFF));

        ByteArrayOutputStream fourteen = new ByteArrayOutputStream();
        Tn3270DataStream.writeAddress(fourteen, 9919, 62 * 160);
        byte[] large = fourteen.toByteArray();
        assertEquals(0, large[0] & 0xC0);
        assertEquals(9919, Tn3270DataStream.readAddress(large[0] & 0xFF, large[1] & 0xFF));
    }

    @Test
    @DisplayName("Read Modified は AID、cursor、SBA ごとの field に分け、CLEAR は AID だけの short read")
    void parsesInbound() {
        byte[] record = {0x7D, (byte) 0xC1, (byte) 0xD1, 0x11, (byte) 0xC1, (byte) 0xD1, (byte) 0xC1, (byte) 0xC2,
            0x11, (byte) 0xC2, (byte) 0x60, (byte) 0xF1};

        Tn3270DataStream.Inbound inbound = Tn3270DataStream.inbound(record);

        assertEquals(0x7D, inbound.aid());
        assertEquals(81, inbound.cursor());
        assertEquals(2, inbound.fields().size());
        assertEquals(81, inbound.fields().get(0).address());
        assertArrayEquals(new byte[] {(byte) 0xC1, (byte) 0xC2}, inbound.fields().get(0).data());
        assertEquals(2 * 64 + 32, inbound.fields().get(1).address());

        Tn3270DataStream.Inbound clear = Tn3270DataStream.inbound(new byte[] {0x6D});
        assertEquals(-1, clear.cursor());
        assertTrue(clear.fields().isEmpty());

        // 書式なしの画面は SBA を持たない
        Tn3270DataStream.Inbound unformatted = Tn3270DataStream.inbound(new byte[] {0x7D, 0x40, (byte) 0xC4,
            (byte) 0xE3, (byte) 0xE2});
        assertEquals(-1, unformatted.fields().get(0).address());
    }

    @Test
    @DisplayName("装置の型は 3278 / 3279 の model 2〜5 だけを受け、-E で拡張属性を知る")
    void readsDeviceTypes() {
        assertEquals(43, Tn3270Device.of("IBM-3278-4").orElseThrow().alternateRows());
        assertTrue(Tn3270Device.of("ibm-3279-2-e").orElseThrow().extended());
        assertTrue(Tn3270Device.of("IBM-DYNAMIC").isEmpty());
        assertTrue(Tn3270Device.of("IBM-3287-1").isEmpty());
    }
}
