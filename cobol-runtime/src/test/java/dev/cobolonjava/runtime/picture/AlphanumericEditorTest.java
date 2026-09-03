package dev.cobolonjava.runtime.picture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("V1")
class AlphanumericEditorTest {

    private static final CodePage CP = CodePages.IBM_1047;

    private static String edit(String picture, String source) {
        Picture p = PictureParser.parse(picture);
        byte[] out = AlphanumericEditor.edit(CP.encode(source), p, CP);
        assertEquals(p.size(), out.length, "編集結果の長さは PICTURE のバイト長と一致する");
        return CP.decode(out);
    }

    @ParameterizedTest(name = "PIC {0} に [{1}] を編集すると [{2}]")
    @CsvSource(delimiter = '|', ignoreLeadingAndTrailingWhitespace = false, value = {
            "XX/XX/XX   | 311225   |31/12/25",
            "XXXBXXX    | ABCDEF   |ABC DEF",
            "XXXBXXX    | ABC      |ABC    ",
            "XXX0XXX    | ABCDEF   |ABC0DEF",
            "XX/XX      | ABCD     |AB/CD",
            "AAABAAA    | ABCDEF   |ABC DEF",
            "X(3)BX(3)  | ABCDEFGH |ABC DEF",
    })
    @DisplayName("英数字編集のゴールデン値 (FR-030)")
    void editing(String picture, String source, String expected) {
        assertEquals(expected, edit(picture.trim(), source.trim()));
    }

    @Test
    @DisplayName("送出データが足りなければ残りの文字位置は空白で埋まる (FR-060)")
    void shortSourceIsPaddedWithSpaces() {
        assertEquals("A      ", edit("XXXBXXX", "A"));
        assertEquals("       ", edit("XXXBXXX", ""));
    }

    @Test
    @DisplayName("送出データが多ければ切り捨てられる (FR-060)")
    void longSourceIsTruncated() {
        assertEquals("ABC DEF", edit("XXXBXXX", "ABCDEFGHIJ"));
    }

    @Test
    @DisplayName("文字位置の数と挿入文字はバイト長に別々に効く (FR-030)")
    void sizeAndCharacterPositions() {
        Picture p = PictureParser.parse("XX/XX/XX");
        assertEquals(8, p.size());
        assertEquals(6, p.characterPositions());
        assertEquals(Picture.Category.ALPHANUMERIC_EDITED, p.category());
    }

    @Test
    @DisplayName("英数字編集でない PICTURE は誤りとして検出する")
    void nonAlphanumericEditedIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AlphanumericEditor.edit(CP.encode("1"), PictureParser.parse("ZZ9"), CP));
        assertThrows(IllegalArgumentException.class,
                () -> AlphanumericEditor.edit(CP.encode("A"), PictureParser.parse("X(3)"), CP));
    }
}
