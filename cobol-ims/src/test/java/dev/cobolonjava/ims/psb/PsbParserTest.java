package dev.cobolonjava.ims.psb;

import static dev.cobolonjava.ims.gen.Cards.card;
import static dev.cobolonjava.ims.gen.Cards.deck;
import static dev.cobolonjava.ims.gen.Cards.more;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.ims.gen.ImsGenerationException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** PSBGEN の原文を読む (設計 78 §2.1、§1.1)。 */
@Tag("V1")
class PsbParserTest {

    @Test
    @DisplayName("PCB の並び、PROCOPT、KEYLEN、SENSEG、CMPAT を読む")
    void readsThePcbList() {
        ProgramSpecification psb = PsbParser.parse(deck(
                card("ALTPCB   PCB   TYPE=TP,MODIFY=YES"),
                more("         PCB   TYPE=DB,DBDNAME=BANKDB,PROCOPT=GR,"),
                card("               KEYLEN=14,PCBNAME=BANKPCB"),
                card("         SENSEG NAME=CUST,PARENT=0"),
                card("         SENSEG NAME=ACCT,PARENT=CUST,PROCOPT=G"),
                card("         PCB   TYPE=DB,DBDNAME=HISTDB,KEYLEN=8"),
                card("         SENSEG NAME=HIST"),
                card("         PSBGEN PSBNAME=BANKPSB,LANG=COBOL,CMPAT=YES"),
                card("         END")));

        assertEquals("BANKPSB", psb.name());
        assertEquals("COBOL", psb.language());
        assertTrue(psb.compatibility());
        assertEquals(3, psb.pcbs().size());

        PcbDefinition.Terminal alternate = assertInstanceOf(PcbDefinition.Terminal.class, psb.pcbs().get(0));
        assertEquals("ALTPCB", alternate.name());
        assertTrue(alternate.modifiable());

        PcbDefinition.Database bank = assertInstanceOf(PcbDefinition.Database.class, psb.pcbs().get(1));
        assertEquals("BANKPCB", bank.name());
        assertEquals("BANKDB", bank.dbdName());
        assertEquals("GR", bank.processingOptions());
        assertEquals(14, bank.keyLength());
        assertEquals(List.of(new SensitiveSegment("CUST", null, null), new SensitiveSegment("ACCT", "CUST", "G")),
                bank.segments());

        PcbDefinition.Database history = assertInstanceOf(PcbDefinition.Database.class, psb.pcbs().get(2));
        assertEquals("A", history.processingOptions());
    }

    @Test
    @DisplayName("CMPAT を書かなければ偽である")
    void compatibilityDefaultsToNo() {
        ProgramSpecification psb = PsbParser.parse(deck(
                card("         PCB   TYPE=DB,DBDNAME=HISTDB,KEYLEN=8"),
                card("         SENSEG NAME=HIST,PARENT=0"),
                card("         PSBGEN PSBNAME=HISTPSB,LANG=COBOL")));

        assertFalse(psb.compatibility());
    }

    @Test
    @DisplayName("SENSEG の無い PCB と、前に無い親を名指す SENSEG は断る")
    void incompletePcbsAreRefused() {
        ImsGenerationException empty = assertThrows(ImsGenerationException.class, () -> PsbParser.parse(deck(
                card("         PCB   TYPE=DB,DBDNAME=HISTDB,KEYLEN=8"),
                card("         PSBGEN PSBNAME=HISTPSB"))));
        assertEquals(1, empty.line());

        assertThrows(ImsGenerationException.class, () -> PsbParser.parse(deck(
                card("         PCB   TYPE=DB,DBDNAME=BANKDB,KEYLEN=8"),
                card("         SENSEG NAME=CUST,PARENT=0"),
                card("         SENSEG NAME=TRAN,PARENT=ACCT"),
                card("         PSBGEN PSBNAME=BANKPSB"))));
    }

    @Test
    @DisplayName("GSAM、PROCSEQ、POS=M、SENFLD は L0 として断る (設計 78 §1.1)")
    void levelZeroFeaturesAreRefused() {
        assertUnsupported(card("         PCB   TYPE=GSAM,DBDNAME=OUTDB"));
        assertUnsupported(card("         PCB   TYPE=DB,DBDNAME=BANKDB,KEYLEN=8,PROCSEQ=CUSTX"));
        assertUnsupported(card("         PCB   TYPE=DB,DBDNAME=BANKDB,KEYLEN=8,POS=M"));
        assertUnsupported(deck(
                card("         PCB   TYPE=DB,DBDNAME=BANKDB,KEYLEN=8"),
                card("         SENSEG NAME=CUST,PARENT=0"),
                card("         SENFLD NAME=CUSTNO,START=1")));
    }

    private static void assertUnsupported(String source) {
        ImsGenerationException failure = assertThrows(ImsGenerationException.class,
                () -> PsbParser.parse(source + "\n"));
        assertTrue(failure.reason().contains("is not supported (design 78"), failure.getMessage());
    }
}
