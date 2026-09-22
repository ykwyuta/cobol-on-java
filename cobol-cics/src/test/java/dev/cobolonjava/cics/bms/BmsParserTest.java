package dev.cobolonjava.cics.bms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Color;
import dev.cobolonjava.cics.bms.BmsModel.ExtendedAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Field;
import dev.cobolonjava.cics.bms.BmsModel.Highlight;
import dev.cobolonjava.cics.bms.BmsModel.Mapset;
import dev.cobolonjava.cics.bms.BmsModel.Mode;
import dev.cobolonjava.cics.bms.BmsModel.Sosi;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** BMSマクロの原始文形式と、中立モデルへの正規化。 */
@Tag("V1")
class BmsParserTest {

    /** 1〜71桁に本文を置き、続くなら72桁目に印を付ける。 */
    static String card(String body, boolean continued) {
        if (body.length() > 71) {
            throw new IllegalArgumentException("card body exceeds column 71: " + body);
        }
        return continued ? String.format("%-71s*", body) : body;
    }

    static String source(String... cards) {
        return String.join("\n", cards) + "\n";
    }

    private static String mapset(String... fieldCards) {
        List<String> cards = new java.util.ArrayList<>(List.of(
                "* comment line",
                card("TESTSET  DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,STORAGE=AUTO,", true),
                card("               CTRL=FREEKB,EXTATT=YES,TIOAPFX=YES,", true),
                card("               DSATTS=(COLOR,HILIGHT)", false),
                card("TESTMP   DFHMDI SIZE=(24,80),LINE=1,COLUMN=1", false)));
        cards.addAll(List.of(fieldCards));
        cards.add(card("         DFHMSD TYPE=FINAL", false));
        cards.add(card("         END", false));
        return source(cards.toArray(String[]::new));
    }

    @Test
    @DisplayName("続きの行と、行をまたぐ引用符の中の空白を読む")
    void readsContinuationsAndQuotedStringsAcrossCards() {
        Mapset parsed = BmsParser.parse(mapset(
                card("TITLE    DFHMDF POS=(1,14),LENGTH=63,ATTRB=(NORM,PROT),COLOR=RED,", true),
                card("               INITIAL='Sample Application - Accounts for", true),
                card("               Customers.'", false)));

        Field title = parsed.maps().get(0).fields().get(0);
        // 引用符は24桁目にあり、値は25桁目から71桁目までの47文字、続きは16桁目から
        String expected = String.format("%-47s", "Sample Application - Accounts for")
                + "Customers.";
        assertEquals(Optional.of(expected), title.initial());
    }

    @Test
    @DisplayName("mapset・map・fieldの属性を欠落なく保持し、既定値を補う")
    void keepsAttributesAndAppliesDefaults() {
        Mapset parsed = BmsParser.parse(mapset(
                card("CUSTNO   DFHMDF POS=(5,17),LENGTH=10,ATTRB=(NORM,NUM,IC),COLOR=GREEN,", true),
                card("               HILIGHT=UNDERLINE", false),
                card("         DFHMDF POS=(5,28),LENGTH=1", false),
                card("NOTE     DFHMDF POS=(6,1),LENGTH=5,ATTRB=(ASKIP,NORM,PROT,ASKIP),", true),
                card("               INITIAL='It''s'", false)));

        assertEquals("TESTSET", parsed.name());
        assertEquals(Mode.INOUT, parsed.mode());
        assertTrue(parsed.tioaPrefix());
        BmsModel.Map map = parsed.map("testmp").orElseThrow();
        assertEquals(24, map.rows());
        assertEquals(80, map.columns());
        assertEquals(EnumSet.of(ExtendedAttribute.COLOR, ExtendedAttribute.HILIGHT),
                map.dataAttributes());
        assertEquals(EnumSet.of(ExtendedAttribute.COLOR, ExtendedAttribute.HILIGHT),
                map.mapAttributes());

        Field custno = map.fields().get(0);
        assertEquals(Optional.of("CUSTNO"), custno.name());
        // 保護を書かなければUNPROTを補う
        assertEquals(EnumSet.of(BasicAttribute.NORM, BasicAttribute.NUM, BasicAttribute.IC,
                BasicAttribute.UNPROT), custno.attributes());
        assertEquals(Optional.of(Color.GREEN), custno.color());
        assertEquals(Optional.of(Highlight.UNDERLINE), custno.highlight());

        Field stopper = map.fields().get(1);
        assertEquals(Optional.empty(), stopper.name());
        // ATTRB自体を書かなければASKIPとNORM
        assertEquals(EnumSet.of(BasicAttribute.ASKIP, BasicAttribute.NORM), stopper.attributes());

        Field note = map.fields().get(2);
        assertEquals(EnumSet.of(BasicAttribute.ASKIP, BasicAttribute.NORM, BasicAttribute.PROT),
                note.attributes());
        assertEquals(Optional.of("It's"), note.initial());
        assertEquals(List.of(custno, note), map.namedFields());
    }

    @Test
    @DisplayName("LENGTHより長いINITIALは断らず、書かれたとおり保持する")
    void keepsInitialLongerThanLength() {
        // hostで組み立てて動いている資産に、この書き方がある。断るのは規則を狭く決めすぎである
        Mapset parsed = BmsParser.parse(mapset(
                card("F1       DFHMDF POS=(1,2),LENGTH=2,INITIAL='ABC'", false)));

        assertEquals(Optional.of("ABC"), parsed.maps().get(0).fields().get(0).initial());
    }

    @Test
    @DisplayName("EXTATT=YESでDSATTSを書かなければCOLOR・HILIGHT・PS・VALIDNを使う")
    void extattYesImpliesFourExtendedAttributes() {
        Mapset parsed = BmsParser.parse(source(
                card("SET2     DFHMSD TYPE=DSECT,MODE=OUT,LANG=COBOL,EXTATT=YES,TIOAPFX=NO", false),
                card("MP2      DFHMDI SIZE=(24,80)", false),
                card("F1       DFHMDF POS=(1,2),LENGTH=3", false),
                card("         DFHMSD TYPE=FINAL", false)));

        assertEquals(Set.of(ExtendedAttribute.COLOR, ExtendedAttribute.HILIGHT,
                ExtendedAttribute.PS, ExtendedAttribute.VALIDN),
                parsed.maps().get(0).dataAttributes());
    }

    @Test
    @DisplayName("知らないoperand、画面外の位置、長すぎる初期値は行番号つきで断る")
    void rejectsWhatItCannotRepresent() {
        assertRejected(mapset(card("F1       DFHMDF POS=(1,2),LENGTH=3,GRPNAME=G1", false)),
                "unsupported DFHMDF operand GRPNAME", 6);
        assertRejected(mapset(card("F1       DFHMDF POS=(25,1),LENGTH=3", false)),
                "outside the map size", 6);
        assertRejected(mapset(card("F1       DFHMDF POS=(24,79),LENGTH=3", false)),
                "beyond the end of the map", 6);
        assertRejected(mapset(card("F1       DFHMDF POS=(1,2),LENGTH=2,ATTRB=(UNPROT,ASKIP)", false)),
                "UNPROT conflicts", 6);
        assertRejected(mapset(card("F1       DFHMDF POS=(1,2),LENGTH=2,COLOR=ORANGE", false)),
                "unsupported COLOR=ORANGE", 6);
        assertRejected(source(
                card("SET3     DFHMSD TYPE=MAP,LANG=COBOL,MODE=OUT", false),
                card("         DFHMSD TYPE=FINAL", false)),
                "TIOAPFX must be specified", 1);
        assertRejected(source(
                card("SET4     DFHMSD TYPE=MAP,LANG=PLI,TIOAPFX=YES", false),
                card("         DFHMSD TYPE=FINAL", false)),
                "only LANG=COBOL", 1);
    }

    @Test
    @DisplayName("SOSIはYES・NOを受け、書かれていなければempty。ほかの値は断る")
    void readsTheSosiOperand() {
        Mapset parsed = BmsParser.parse(mapset(
                card("MIX      DFHMDF POS=(1,2),LENGTH=8,ATTRB=UNPROT,SOSI=YES", false),
                card("SBCS     DFHMDF POS=(2,2),LENGTH=8,ATTRB=UNPROT,SOSI=NO", false),
                card("PLAIN    DFHMDF POS=(3,2),LENGTH=8,ATTRB=UNPROT", false)));
        List<Field> fields = parsed.maps().get(0).fields();

        assertEquals(Optional.of(Sosi.YES), fields.get(0).sosi());
        assertEquals(Optional.of(Sosi.NO), fields.get(1).sosi());
        assertEquals(Optional.empty(), fields.get(2).sosi());

        assertRejected(mapset(card("F1       DFHMDF POS=(1,2),LENGTH=2,SOSI=MAYBE", false)),
                "unsupported SOSI=MAYBE", 6);
    }

    private static void assertRejected(String source, String message, int line) {
        BmsDefinitionException failure = assertThrows(BmsDefinitionException.class,
                () -> BmsParser.parse(source));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
        assertEquals(line, failure.line(), failure.getMessage());
    }
}
