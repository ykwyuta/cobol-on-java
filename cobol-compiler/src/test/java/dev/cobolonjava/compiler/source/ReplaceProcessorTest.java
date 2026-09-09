package dev.cobolonjava.compiler.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class ReplaceProcessorTest {

    private static final String FILE = "MAIN.cbl";

    private static String source(String... contents) {
        StringBuilder sb = new StringBuilder();
        for (String content : contents) {
            sb.append("       ").append(content).append('\n');
        }
        return sb.toString();
    }

    private static String replace(String... contents) {
        return ReplaceProcessor.apply(
                FixedFormatReader.standard().normalize(FILE, source(contents))).text();
    }

    @Test
    @DisplayName("REPLACE はその文より後ろのソース全体に効く (FR-091)")
    void replaceAffectsTheFollowingSource() {
        assertEquals("MOVE NEW TO B. MOVE C TO NEW.", replace(
                "REPLACE ==OLD== BY ==NEW==.",
                "MOVE OLD TO B.",
                "MOVE C TO OLD."));
    }

    @Test
    @DisplayName("REPLACE 文そのものは出力に残らない (FR-091)")
    void theReplaceStatementItselfIsRemoved() {
        assertEquals("MOVE A TO B.", replace(
                "REPLACE ==X== BY ==Y==.",
                "MOVE A TO B."));
    }

    @Test
    @DisplayName("REPLACE より前のソースには効かない (FR-091)")
    void textBeforeReplaceIsUntouched() {
        assertEquals("MOVE OLD TO B. MOVE NEW TO C.", replace(
                "MOVE OLD TO B.",
                "REPLACE ==OLD== BY ==NEW==.",
                "MOVE OLD TO C."));
    }

    @Test
    @DisplayName("REPLACE OFF で置換が止まる (FR-091)")
    void replaceOffStopsTheSubstitution() {
        assertEquals("MOVE NEW TO B. MOVE OLD TO C.", replace(
                "REPLACE ==OLD== BY ==NEW==.",
                "MOVE OLD TO B.",
                "REPLACE OFF.",
                "MOVE OLD TO C."));
    }

    @Test
    @DisplayName("次の REPLACE が前の指定を置き換える (FR-091)")
    void aSecondReplaceSupersedesTheFirst() {
        assertEquals("MOVE ONE TO B. MOVE TWO TO C. MOVE A TO D.", replace(
                "REPLACE ==OLD== BY ==ONE==.",
                "MOVE OLD TO B.",
                "REPLACE ==OLD== BY ==TWO==.",
                "MOVE OLD TO C.",
                "MOVE A TO D."));
    }

    @Test
    @DisplayName("複数の語にまたがる置換ができる (FR-091)")
    void replacementCanSpanSeveralWords() {
        assertEquals("MOVE ZERO TO B.", replace(
                "REPLACE ==ALL SPACES== BY ==ZERO==.",
                "MOVE ALL SPACES TO B."));
    }

    @Test
    @DisplayName("差し込んだ語は再び置換の対象にならない (FR-091)")
    void insertedWordsAreNotRescanned() {
        // 再走査すると ==A== BY ==A B== のような指定で終わらなくなる
        assertEquals("A B C.", replace(
                "REPLACE ==A== BY ==A B==.",
                "A C."));
    }

    @Test
    @DisplayName("REPLACE の被演算子は擬似テキストでなければならない (FR-091)")
    void replaceRequiresPseudoText() {
        // COPY ... REPLACING が 1 語の指定も許すのとは違う
        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> replace("REPLACE OLD BY NEW.", "MOVE OLD TO B."));
        assertTrue(e.getMessage().contains("pseudo-text"), e.getMessage());
    }

    @Test
    @DisplayName("終止符のない REPLACE は誤りとして検出する (FR-091)")
    void replaceMustBeTerminatedByAPeriod() {
        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> replace("REPLACE ==A== BY ==B=="));
        assertTrue(e.getMessage().contains("period"), e.getMessage());
    }

    @Test
    @DisplayName("被演算子のない REPLACE は誤りとして検出する (FR-091)")
    void replaceRequiresOperands() {
        assertThrows(SourceFormatException.class, () -> replace("REPLACE."));
    }

    @Test
    @DisplayName("REPLACE は COPY で展開された語にも効く (FR-090, FR-091)")
    void replaceAppliesToCopiedText() {
        // COBOL は COPY をすべて処理してから REPLACE を適用する。
        // 順序を逆にすると、コピー句の中身が置換の対象から漏れる
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("REC", source("01 OLD-REC.", "   05 OLD-ID PIC 9(5)."));

        NormalizedSource result = Preprocessor.with(resolver).process(FILE, source(
                "REPLACE ==OLD-REC== BY ==NEW-REC==.",
                "COPY REC."));

        assertEquals("01 NEW-REC. 05 OLD-ID PIC 9(5).", result.text());
    }

    @Test
    @DisplayName("照合ではコンマとセミコロンを空白と同じに扱う (FR-091)")
    void separatorCommasAndSemicolonsAreTreatedAsSpaces() {
        // 85 規格 XII 3.4 一般規則 6(b)。擬似テキストと本文で区切りの書き方が
        // 違っていても当たらなければならない (SM208A REP-TEST-8)
        assertEquals("MOVE \"PASS\" TO P-OR-F.", replace(
                "REPLACE ==MOVE;  \"FAIL\"  , TO== BY ==MOVE \"PASS\" TO==.",
                "MOVE  , \"FAIL\";      TO  P-OR-F."));
    }

    @Test
    @DisplayName("一致に含まれないコンマは残る (FR-091)")
    void aCommaOutsideTheMatchIsKept() {
        // 読み飛ばすのは語と語のあいだだけである。一致の先頭で読み飛ばすと、
        // 手前のコンマまで消えてしまう
        assertEquals("MOVE A , NEW TO B.", replace(
                "REPLACE ==OLD== BY ==NEW==.",
                "MOVE A , OLD TO B."));
    }

    @Test
    @DisplayName("終止符は空白と同じにはならない (FR-091)")
    void thePeriodIsNotASpaceSeparator() {
        // コンマとセミコロンだけが空白と同じである。終止符は文の切れ目を表すので、
        // 語のあいだに挟まっていれば一致しない
        assertEquals("X ADD-A . ADD-B", replace(
                "REPLACE ==ADD-A ADD-B== BY ==X==.",
                "ADD-A ; ADD-B ADD-A . ADD-B"));
    }

    @Test
    @DisplayName("差し込まれた語は REPLACE を書いた位置を指す (FR-094)")
    void replacementWordsKeepTheirOrigin() {
        NormalizedSource result = ReplaceProcessor.apply(
                FixedFormatReader.standard().normalize(FILE, source(
                        "REPLACE ==OLD== BY ==NEW==.",
                        "MOVE OLD TO B.")));
        int index = result.text().indexOf("NEW");
        Origin origin = result.originOf(index);
        assertEquals(FILE, origin.fileName());
        assertEquals(1, origin.line(), "REPLACE 文が書かれた 1 行目を指す");
    }
}
