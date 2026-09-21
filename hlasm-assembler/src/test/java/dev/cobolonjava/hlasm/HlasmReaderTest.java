package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HlasmReaderTest {

    /** 72 桁に印がある行の 16 桁からを、演算項の続きとして読む。 */
    @Test
    @DisplayName("コンマで終わった行は、次の行の 16 桁から演算項を続ける")
    void continuesOperandsAfterComma() {
        String source = column("LABEL    LM    R2,R4,", 71, "X") + "\n"
                + column("               SAVE", 71, " ");
        List<Statement> statements = HlasmReader.read(source);
        assertEquals(1, statements.size());
        assertEquals("LABEL", statements.get(0).label());
        assertEquals("LM", statements.get(0).operation());
        assertEquals("R2,R4,SAVE", statements.get(0).operands());
    }

    /**
     * コンマで終わらない行の続きは注記である。ここを取り違えると、注記が演算項に混ざって
     * 「演算項の数が合わない」という別の理由で止まり、原因が散る。
     */
    @Test
    @DisplayName("コンマで終わらない行の続きは注記として捨てる")
    void treatsContinuationAsRemarkWhenOperandsEnded() {
        String source = column("         MVC   A,B            move it", 71, "X") + "\n"
                + column("               and keep going", 71, " ");
        List<Statement> statements = HlasmReader.read(source);
        assertEquals(1, statements.size());
        assertEquals("A,B", statements.get(0).operands());
    }

    @Test
    @DisplayName("演算項欄は引用符の外の空白で終わり、そのあとは注記である")
    void stopsOperandsAtFirstBlankOutsideQuotes() {
        List<Statement> statements = HlasmReader.read("         DC    C'A B',C'C'  a remark");
        assertEquals("C'A B',C'C'", statements.get(0).operands());
    }

    @Test
    @DisplayName("1 桁の * と .* の行は注記である")
    void skipsComments() {
        List<Statement> statements = HlasmReader.read("* a comment\n.* a macro comment\n         BR    14");
        assertEquals(1, statements.size());
        assertEquals("BR", statements.get(0).operation());
    }

    @Test
    @DisplayName("73 桁以降の順序欄は読まない")
    void ignoresIdentificationField() {
        String source = column("         BR    14", 72, " ") + "SEQ00010";
        List<Statement> statements = HlasmReader.read(source);
        assertEquals("14", statements.get(0).operands());
    }

    /**
     * ICTL は桁の割当てを変える。黙って既定の桁で読むと、原文と違う文を組み立てる。
     * 道具が処理系の失敗を作らないよう、読めないことを断る。
     */
    @Test
    @DisplayName("ICTL は断る")
    void rejectsIctl() {
        AssemblyException failure = assertThrows(AssemblyException.class,
                () -> HlasmReader.read("         ICTL  1,71,16"));
        assertTrue(failure.getMessage().contains("ICTL"));
    }

    @Test
    @DisplayName("続きの行が 16 桁より前から書かれていれば断る")
    void rejectsContinuationBeforeColumn16() {
        String source = column("         LM    R2,R4,", 71, "X") + "\n"
                + column("      SAVE", 71, " ");
        assertThrows(AssemblyException.class, () -> HlasmReader.read(source));
    }

    @Test
    @DisplayName("括弧と引用符の中のコンマでは演算項を分けない")
    void splitsOperandsAtTopLevelCommasOnly() {
        List<Statement> statements = HlasmReader.read("         MVC   0(8,R1),0(R2)");
        assertEquals(List.of("0(8,R1)", "0(R2)"), statements.get(0).operandList());
    }

    /** 指定した桁 (1 起点) に文字を置く。 */
    private static String column(String text, int column, String mark) {
        StringBuilder out = new StringBuilder(text);
        while (out.length() < column) {
            out.append(' ');
        }
        out.append(mark);
        return out.toString();
    }
}
