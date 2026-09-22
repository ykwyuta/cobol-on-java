package dev.cobolonjava.cics.bms;

import static dev.cobolonjava.cics.bms.BmsParserTest.card;
import static dev.cobolonjava.cics.bms.BmsParserTest.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Color;
import dev.cobolonjava.cics.bms.BmsModel.ExtendedAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Highlight;
import dev.cobolonjava.cics.bms.BmsModel.Mapset;
import dev.cobolonjava.cics.bms.BmsScreenComposer.SendOptions;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.util.EnumSet;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** SEND MAP の合成規則。 */
@Tag("V1")
class BmsScreenComposerTest {

    private static final CodePage CP = CodePages.IBM_1047;

    private static final Mapset MAPSET = BmsParser.parse(source(
            card("SCRSET   DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,STORAGE=AUTO,", true),
            card("               CTRL=FREEKB,EXTATT=YES,TIOAPFX=YES,", true),
            card("               DSATTS=(COLOR,HILIGHT)", false),
            card("SCRMP    DFHMDI SIZE=(24,80)", false),
            card("         DFHMDF POS=(1,1),LENGTH=5,INITIAL='TITLE',ATTRB=(PROT,NORM)", false),
            card("CUSTNO   DFHMDF POS=(5,17),LENGTH=10,ATTRB=(NORM,NUM,IC),COLOR=GREEN,", true),
            card("               HILIGHT=UNDERLINE", false),
            card("ROW      DFHMDF POS=(9,1),LENGTH=10,ATTRB=(PROT,FSET),OCCURS=2", false),
            card("MESSAGE  DFHMDF POS=(23,1),LENGTH=12,ATTRB=(BRT,PROT),INITIAL='READY'", false),
            card("         DFHMSD TYPE=FINAL", false)));
    private static final BmsModel.Map MAP = MAPSET.maps().get(0);
    private static final BmsSymbolicLayout LAYOUT = BmsSymbolicLayout.of(MAPSET, MAP);

    private static SendOptions options(boolean erase, boolean mapOnly, boolean dataOnly,
                                       boolean resetModified, boolean symbolicCursor) {
        return new SendOptions(erase, mapOnly, dataOnly, false, false, resetModified,
                OptionalInt.empty(), symbolicCursor);
    }

    private static BmsSymbolicLayout.Slot slot(String name, int occurrence) {
        return LAYOUT.slots().stream()
                .filter(s -> s.name().equals(name) && s.occurrence() == occurrence)
                .findFirst().orElseThrow();
    }

    private static void putData(byte[] symbolic, String name, int occurrence, String text) {
        BmsSymbolicLayout.Slot slot = slot(name, occurrence);
        byte[] bytes = CP.encode(String.format("%-" + slot.dataLength() + "s", text));
        System.arraycopy(bytes, 0, symbolic, slot.dataOffset(), bytes.length);
    }

    @Test
    @DisplayName("位置表は写し句と同じ並び: 接頭12、L・F・拡張属性・データ、OCCURSは横に並ぶ")
    void layoutMatchesTheSymbolicMapWriter() {
        assertEquals(12, slot("CUSTNO", 1).lengthOffset());
        assertEquals(17, slot("CUSTNO", 1).dataOffset());
        assertEquals(27, slot("ROW", 1).lengthOffset());
        assertEquals(42, slot("ROW", 2).lengthOffset());
        assertEquals(Optional.of(16), LAYOUT.attributeOffset(slot("CUSTNO", 1), ExtendedAttribute.HILIGHT));
        // 名前つき field は CUSTNO、ROW の 2 回、MESSAGE の 4 組。1 組に L・F・拡張属性 2 の 5 byte
        assertEquals(12 + 4 * 5 + 10 + 10 + 10 + 12, LAYOUT.length());
    }

    @Test
    @DisplayName("ERASEは物理マップに記号マップを重ね、X'00'のデータと属性は物理マップの値を残す")
    void overlaysSymbolicDataOnThePhysicalMap() {
        byte[] symbolic = new byte[LAYOUT.length()];
        putData(symbolic, "CUSTNO", 1, "0000000042");
        symbolic[slot("CUSTNO", 1).lengthOffset()] = (byte) 0xFF;
        symbolic[slot("CUSTNO", 1).lengthOffset() + 1] = (byte) 0xFF;
        // 明るい保護 (X'E8') と赤
        symbolic[slot("MESSAGE", 1).flagOffset()] = (byte) 0xE8;
        symbolic[LAYOUT.attributeOffset(slot("MESSAGE", 1), ExtendedAttribute.COLOR).orElseThrow()]
                = (byte) 0xF2;
        putData(symbolic, "ROW", 2, "SECOND");

        BmsScreenSnapshot screen = BmsScreenComposer.send(MAPSET, MAP, Optional.empty(), symbolic,
                options(true, false, false, false, true), CP);

        assertEquals("0000000042", screen.field("CUSTNO", 1).orElseThrow().data());
        assertEquals(Optional.of(Highlight.UNDERLINE), screen.field("CUSTNO", 1).orElseThrow().highlight());
        assertEquals("READY       ", screen.field("MESSAGE", 1).orElseThrow().data());
        assertEquals(EnumSet.of(BasicAttribute.PROT, BasicAttribute.BRT),
                screen.field("MESSAGE", 1).orElseThrow().attributes());
        assertEquals(Optional.of(Color.RED), screen.field("MESSAGE", 1).orElseThrow().color());
        assertEquals("          ", screen.field("ROW", 1).orElseThrow().data());
        assertEquals("SECOND    ", screen.field("ROW", 2).orElseThrow().data());
        assertEquals(new BmsModel.Position(9, 12), screen.field("ROW", 2).orElseThrow().position());
        assertTrue(screen.field("ROW", 2).orElseThrow().modified());
        // 記号 cursor: L=-1 の CUSTNO のデータ位置 (5 行 18 桁)
        assertEquals(4 * 80 + 17, screen.cursorOffset());
        assertTrue(screen.keyboardRestored());
        assertFalse(screen.alarm());
    }

    @Test
    @DisplayName("MAPONLYは記号マップを読まずICへcursorを置き、DATAONLYは画面の固定文字を残す")
    void mapOnlyThenDataOnly() {
        BmsScreenSnapshot first = BmsScreenComposer.send(MAPSET, MAP, Optional.empty(), null,
                options(true, true, false, false, false), CP);
        assertEquals("TITLE", first.fields().get(0).data());
        assertEquals(4 * 80 + 17, first.cursorOffset());

        byte[] symbolic = new byte[LAYOUT.length()];
        putData(symbolic, "MESSAGE", 1, "Invalid key");
        BmsScreenSnapshot second = BmsScreenComposer.send(MAPSET, MAP, Optional.of(first), symbolic,
                new SendOptions(false, false, true, false, true, true, OptionalInt.empty(), false), CP);

        assertEquals("TITLE", second.fields().get(0).data());
        assertEquals("Invalid key ", second.field("MESSAGE", 1).orElseThrow().data());
        assertFalse(second.field("ROW", 1).orElseThrow().modified(), "FRSET resets MDT");
        assertTrue(second.alarm());
        assertEquals(first.cursorOffset(), second.cursorOffset());
    }

    @Test
    @DisplayName("画面に無いmapへのDATAONLY、長さの違う記号マップ、未知の属性値は断る")
    void rejectsWhatItCannotCompose() {
        byte[] symbolic = new byte[LAYOUT.length()];
        assertThrows(IllegalStateException.class, () -> BmsScreenComposer.send(MAPSET, MAP,
                Optional.empty(), symbolic, options(false, false, true, false, false), CP));
        assertThrows(IllegalStateException.class, () -> BmsScreenComposer.send(MAPSET, MAP,
                Optional.empty(), new byte[LAYOUT.length() - 1],
                options(true, false, false, false, false), CP));
        byte[] badColor = new byte[LAYOUT.length()];
        badColor[LAYOUT.attributeOffset(slot("CUSTNO", 1), ExtendedAttribute.COLOR).orElseThrow()]
                = (byte) 0x99;
        assertThrows(IllegalArgumentException.class, () -> BmsScreenComposer.send(MAPSET, MAP,
                Optional.empty(), badColor, options(true, false, false, false, false), CP));
        assertThrows(IllegalArgumentException.class, () -> BmsAttributeCodes.basic(0x20));
    }

    @Test
    @DisplayName("3270属性byteを保護・数字・輝度・MDTへ分ける")
    void decodesAttributeBytes() {
        assertEquals(EnumSet.of(BasicAttribute.ASKIP, BasicAttribute.NORM),
                BmsAttributeCodes.basic(0xF0).attributes());
        assertEquals(EnumSet.of(BasicAttribute.UNPROT, BasicAttribute.NUM, BasicAttribute.BRT),
                BmsAttributeCodes.basic(0xD8).attributes());
        assertEquals(EnumSet.of(BasicAttribute.PROT, BasicAttribute.DRK),
                BmsAttributeCodes.basic(0x6C).attributes());
        assertTrue(BmsAttributeCodes.basic(0xC1).modified());
    }
    // --- 混在コードページ (DBCS) -------------------------------------------------

    private static final CodePage MIXED = CodePages.IBM_930;
    private static final String YAMADA = "\u5C71\u7530";

    private static final Mapset DBCS_MAPSET = BmsParser.parse(source(
            card("JSET     DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,TIOAPFX=YES", false),
            card("JMAP     DFHMDI SIZE=(24,80)", false),
            card("NAME     DFHMDF POS=(3,2),LENGTH=8,ATTRB=(UNPROT,NORM),SOSI=YES", false),
            card("         DFHMSD TYPE=FINAL", false)));
    private static final BmsModel.Map DBCS_MAP = DBCS_MAPSET.maps().get(0);
    private static final BmsSymbolicLayout DBCS_LAYOUT = BmsSymbolicLayout.of(DBCS_MAPSET, DBCS_MAP);

    private static byte[] dbcsSymbolic(byte[] data) {
        BmsSymbolicLayout.Slot slot = DBCS_LAYOUT.slots().stream()
                .filter(s -> s.name().equals("NAME")).findFirst().orElseThrow();
        byte[] symbolic = new byte[DBCS_LAYOUT.length()];
        System.arraycopy(data, 0, symbolic, slot.dataOffset(), data.length);
        return symbolic;
    }

    @Test
    @DisplayName("DBCSを持つ記号マップを画面へ出す。桁数で数えるので1文字1byteを求めない")
    void putsDoubleByteDataOnTheScreen() {
        // X'0E 4565 4563 0F 40 40' = 8 桁。文字としては「山田」+ 空白 2 つの 4 文字である
        byte[] symbolic = dbcsSymbolic(MIXED.encode(YAMADA + "  "));

        BmsScreenSnapshot screen = BmsScreenComposer.send(DBCS_MAPSET, DBCS_MAP, Optional.empty(),
                symbolic, options(true, false, false, false, false), MIXED);

        assertEquals(YAMADA + "  ", screen.field("NAME", 1).orElseThrow().data());
        assertEquals(8, screen.field("NAME", 1).orElseThrow().length());
    }

    @Test
    @DisplayName("復号できない半端なDBCSは断る")
    void refusesDataThatIsNotValidMixedBytes() {
        // シフトアウトのあとがシフトインで閉じられず、最後の 1 byte が DBCS の片割れになる
        byte[] symbolic = dbcsSymbolic(new byte[] {0x0E, 0x45, 0x65, 0x40, 0x40, 0x40, 0x40, 0x40});

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> BmsScreenComposer.send(DBCS_MAPSET, DBCS_MAP, Optional.empty(), symbolic,
                        options(true, false, false, false, false), MIXED));
        assertTrue(refused.getMessage().contains("does not hold valid IBM-930 data"),
                refused.getMessage());
    }

    @Test
    @DisplayName("復号で黙って落ちるシフト符号も、桁数の照合で捕まえる")
    void refusesDataWhoseShiftCodesDisappearWhenDecoded() {
        // 末尾のシフトアウトは復号で消える。文字としては空白 7 つになり、8 桁の field が埋まらない
        byte[] symbolic = dbcsSymbolic(new byte[] {0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x40, 0x0E});

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> BmsScreenComposer.send(DBCS_MAPSET, DBCS_MAP, Optional.empty(), symbolic,
                        options(true, false, false, false, false), MIXED));
        assertTrue(refused.getMessage().contains("fills 7 screen positions"), refused.getMessage());
    }
}
