package dev.cobolonjava.compiler.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class FreeFormatReaderTest {

    private static final String FILE = "MAIN.cbl";

    private static NormalizedSource read(String... lines) {
        return FreeFormatReader.standard().normalize(FILE, String.join("\n", lines));
    }

    private static String text(String... lines) {
        return read(lines).text();
    }

    @Test
    @DisplayName("カラムの区分がなく、行の全体が本文である (FR-002)")
    void thereAreNoColumnAreas() {
        assertEquals("IDENTIFICATION DIVISION. PROGRAM-ID. HELLO.",
                text("IDENTIFICATION DIVISION.", "PROGRAM-ID. HELLO."));
    }

    @Test
    @DisplayName("行が変われば語の区切りが入る (FR-002)")
    void aLineBreakSeparatesWords() {
        // 固定形式と違い、文の途中で行を変えるのに継続の印は要らない
        assertEquals("MOVE A TO B.", text("MOVE A", "   TO B."));
    }

    @Test
    @DisplayName("*> だけの行は注釈である (FR-002)")
    void aLineThatStartsWithTheCommentIndicatorIsDropped() {
        assertEquals("MOVE A TO B.", text("*> a remark", "MOVE A TO B."));
    }

    @Test
    @DisplayName("行の途中の *> 以降は注釈である (FR-002)")
    void everythingAfterTheCommentIndicatorIsDropped() {
        assertEquals("MOVE A TO B.", text("MOVE A TO B.  *> why"));
    }

    @Test
    @DisplayName("文字定数の中の *> は注釈ではない (FR-002)")
    void aCommentIndicatorInsideALiteralIsNotAComment() {
        // 落としてしまうと、黙って別のソースになる
        assertEquals("MOVE '*>' TO B.", text("MOVE '*>' TO B."));
    }

    @Test
    @DisplayName("空の行と空白だけの行は落とす (FR-002)")
    void blankLinesAreDropped() {
        assertEquals("MOVE A TO B.", text("", "   ", "MOVE A TO B.", ""));
    }

    @Test
    @DisplayName("定数は行末のハイフンで継続する (FR-002, FR-003)")
    void aLiteralIsContinuedByATrailingHyphen() {
        assertEquals("MOVE 'ABCD' TO B.", text("MOVE 'AB-", "     'CD' TO B."));
    }

    @Test
    @DisplayName("ハイフンの直前の空白は定数の一部になる (FR-003)")
    void spacesBeforeTheHyphenBelongToTheLiteral() {
        // ハイフンがある理由そのものである。これがないと行末の空白が定数に入るか決まらない
        assertEquals("MOVE 'AB  CD' TO B.", text("MOVE 'AB  -", "     'CD' TO B."));
    }

    @Test
    @DisplayName("継続は 3 行以上にわたってもよい (FR-003)")
    void aLiteralMayBeContinuedSeveralTimes() {
        assertEquals("MOVE 'ABCDEF' TO B.",
                text("MOVE 'AB-", "     'CD-", "     'EF' TO B."));
    }

    @Test
    @DisplayName("継続行は同じ引用符から再開しなければならない (FR-003)")
    void aContinuationMustResumeWithTheSameQuotationCharacter() {
        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> text("MOVE 'AB-", "     \"CD' TO B."));
        assertTrue(e.getMessage().contains("quotation character"), e.getMessage());
    }

    @Test
    @DisplayName("ハイフンのない未閉じの定数は誤りとして検出する (FR-003)")
    void anUnclosedLiteralWithoutAHyphenIsRejected() {
        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> text("MOVE 'AB", "     'CD' TO B."));
        assertTrue(e.getMessage().contains("end the line with"), e.getMessage());
    }

    @Test
    @DisplayName("閉じられないまま終わる定数は誤りとして検出する (FR-003)")
    void aLiteralLeftOpenAtEndOfSourceIsRejected() {
        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> text("MOVE 'AB-"));
        assertTrue(e.getMessage().contains("unclosed"), e.getMessage());
    }

    @Test
    @DisplayName("定数の外の行末ハイフンは継続ではない (P-022)")
    void aTrailingHyphenOutsideALiteralIsOrdinaryText() {
        // COMPUTE A = B - / C で行を変える書き方を壊さない
        assertEquals("COMPUTE A = B - C.", text("COMPUTE A = B -", "        C."));
    }

    @Test
    @DisplayName("桁は 1 起点でそのまま記録する (FR-094)")
    void columnsAreRecordedAsTheyAre() {
        NormalizedSource source = read("   MOVE A TO B.");
        Origin origin = source.originOf(0);
        assertEquals(1, origin.line());
        assertEquals(4, origin.column(), "先頭の M は 4 桁目");
    }

    @Test
    @DisplayName("自由形式でも COPY と指示文はそのまま働く (FR-090, FR-092)")
    void copyAndDirectivesWorkInFreeFormatToo() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("REC", String.join("\n",
                        ">>IF WITH-AUDIT DEFINED",
                        "05 AUDIT-ID PIC 9(5).",
                        ">>END-IF",
                        "05 CUST-ID PIC 9(5)."));

        NormalizedSource result = Preprocessor.with(resolver, FreeFormatReader.standard())
                .process(FILE, String.join("\n", ">>DEFINE WITH-AUDIT AS 1", "COPY REC."));

        assertEquals("05 AUDIT-ID PIC 9(5). 05 CUST-ID PIC 9(5).", result.text());
    }
}
