package dev.cobolonjava.ims.dbd;

import static dev.cobolonjava.ims.gen.Cards.card;
import static dev.cobolonjava.ims.gen.Cards.deck;
import static dev.cobolonjava.ims.gen.Cards.more;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.gen.ImsGenerationException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** DBDGEN の原文を読む (設計 78 §2.1、§1.1)。 */
@Tag("V1")
class DbdParserTest {

    /** 3 段の HIDAM。ACCT は可変長でキーが重なってよく、TRAN はキーを持たない。 */
    private static final String BANK = deck(
            more("         DBD   NAME=BANKDB,"),
            card("               ACCESS=(HIDAM,VSAM)"),
            card("         DATASET DD1=BANKDD,DEVICE=3390"),
            card("         SEGM  NAME=CUST,PARENT=0,BYTES=40"),
            card("         FIELD NAME=(CUSTNO,SEQ,U),BYTES=6,START=1,TYPE=C"),
            card("         FIELD NAME=CUSTNAME,BYTES=30,START=7"),
            card("         LCHILD NAME=(CUSTX,CUSTXDB),POINTER=INDX"),
            more("         SEGM  NAME=ACCT,PARENT=CUST,"),
            card("               BYTES=(60,20),RULES=(LLL,FIRST)"),
            card("         FIELD NAME=(ACCTNO,SEQ,M),BYTES=8,START=3,TYPE=P"),
            card("         SEGM  NAME=TRAN,PARENT=((ACCT,SNGL)),BYTES=30"),
            card("         SEGM  NAME=ADDR,PARENT=CUST,BYTES=50"),
            card("         DBDGEN"),
            card("         FINISH"),
            card("         END"));

    @Test
    @DisplayName("階層の順、段、長さ、挿入規則、順序フィールドを読む")
    void readsTheHierarchy() {
        DatabaseDefinition dbd = DbdParser.parse(BANK);

        assertEquals("BANKDB", dbd.name());
        assertEquals(AccessMethod.HIDAM, dbd.access());
        assertEquals("BANKDD", dbd.dataSetName());
        assertEquals(List.of("CUST", "ACCT", "TRAN", "ADDR"),
                dbd.segments().stream().map(SegmentDefinition::name).toList());
        assertEquals(List.of(1, 2, 3, 2), dbd.segments().stream().map(SegmentDefinition::level).toList());
        assertEquals(List.of("ACCT", "ADDR"),
                dbd.childrenOf(dbd.root()).stream().map(SegmentDefinition::name).toList());

        SegmentDefinition cust = dbd.root();
        assertNull(cust.parent());
        assertFalse(cust.variableLength());
        assertEquals(new FieldDefinition("CUSTNO", 1, 6, FieldType.C, true, true), cust.sequenceField());
        assertEquals(6, cust.field("CUSTNAME").offset());

        SegmentDefinition acct = dbd.segment("ACCT");
        assertTrue(acct.variableLength());
        assertEquals(60, acct.maxBytes());
        assertEquals(20, acct.minBytes());
        assertEquals(InsertRule.FIRST, acct.insertRule());
        assertFalse(acct.sequenceField().unique());
        assertEquals(FieldType.P, acct.sequenceField().type());

        SegmentDefinition tran = dbd.segment("TRAN");
        assertEquals("ACCT", tran.parent());
        assertEquals(InsertRule.LAST, tran.insertRule());
        assertNull(tran.sequenceField());
    }

    @Test
    @DisplayName("親が開いている階層の道に無い SEGM は、階層の順が崩れているので断る")
    void aParentOffTheHierarchicalPathIsRefused() {
        ImsGenerationException failure = assertThrows(ImsGenerationException.class, () -> DbdParser.parse(deck(
                card("         DBD   NAME=BANKDB,ACCESS=HDAM"),
                card("         SEGM  NAME=CUST,PARENT=0,BYTES=40"),
                card("         SEGM  NAME=ACCT,PARENT=CUST,BYTES=40"),
                card("         SEGM  NAME=ADDR,PARENT=CUST,BYTES=40"),
                card("         SEGM  NAME=BAD,PARENT=ACCT,BYTES=40"))));

        assertEquals(5, failure.line());
        assertTrue(failure.reason().contains("hierarchical path"), failure.getMessage());
    }

    @Test
    @DisplayName("セグメントの長さを越えるフィールドは断る")
    void aFieldBeyondTheSegmentIsRefused() {
        ImsGenerationException failure = assertThrows(ImsGenerationException.class, () -> DbdParser.parse(deck(
                card("         DBD   NAME=BANKDB,ACCESS=HDAM"),
                card("         SEGM  NAME=CUST,PARENT=0,BYTES=10"),
                card("         FIELD NAME=CUSTNO,BYTES=6,START=6"))));

        assertTrue(failure.reason().contains("extends beyond"), failure.getMessage());
    }

    @Test
    @DisplayName("結果に効く SEGM の知らないキーワードは黙って飛ばさない")
    void anUnknownSegmentKeywordIsRefused() {
        ImsGenerationException failure = assertThrows(ImsGenerationException.class, () -> DbdParser.parse(deck(
                card("         DBD   NAME=BANKDB,ACCESS=HDAM"),
                card("         SEGM  NAME=CUST,PARENT=0,BYTES=10,MYSTERY=1"))));

        assertTrue(failure.reason().contains("MYSTERY is not supported yet"), failure.getMessage());
    }

    @Test
    @DisplayName("索引の DBD、二次索引、HDAM の LCHILD は L0 として断る (設計 78 §1.1)")
    void levelZeroFeaturesAreRefused() {
        assertUnsupported(deck(card("         DBD   NAME=CUSTXDB,ACCESS=INDEX")));
        assertUnsupported(deck(
                card("         DBD   NAME=BANKDB,ACCESS=HIDAM"),
                card("         SEGM  NAME=CUST,PARENT=0,BYTES=10"),
                card("         XDFLD NAME=XNAME,SRCH=CUSTNO")));
        assertUnsupported(deck(
                card("         DBD   NAME=BANKDB,ACCESS=HDAM"),
                card("         SEGM  NAME=CUST,PARENT=0,BYTES=10"),
                card("         LCHILD NAME=(CUSTX,CUSTXDB),POINTER=INDX")));
        assertUnsupported(deck(
                card("         DBD   NAME=BANKDB,ACCESS=HDAM"),
                card("         SEGM  NAME=CUST,PARENT=0,BYTES=10"),
                more("         SEGM  NAME=LINK,"),
                more("               PARENT=((CUST,SNGL),(OTHER,PHYSICAL,OTHERDB)),"),
                card("               BYTES=10")));
    }

    private static void assertUnsupported(String source) {
        ImsGenerationException failure = assertThrows(ImsGenerationException.class, () -> DbdParser.parse(source));
        assertTrue(failure.reason().contains("is not supported (design 78"), failure.getMessage());
    }
}
