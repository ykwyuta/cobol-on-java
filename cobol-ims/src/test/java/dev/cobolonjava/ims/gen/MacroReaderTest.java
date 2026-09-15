package dev.cobolonjava.ims.gen;

import static dev.cobolonjava.ims.gen.Cards.card;
import static dev.cobolonjava.ims.gen.Cards.deck;
import static dev.cobolonjava.ims.gen.Cards.more;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** DBDGEN / PSBGEN の原文の固定形式 (設計 78 §2.1)。 */
@Tag("V1")
class MacroReaderTest {

    @Test
    @DisplayName("コンマで終わった演算項欄は 16 桁から始まる続きの行へ続き、空白のあとは注記である")
    void operandsContinueAfterACommaAndRemarksAreIgnored() {
        List<MacroStatement> statements = MacroReader.read(deck(
                more("         SEGM  NAME=CUST,                 first remark"),
                more("               PARENT=0,                  second remark"),
                card("               BYTES=(40,20)              last remark")));

        assertEquals(1, statements.size());
        MacroStatement segm = statements.get(0);
        assertNull(segm.label());
        assertEquals("SEGM", segm.operation());
        assertEquals(List.of("NAME=CUST", "PARENT=0", "BYTES=(40,20)"), segm.operands());
        assertEquals(1, segm.line());
    }

    @Test
    @DisplayName("コンマで終わらない行の続きは注記の続きであり、演算項にならない")
    void aContinuationAfterAnEndedFieldIsARemark() {
        // 命令のあとの最初の語は演算項である。演算項が無いかどうかを読む側は知りえないので、
        // 注記は演算項のあとの空白から始まる
        List<MacroStatement> statements = MacroReader.read(deck(
                more("         SEGM  NAME=CUST          A REMARK THAT"),
                card("               CONTINUES=HERE"),
                card("         FINISH")));

        assertEquals(List.of("NAME=CUST"), statements.get(0).operands());
        assertEquals("FINISH", statements.get(1).operation());
        assertEquals(3, statements.get(1).line());
    }

    @Test
    @DisplayName("注記の行を飛ばし、名前欄を読み、73 桁以降の順序番号を読まない")
    void commentsLabelsAndSequenceNumbers() {
        List<MacroStatement> statements = MacroReader.read(deck(
                "* A COMMENT LINE",
                ".* A MACRO COMMENT",
                "",
                card("CUSTPCB  PCB   TYPE=DB,DBDNAME=CUSTDB,KEYLEN=6") + "00010000"));

        MacroStatement pcb = statements.get(0);
        assertEquals("CUSTPCB", pcb.label());
        assertEquals(Map.of("TYPE", "DB", "DBDNAME", "CUSTDB", "KEYLEN", "6"), pcb.keywords());
        assertEquals(4, pcb.line());
    }

    @Test
    @DisplayName("引用符の中の空白とコンマで演算項欄を切らない")
    void quotedBlanksAndCommasStayInTheOperand() {
        MacroStatement statement = MacroReader.read(deck(
                card("         DFSMARSH PATTERN='yyyy-MM-dd, HH:mm',SIZE=1"))).get(0);

        assertEquals(List.of("PATTERN='yyyy-MM-dd, HH:mm'", "SIZE=1"), statement.operands());
    }

    @Test
    @DisplayName("括弧の並びは 1 段ずつ分ける")
    void elementsSplitOneLevelOfParentheses() {
        MacroStatement statement = MacroReader.read(deck(
                card("         SEGM  NAME=TRAN,PARENT=((ACCT,SNGL))"))).get(0);
        String parent = statement.keywords().get("PARENT");

        assertEquals(List.of("(ACCT,SNGL)"), statement.elements(parent));
        assertEquals(List.of("ACCT", "SNGL"), statement.elements(statement.elements(parent).get(0)));
        assertEquals(List.of("ACCT"), statement.elements("ACCT"));
    }

    @Test
    @DisplayName("16 桁より前から書いた続きの行は断る")
    void aContinuationLineMustStartInColumn16() {
        ImsGenerationException failure = assertThrows(ImsGenerationException.class, () -> MacroReader.read(deck(
                more("         SEGM  NAME=CUST,"),
                card("          BYTES=40"))));

        assertEquals(2, failure.line());
        assertTrue(failure.reason().contains("column 16"), failure.getMessage());
    }

    @Test
    @DisplayName("続きの印があるのに次の行が無ければ断る")
    void aMissingContinuationLineIsRefused() {
        assertThrows(ImsGenerationException.class, () -> MacroReader.read(more("         SEGM  NAME=CUST,")));
    }

    @Test
    @DisplayName("同じキーワードを 2 度書いた文は断る")
    void aKeywordWrittenTwiceIsRefused() {
        MacroStatement statement = MacroReader.read(deck(card("         SEGM  NAME=A,NAME=B"))).get(0);

        assertThrows(ImsGenerationException.class, statement::keywords);
    }
}
