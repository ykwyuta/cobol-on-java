package dev.cobolonjava.cics.bms;

import static dev.cobolonjava.cics.bms.BmsParserTest.card;
import static dev.cobolonjava.cics.bms.BmsParserTest.source;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.bms.BmsInputDecoder.Received;
import dev.cobolonjava.cics.bms.BmsModel.Mapset;
import dev.cobolonjava.cics.bms.BmsScreenComposer.SendOptions;
import dev.cobolonjava.cics.bms.BmsTerminalInput.FieldInput;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.codepage.UnrepresentableCharacterException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** RECEIVE MAP の分解規則と入力の再検証。 */
@Tag("V1")
class BmsInputDecoderTest {

    private static final CodePage CP = CodePages.IBM_1047;

    private static final Mapset MAPSET = BmsParser.parse(source(
            card("INSET    DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,TIOAPFX=YES", false),
            card("INMP     DFHMDI SIZE=(24,80)", false),
            card("         DFHMDF POS=(1,1),LENGTH=5,INITIAL='TITLE',ATTRB=(PROT,NORM)", false),
            card("NAME     DFHMDF POS=(3,2),LENGTH=8,ATTRB=(UNPROT,NORM,IC)", false),
            card("AMOUNT   DFHMDF POS=(4,2),LENGTH=6,ATTRB=(UNPROT,NUM),JUSTIFY=RIGHT", false),
            card("FLAG     DFHMDF POS=(5,2),LENGTH=1,ATTRB=(PROT,FSET),INITIAL='Y'", false),
            card("         DFHMSD TYPE=FINAL", false)));
    private static final BmsModel.Map MAP = MAPSET.maps().get(0);

    private static BmsScreenSnapshot screen(boolean resetModified) {
        return BmsScreenComposer.send(MAPSET, MAP, Optional.empty(), null,
                new SendOptions(true, true, false, true, false, resetModified,
                        OptionalInt.empty(), false), CP);
    }

    private static byte[] slice(byte[] bytes, int from, int length) {
        return Arrays.copyOfRange(bytes, from, from + length);
    }

    @Test
    @DisplayName("入力はL・F・Iへ分け、既定は左寄せ空白、RIGHTは右寄せ0、FSETの画面値も送る")
    void decodesEnteredAndFsetFields() {
        BmsInputDecoder.Result result = BmsInputDecoder.receive(MAPSET, MAP, screen(false),
                new BmsTerminalInput(BmsAid.ENTER, 250,
                        List.of(new FieldInput("NAME", 1, "BOB"), new FieldInput("amount", 1, "42"))),
                CP);

        byte[] symbolic = assertInstanceOf(Received.class, result).symbolic();
        // 接頭 12、NAME: L@12 F@14 I@15、AMOUNT: L@23 F@25 I@26、FLAG: L@32 F@34 I@35
        assertArrayEquals(new byte[] {0, 3, 0}, slice(symbolic, 12, 3));
        assertArrayEquals(CP.encode("BOB     "), slice(symbolic, 15, 8));
        assertArrayEquals(new byte[] {0, 2, 0}, slice(symbolic, 23, 3));
        assertArrayEquals(CP.encode("000042"), slice(symbolic, 26, 6));
        assertArrayEquals(new byte[] {0, 1, 0}, slice(symbolic, 32, 3));
        assertArrayEquals(CP.encode("Y"), slice(symbolic, 35, 1));

        BmsScreenSnapshot after = ((Received) result).screen();
        assertEquals("BOB     ", after.field("NAME", 1).orElseThrow().data());
        assertTrue(after.field("NAME", 1).orElseThrow().modified());
        assertEquals(250, after.cursorOffset());
        assertFalse(after.keyboardRestored());
    }

    @Test
    @DisplayName("送られなかったfieldはL=0・I=X'00'、消去したfieldはF=X'80'")
    void leavesUntransmittedFieldsEmptyAndFlagsErasure() {
        byte[] symbolic = assertInstanceOf(Received.class, BmsInputDecoder.receive(MAPSET, MAP,
                screen(true), new BmsTerminalInput(BmsAid.PF5, -1,
                        List.of(new FieldInput("NAME", 1, ""))), CP)).symbolic();

        assertArrayEquals(new byte[] {0, 0, (byte) 0x80}, slice(symbolic, 12, 3));
        assertArrayEquals(new byte[8], slice(symbolic, 15, 8));
        assertArrayEquals(new byte[9], slice(symbolic, 23, 9));
        assertArrayEquals(new byte[4], slice(symbolic, 32, 4));
    }

    @Test
    @DisplayName("CLEAR・PAキーと、送られたfieldが無い入力はMAPFAIL")
    void reportsMapFail() {
        assertInstanceOf(BmsInputDecoder.MapFail.class, BmsInputDecoder.receive(MAPSET, MAP,
                screen(false), new BmsTerminalInput(BmsAid.CLEAR, -1, List.of()), CP));
        assertInstanceOf(BmsInputDecoder.MapFail.class, BmsInputDecoder.receive(MAPSET, MAP,
                screen(false), new BmsTerminalInput(BmsAid.PA2, -1,
                        List.of(new FieldInput("NAME", 1, "X"))), CP));
        assertInstanceOf(BmsInputDecoder.MapFail.class, BmsInputDecoder.receive(MAPSET, MAP,
                screen(true), new BmsTerminalInput(BmsAid.ENTER, -1, List.of()), CP));
    }

    @Test
    @DisplayName("保護field・長さ超過・NUMの数字以外・画面に無いfield・別mapの画面は断る")
    void revalidatesInputAgainstTheScreen() {
        for (FieldInput bad : List.of(
                new FieldInput("FLAG", 1, "N"),
                new FieldInput("NAME", 1, "TOOLONGNAME"),
                new FieldInput("AMOUNT", 1, "4X"),
                new FieldInput("NOPE", 1, "1"),
                new FieldInput("NAME", 2, "A"))) {
            assertThrows(IllegalArgumentException.class, () -> BmsInputDecoder.receive(MAPSET, MAP,
                    screen(false), new BmsTerminalInput(BmsAid.ENTER, -1, List.of(bad)), CP),
                    bad.toString());
        }
        BmsScreenSnapshot other = new BmsScreenSnapshot("INSET", "OTHER", 24, 80, List.of(), -1,
                false, false);
        assertThrows(IllegalStateException.class, () -> BmsInputDecoder.receive(MAPSET, MAP, other,
                new BmsTerminalInput(BmsAid.ENTER, -1, List.of()), CP));
    }

    // --- 混在コードページ (DBCS) -------------------------------------------------

    private static final CodePage MIXED = CodePages.IBM_930;
    private static final String YAMADA = "\u5C71\u7530";

    /** DBCS を入れる field を持つ mapset。SOSI=NO の field は SBCS だけを受ける。 */
    private static final Mapset DBCS_MAPSET = BmsParser.parse(source(
            card("JSET     DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,TIOAPFX=YES", false),
            card("JMAP     DFHMDI SIZE=(24,80)", false),
            card("NAME     DFHMDF POS=(3,2),LENGTH=8,ATTRB=(UNPROT,NORM,IC),SOSI=YES", false),
            card("CODE     DFHMDF POS=(4,2),LENGTH=6,ATTRB=(UNPROT,NORM),SOSI=NO", false),
            card("FREE     DFHMDF POS=(5,2),LENGTH=6,ATTRB=(UNPROT,NORM)", false),
            card("         DFHMSD TYPE=FINAL", false)));
    private static final BmsModel.Map DBCS_MAP = DBCS_MAPSET.maps().get(0);

    private static BmsScreenSnapshot dbcsScreen() {
        return BmsScreenComposer.send(DBCS_MAPSET, DBCS_MAP, Optional.empty(), null,
                new SendOptions(true, true, false, true, false, false,
                        OptionalInt.empty(), false), MIXED);
    }

    @Test
    @DisplayName("DBCSは桁数が収まれば受ける。Lは端末が送った桁数、データはシフト符号を含む")
    void acceptsDoubleByteInputThatFitsTheField() {
        BmsInputDecoder.Result result = BmsInputDecoder.receive(DBCS_MAPSET, DBCS_MAP, dbcsScreen(),
                new BmsTerminalInput(BmsAid.ENTER, -1, List.of(new FieldInput("NAME", 1, YAMADA))),
                MIXED);

        byte[] symbolic = assertInstanceOf(Received.class, result).symbolic();
        // 接頭 12、NAME: L@12 F@14 I@15 (8 桁)
        // 「山田」は 2 文字だが 6 桁である。L は文字数 2 ではなく桁数 6
        assertArrayEquals(new byte[] {0, 6, 0}, slice(symbolic, 12, 3));
        assertArrayEquals(new byte[] {0x0E, 0x45, 0x65, 0x45, 0x63, 0x0F, 0x40, 0x40},
                slice(symbolic, 15, 8));
        // 画面にも桁ぶん詰めて残る
        assertEquals(YAMADA + "  ", ((Received) result).screen().field("NAME", 1).orElseThrow().data());
    }

    @Test
    @DisplayName("SBCSとDBCSの混在も受ける。シフト符号の桁を数える")
    void acceptsMixedSingleAndDoubleByteInput() {
        byte[] symbolic = assertInstanceOf(Received.class, BmsInputDecoder.receive(DBCS_MAPSET, DBCS_MAP,
                dbcsScreen(), new BmsTerminalInput(BmsAid.ENTER, -1,
                        List.of(new FieldInput("NAME", 1, "A\u5C71B"))), MIXED)).symbolic();

        // X'C1 0E 4565 0F C2' の 6 桁。3 文字だが 6 桁である
        assertArrayEquals(new byte[] {0, 6, 0}, slice(symbolic, 12, 3));
        assertArrayEquals(new byte[] {(byte) 0xC1, 0x0E, 0x45, 0x65, 0x0F, (byte) 0xC2, 0x40, 0x40},
                slice(symbolic, 15, 8));
    }

    @Test
    @DisplayName("半角カタカナはSBCSなので、文字数ぶんの桁に収まる")
    void acceptsHalfWidthKatakanaAsSingleByte() {
        byte[] symbolic = assertInstanceOf(Received.class, BmsInputDecoder.receive(DBCS_MAPSET, DBCS_MAP,
                dbcsScreen(), new BmsTerminalInput(BmsAid.ENTER, -1,
                        List.of(new FieldInput("FREE", 1, "\uFF71\uFF72"))), MIXED)).symbolic();

        // FREE: L@32 F@34 I@35 (6 桁)
        assertArrayEquals(new byte[] {0, 2, 0}, slice(symbolic, 32, 3));
        assertArrayEquals(MIXED.encode("\uFF71\uFF72    "), slice(symbolic, 35, 6));
    }

    @Test
    @DisplayName("桁が足りないDBCSは断る。文字数ではなく桁数で数える")
    void refusesDoubleByteInputThatDoesNotFit() {
        // 8 桁の field にシフト符号を含めて 10 桁を入れようとしている (文字数は 4 で maxlength には収まる)
        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
                () -> BmsInputDecoder.receive(DBCS_MAPSET, DBCS_MAP, dbcsScreen(),
                        new BmsTerminalInput(BmsAid.ENTER, -1,
                                List.of(new FieldInput("NAME", 1, YAMADA + YAMADA))), MIXED));
        assertTrue(tooLong.getMessage().contains("10 screen positions"), tooLong.getMessage());
    }

    @Test
    @DisplayName("SOSI=NOのfieldはDBCSを断る")
    void refusesDoubleByteInputInSingleByteOnlyFields() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> BmsInputDecoder.receive(DBCS_MAPSET, DBCS_MAP, dbcsScreen(),
                        new BmsTerminalInput(BmsAid.ENTER, -1,
                                List.of(new FieldInput("CODE", 1, YAMADA))), MIXED));
        assertTrue(refused.getMessage().contains("SOSI=NO"), refused.getMessage());
    }

    @Test
    @DisplayName("DBCSを持たないコードページでは、どの文字が入らないかを言って断る")
    void refusesDoubleByteInputUnderASingleByteCodePage() {
        UnrepresentableCharacterException refused = assertThrows(UnrepresentableCharacterException.class,
                () -> BmsInputDecoder.receive(MAPSET, MAP, screen(false),
                        new BmsTerminalInput(BmsAid.ENTER, -1,
                                List.of(new FieldInput("NAME", 1, YAMADA))), CP));
        assertEquals("\u5C71", refused.character());
        assertEquals("IBM-1047", refused.codePageName());
    }
}
