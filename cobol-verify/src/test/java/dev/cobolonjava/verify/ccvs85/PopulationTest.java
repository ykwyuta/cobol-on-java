package dev.cobolonjava.verify.ccvs85;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CCVS85 を翻訳できる原文へ起こす (要件 NFR-040)。
 *
 * <p>ここが狂うと<b>処理系の失敗を道具が作る</b>。合格率がいくら低くても、その数が
 * 処理系のものでなければ意味がない。
 */
@Tag("V1")
class PopulationTest {

    private static final XCards CARDS = new XCards(Map.of(
            82, "COBOL-ON-JAVA",
            84, "OMITTED",
            55, "PRINT-FILE"));

    /** 1 行を起こす。 */
    private static String one(Population population, String line) {
        Ccvs85Archive.Member member = new Ccvs85Archive.Member(
                Ccvs85Archive.Kind.COBOL, "NC101A", List.of(line));
        return population.apply(member).text().stripTrailing();
    }

    // ---- 7 桁目の英字 ----

    @Test
    @DisplayName("選ばなかった英字の行は注釈になる (NFR-040)")
    void anUnselectedOptionalLineBecomesAComment() {
        String line = "032700S    EXIT PROGRAM.";

        String out = one(Population.plain(CARDS), line);

        // 7 桁目だけを書き換える。中身は消さない
        assertEquals("032700*    EXIT PROGRAM.", out);
    }

    @Test
    @DisplayName("注釈にするとき 8 桁目を潰さない (NFR-040)")
    void commentingOutDoesNotDestroyColumnEight() {
        // 8 桁目は A 領域の先頭であり、段落見出しが始まる場所である。
        // 印をそこへ逃がすと「*APECIAL-NAMES.」になり、道具が原文を壊す
        String line = "003300CSPECIAL-NAMES.";

        String out = one(Population.plain(CARDS), line);

        assertEquals("003300*SPECIAL-NAMES.", out);
    }

    @Test
    @DisplayName("支えている機能の行は活きる (NFR-040)")
    void aSupportedOptionComesAlive() {
        // 何も選ばないと形が壊れるプログラムがある。選ばなかったせいで構文誤りに
        // なるのは、道具が処理系の失敗を作っているのと同じである
        assertEquals("003300 SPECIAL-NAMES.",
                one(Population.plain(CARDS), "003300ASPECIAL-NAMES."));
        assertEquals("003300*SPECIAL-NAMES.",
                one(Population.bare(CARDS), "003300ASPECIAL-NAMES."));
    }

    @Test
    @DisplayName("選んだ英字の行は 7 桁目を空けて活きる (NFR-040)")
    void aSelectedOptionalLineComesAlive() {
        String line = "032700S    EXIT PROGRAM.";

        String out = one(new Population(Set.of('S'), CARDS), line);

        assertEquals("032700     EXIT PROGRAM.", out);
    }

    @Test
    @DisplayName("D はデバッグ行なのでそのまま通す (NFR-040)")
    void theDebuggingIndicatorIsLeftAlone() {
        // COBOL の決まりが 7 桁目の D をすでに使っている。選べる行の印と取り違えない
        String line = "032700D    DISPLAY X.";

        assertEquals(line, one(Population.plain(CARDS), line));
    }

    @Test
    @DisplayName("注釈になった行の札は要らない (NFR-040)")
    void aCardOnACommentedOutLineIsNotNeeded() {
        // 7 桁目の印で落とした行に札が載っていても、その行は動かない。
        // 数えてしまうと、流せるプログラムを流さなくなる
        Population.Result result = Population.plain(CARDS).apply(new Ccvs85Archive.Member(
                Ccvs85Archive.Kind.COBOL, "NC101A", List.of("000100C    XXXXX999")));

        assertEquals(Set.of(), result.missing());
        assertTrue(result.text().startsWith("000100*    XXXXX999"), result.text());
    }

    @Test
    @DisplayName("注釈と継続はそのまま通す (NFR-040)")
    void commentsAndContinuationsAreLeftAlone() {
        assertEquals("032700* COMMENT", one(Population.plain(CARDS), "032700* COMMENT"));
        assertEquals("032700-    \"X\"", one(Population.plain(CARDS), "032700-    \"X\""));
    }

    // ---- 差し込み札 ----

    @Test
    @DisplayName("XXXXXnnn は札の文字へ置き換わる (NFR-040)")
    void anXCardIsSubstituted() {
        String line = "003600     XXXXX082.";

        String out = one(Population.plain(CARDS), line);

        assertEquals("003600     COBOL-ON-JAVA.", out.stripTrailing());
    }

    @Test
    @DisplayName("20 桁目が空白なら札の末尾の句点を落とす (NFR-040)")
    void aCardInTheMiddleOfASentenceLosesItsFullStop() {
        // 文の途中に置かれる札もある。そこへ句点を持ち込むと文が切れる
        XCards cards = new XCards(Map.of(55, "PRINT-FILE."));
        String line = "004200     XXXXX055 ";

        assertEquals("004200     PRINT-FILE", one(Population.plain(cards), line).stripTrailing());
    }

    @Test
    @DisplayName("20 桁目が句点なら札の末尾に句点を足す (NFR-040)")
    void aCardAtTheEndOfASentenceGetsAFullStop() {
        XCards cards = new XCards(Map.of(55, "PRINT-FILE"));
        String line = "004200     XXXXX055.";

        assertEquals("004200     PRINT-FILE.", one(Population.plain(cards), line).stripTrailing());
    }

    @Test
    @DisplayName("73 桁目から先は残る (NFR-040)")
    void theSequenceAreaSurvivesTheSubstitution() {
        String line = "003600     XXXXX082.                                                    NC1014.2";

        String out = one(Population.plain(CARDS), line);

        assertTrue(out.endsWith("NC1014.2"), out);
        assertEquals(80, out.length());
    }

    @Test
    @DisplayName("用意していない札は置き換えず、報せる (NFR-040)")
    void aMissingCardIsReportedNotGuessed() {
        // 黙って XXXXX014 を残すと、処理系が読めなかったのか札を書き忘れたのかが
        // 区別できなくなる。検査の道具が処理系の失敗を作ってはならない
        Ccvs85Archive.Member member = new Ccvs85Archive.Member(Ccvs85Archive.Kind.COBOL,
                "SQ101A", List.of("004200     XXXXX014."));

        Population.Result result = Population.plain(CARDS).apply(member);

        assertEquals(Set.of(14), result.missing());
        assertTrue(result.text().contains("XXXXX014"), result.text());
    }

    @Test
    @DisplayName("XXXX に見えても番号でなければ触らない (NFR-040)")
    void somethingThatIsNotACardIsLeftAlone() {
        String line = "004200     XXXXXABC.";

        assertEquals(line, one(Population.plain(CARDS), line));
    }

    @Test
    @DisplayName("札が足りないプログラムは流さない (NFR-040)")
    void programsWithMissingCardsAreNotRun() {
        Ccvs85Archive archive = new Ccvs85Archive(List.of(
                new Ccvs85Archive.Member(Ccvs85Archive.Kind.COBOL, "NC101A",
                        List.of("003600     XXXXX082.")),
                new Ccvs85Archive.Member(Ccvs85Archive.Kind.COBOL, "SQ101A",
                        List.of("004200     XXXXX014."))));

        List<Population.Program> programs = Population.plain(CARDS).populate(archive);

        assertTrue(programs.get(0).missing().isEmpty());
        assertEquals(Set.of(14), programs.get(1).missing());
    }

    @Test
    @DisplayName("索引編成の鍵の長さは T か U のどちらかを必ず選ぶ (U を選ぶ)")
    void oneOfTheIndexedKeyLengthsIsAlwaysChosen() {
        // 原文の但し書きが「どちらか一方を選べ」と言っている。どちらも選ばないと
        // レコードが 10 バイト足りなくなり、副鍵の位置がずれる。
        // <b>道具が処理系の失敗を作ってはならない</b>
        assertEquals("013900     10 FILLER           PICTURE X(5).",
                one(Population.plain(CARDS), "013900U    10 FILLER           PICTURE X(5)."));
        assertEquals("013800*       15 FILLER        PICTURE 9(5).",
                one(Population.plain(CARDS), "013800T       15 FILLER        PICTURE 9(5)."));
    }
}
