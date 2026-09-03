package dev.cobolonjava.compiler.source;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class SourceListingTest {

    private static final String FILE = "MAIN.cbl";

    private static String source(String... contents) {
        StringBuilder sb = new StringBuilder();
        for (String content : contents) {
            sb.append("       ").append(content).append('\n');
        }
        return sb.toString();
    }

    private static List<SourceListing.ListingLine> lines(String... contents) {
        return SourceListing.lines(
                FixedFormatReader.standard().normalize(FILE, source(contents)));
    }

    private static String texts(List<SourceListing.ListingLine> lines) {
        StringBuilder sb = new StringBuilder();
        for (SourceListing.ListingLine line : lines) {
            sb.append(line.text()).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("元のソース行ごとに折り返す (FR-094)")
    void theListingBreaksAtEverySourceLine() {
        List<SourceListing.ListingLine> lines = lines(
                "MOVE A TO B.",
                "MOVE C TO D.");
        assertEquals("MOVE A TO B.\nMOVE C TO D.\n", texts(lines));
    }

    @Test
    @DisplayName("出力の行番号は 1 から順に振る (FR-094)")
    void outputLinesAreNumberedFromOne() {
        List<SourceListing.ListingLine> lines = lines(
                "MOVE A TO B.",
                "MOVE C TO D.",
                "MOVE E TO F.");
        assertEquals(List.of(1, 2, 3), lines.stream().map(SourceListing.ListingLine::number).toList());
    }

    @Test
    @DisplayName("各行は元のソース上の位置を伴う (FR-094)")
    void everyLineCarriesItsSourcePosition() {
        List<SourceListing.ListingLine> lines = lines(
                "MOVE A TO B.",
                "MOVE C TO D.");
        assertEquals(FILE, lines.get(1).origin().fileName());
        assertEquals(2, lines.get(1).origin().line());
    }

    @Test
    @DisplayName("注釈行は出力に現れず、その分だけ番号がずれる (FR-002, FR-094)")
    void commentLinesDoNotAppear() {
        String text = "       MOVE A TO B.\n      * a remark\n       MOVE C TO D.\n";
        List<SourceListing.ListingLine> lines =
                SourceListing.lines(FixedFormatReader.standard().normalize(FILE, text));
        assertEquals("MOVE A TO B.\nMOVE C TO D.\n", texts(lines));
        assertEquals(3, lines.get(1).origin().line(), "2 行目ではなく 3 行目から来ている");
    }

    @Test
    @DisplayName("継続行は 1 本につながって現れる (FR-003, FR-094)")
    void continuedLinesAppearAsOne() {
        // 継続された部分は継続行の出自を持つので、折り返しは元の行のとおりに入る
        String text = "       MOVE 'AB\n      -    'CD' TO B.\n";
        List<SourceListing.ListingLine> lines =
                SourceListing.lines(FixedFormatReader.standard().normalize(FILE, text));
        assertEquals(2, lines.size());
        assertEquals("MOVE 'AB", lines.get(0).text());
        assertEquals("CD' TO B.", lines.get(1).text());
        assertEquals(2, lines.get(1).origin().line());
    }

    @Test
    @DisplayName("コピー句から来た行はコピー句の位置を示す (FR-090, FR-094)")
    void copiedLinesShowTheCopybookPosition() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("CUSTREC", source("01 CUST-REC.", "   05 CUST-ID PIC 9(5)."));

        List<SourceListing.ListingLine> lines = SourceListing.lines(
                Preprocessor.with(resolver).process(FILE, source(
                        "COPY CUSTREC.",
                        "MOVE A TO B.")));

        assertEquals("01 CUST-REC.\n05 CUST-ID PIC 9(5).\nMOVE A TO B.\n", texts(lines));
        assertEquals("CUSTREC", lines.get(0).origin().fileName());
        assertEquals("CUSTREC", lines.get(1).origin().fileName());
        assertEquals(2, lines.get(1).origin().line());
        assertEquals(FILE, lines.get(2).origin().fileName());
    }

    @Test
    @DisplayName("コピー句から来た行には印を付ける (FR-094)")
    void copiedLinesAreMarked() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("CUSTREC", source("01 CUST-REC."));

        String rendered = SourceListing.render(Preprocessor.with(resolver).process(FILE, source(
                "COPY CUSTREC.",
                "MOVE A TO B.")), FILE);

        List<String> out = rendered.lines().toList();
        assertEquals(2, out.size());
        assertEquals("    1 C CUSTREC:1            01 CUST-REC.", out.get(0));
        assertEquals("    2   MAIN.cbl:2           MOVE A TO B.", out.get(1));
    }

    @Test
    @DisplayName("REPLACING で差し込まれた語は COPY 文の側の行として現れる (FR-090, FR-094)")
    void replacedWordsAppearAsLinesOfTheCopyStatement() {
        // 置換で差し込まれる語はコピー句ではなく COPY を書いた行から来ている。
        // 出自が変わるので、1 つのコピー句の行が複数の出力行に割れる
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("CUSTREC", source("01 OLD-REC."));

        List<SourceListing.ListingLine> lines = SourceListing.lines(
                Preprocessor.with(resolver).process(FILE, source(
                        "COPY CUSTREC REPLACING ==OLD-REC== BY ==NEW-REC==.")));

        assertEquals(3, lines.size());
        assertEquals("01", lines.get(0).text());
        assertEquals("CUSTREC", lines.get(0).origin().fileName());
        assertEquals("NEW-REC", lines.get(1).text());
        assertEquals(FILE, lines.get(1).origin().fileName(), "置換した語は COPY を書いた側から来る");
        assertEquals(".", lines.get(2).text());
        assertEquals("CUSTREC", lines.get(2).origin().fileName());
    }

    @Test
    @DisplayName("空のソースからは何も出ない (FR-094)")
    void anEmptySourceProducesNothing() {
        assertEquals(List.of(), SourceListing.lines(
                FixedFormatReader.standard().normalize(FILE, "")));
        assertEquals("", SourceListing.render(
                FixedFormatReader.standard().normalize(FILE, ""), FILE));
    }
}
