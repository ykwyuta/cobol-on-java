package dev.cobolonjava.compiler.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class FixedFormatReaderTest {

    private static final String FILE = "TEST.cbl";

    /** 一連番号領域を空白にし、標識と本文を置いた 1 行を作る。 */
    private static String line(char indicator, String content) {
        return "      " + indicator + content;
    }

    private static String normalize(String... lines) {
        return FixedFormatReader.standard().normalize(FILE, String.join("\n", lines)).text();
    }

    @Test
    @DisplayName("一連番号領域と識別領域は無視される (FR-002)")
    void sequenceAndIdentificationAreasAreIgnored() {
        // 1-6 桁の一連番号と 73-80 桁の識別領域は本文に含まれない
        String raw = "000100 " + "    MOVE A TO B." + " ".repeat(72 - 7 - 16) + "IDENTIFY";
        assertEquals("MOVE A TO B.", normalize(raw));
    }

    @Test
    @DisplayName("注釈行と改ページ行は取り除かれる (FR-003)")
    void commentAndEjectLinesAreRemoved() {
        assertEquals("MOVE A TO B.", normalize(
                line('*', " これは注釈"),
                line('/', " 改ページを伴う注釈"),
                line(' ', "    MOVE A TO B.")));
    }

    @Test
    @DisplayName("デバッグ行は WITH DEBUGGING MODE のときだけ有効になる (FR-193)")
    void debugLinesDependOnDebuggingMode() {
        String[] source = {
                line(' ', "    MOVE A TO B."),
                line('D', "    DISPLAY A."),
        };
        assertEquals("MOVE A TO B.",
                FixedFormatReader.standard().normalize(FILE, String.join("\n", source)).text());
        assertEquals("MOVE A TO B. DISPLAY A.",
                new FixedFormatReader(true).normalize(FILE, String.join("\n", source)).text());
    }

    @Test
    @DisplayName("通常の行は区切りの空白を挟んで連結される (FR-002)")
    void normalLinesAreJoinedWithASpace() {
        assertEquals("MOVE A TO B. MOVE C TO D.", normalize(
                line(' ', "    MOVE A TO B."),
                line(' ', "    MOVE C TO D.")));
    }

    @Test
    @DisplayName("語の途中の継続は区切りを入れずに連結される (FR-003)")
    void wordContinuationJoinsWithoutASeparator() {
        assertEquals("MOVE WS-FIELD TO X.", normalize(
                line(' ', "    MOVE WS-"),
                line('-', "        FIELD TO X.")));
    }

    @Test
    @DisplayName("文字定数の継続では、継続される側が 72 桁まで定数の一部になる (FR-003)")
    void literalContinuationExtendsToTheMargin() {
        // 本文は 8 桁目から始まる。"    MOVE 'AB" は 12 文字なので 19 桁まで。
        // 定数が閉じていないため 20〜72 桁の 53 個の空白も定数の一部になる。
        // この規則を落とすと、行末の空白を削ったソースで定数の長さが変わってしまう。
        String expected = "MOVE 'AB" + " ".repeat(53) + "CD' TO X.";
        assertEquals(expected, normalize(
                line(' ', "    MOVE 'AB"),
                line('-', "    'CD' TO X.")));
    }

    @Test
    @DisplayName("継続行の引用符は定数に含まれない (FR-003)")
    void theResumingQuoteIsNotPartOfTheLiteral() {
        String text = normalize(
                line(' ', "    MOVE 'AB"),
                line('-', "    'CD' TO X."));
        // 定数の中身は AB + 53 個の空白 + CD である
        int open = text.indexOf('\'');
        int close = text.indexOf('\'', open + 1);
        assertEquals(2 + 53 + 2, close - open - 1);
    }

    @Test
    @DisplayName("定数の中の引用符 2 個は定数を閉じない (FR-003)")
    void doubledQuotesDoNotCloseTheLiteral() {
        assertEquals("MOVE 'IT''S' TO X.", normalize(line(' ', "    MOVE 'IT''S' TO X.")));
    }

    @Test
    @DisplayName("継続行が引用符で再開しなければ誤りとする (FR-003)")
    void continuationMustResumeWithAQuote() {
        SourceFormatException e = assertThrows(SourceFormatException.class, () -> normalize(
                line(' ', "    MOVE 'AB"),
                line('-', "    CD' TO X.")));
        assertTrue(e.getMessage().contains("quotation"), e.getMessage());
    }

    @Test
    @DisplayName("定数が閉じないまま継続行以外が続けば誤りとする (FR-003)")
    void unclosedLiteralWithoutContinuationIsAnError() {
        assertThrows(SourceFormatException.class, () -> normalize(
                line(' ', "    MOVE 'AB"),
                line(' ', "    MOVE C TO D.")));
    }

    @Test
    @DisplayName("標識領域に妥当でない文字があれば誤りとする (FR-002)")
    void invalidIndicatorIsRejected() {
        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> normalize(line('X', "    MOVE A TO B.")));
        assertTrue(e.getMessage().contains("TEST.cbl:1"), e.getMessage());
    }

    @Test
    @DisplayName("正規化後の各文字から元のファイル・行・桁へ戻れる (FR-094)")
    void everyCharacterKeepsItsOrigin() {
        NormalizedSource source = FixedFormatReader.standard().normalize(FILE, String.join("\n",
                line(' ', "    MOVE A"),
                line(' ', "    TO B.")));
        assertEquals("MOVE A TO B.", source.text());

        // 先頭の M は 1 行目の 12 桁目
        assertEquals(new Origin(FILE, 1, 12), source.originOf(0));
        // "MOVE A" の A は 1 行目の 17 桁目
        assertEquals(new Origin(FILE, 1, 17), source.originOf(5));
        // "TO" の T は 2 行目の 12 桁目。継続していないので行をまたぐ
        assertEquals(new Origin(FILE, 2, 12), source.originOf(7));
    }

    @Test
    @DisplayName("継続行の文字も元の行を指す (FR-094)")
    void continuationCharactersPointAtTheirOwnLine() {
        NormalizedSource source = FixedFormatReader.standard().normalize(FILE, String.join("\n",
                line(' ', "    MOVE WS-"),
                line('-', "        FIELD TO X.")));
        assertEquals("MOVE WS-FIELD TO X.", source.text());
        // "FIELD" の F は 2 行目の 16 桁目
        int index = source.text().indexOf("FIELD");
        assertEquals(new Origin(FILE, 2, 16), source.originOf(index));
    }
}
