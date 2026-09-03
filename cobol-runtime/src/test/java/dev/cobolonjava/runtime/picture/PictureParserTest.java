package dev.cobolonjava.runtime.picture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.picture.Picture.Category;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("V1")
class PictureParserTest {

    @ParameterizedTest(name = "PIC {0} -> {1} size={2} digits={3} scale={4}")
    @CsvSource({
            // PICTURE,      category,          size, digits, scale
            "'9(5)',         NUMERIC,             5,      5,     0",
            "'S9(5)',        NUMERIC,             5,      5,     0",
            "'S9(3)V99',     NUMERIC,             5,      5,     2",
            "'V999',         NUMERIC,             3,      3,     3",
            "'9',            NUMERIC,             1,      1,     0",
            "'X(10)',        ALPHANUMERIC,       10,      0,     0",
            "'A(5)',         ALPHABETIC,          5,      0,     0",
            "'ZZZ,ZZ9.99',   NUMERIC_EDITED,     10,      8,     2",
            "'**,**9.99',    NUMERIC_EDITED,      9,      7,     2",
            "'$$$,$$9.99',   NUMERIC_EDITED,     10,      7,     2",
            "'----9',        NUMERIC_EDITED,      5,      4,     0",
            "'+9(4)',        NUMERIC_EDITED,      5,      4,     0",
            "'9(3).99CR',    NUMERIC_EDITED,      8,      5,     2",
            "'ZZZZ',         NUMERIC_EDITED,      4,      4,     0",
    })
    @DisplayName("PICTURE の桁数・バイト長・小数位置 (FR-030)")
    void attributes(String picture, Category category, int size, int digits, int scale) {
        Picture p = PictureParser.parse(picture);
        assertEquals(category, p.category(), "category of " + picture);
        assertEquals(size, p.size(), "size of " + picture);
        assertEquals(digits, p.digits(), "digits of " + picture);
        assertEquals(scale, p.scale(), "scale of " + picture);
    }

    @ParameterizedTest(name = "PIC {0} の scale は {1}")
    @CsvSource({
            "'999PPP', -3",
            "'PPP999',  6",
            "'P999',    4",
            "'999PP',  -2",
    })
    @DisplayName("P は桁位置を指定するが格納されない (FR-030)")
    void scalingPositions(String picture, int expectedScale) {
        Picture p = PictureParser.parse(picture);
        assertEquals(expectedScale, p.scale(), "scale of " + picture);
        assertEquals(3, p.digits(), "P は格納されないので digits は 3");
        assertEquals(3, p.size(), "P はバイトを占めない");
    }

    @Test
    @DisplayName("S は符号付きを表し、バイト長には影響しない (FR-030)")
    void signIsNotStoredSeparatelyByDefault() {
        Picture signed = PictureParser.parse("S9(5)");
        Picture unsigned = PictureParser.parse("9(5)");
        assertEquals(SignPosition.TRAILING, signed.signPosition());
        assertEquals(SignPosition.UNSIGNED, unsigned.signPosition());
        assertEquals(unsigned.size(), signed.size());
    }

    @Test
    @DisplayName("繰り返し指定は展開される")
    void repetitionIsExpanded() {
        assertEquals("S99999", PictureParser.parse("S9(5)").expanded());
        assertEquals("XXX", PictureParser.parse("X(3)").expanded());
    }

    @Test
    @DisplayName("構文誤りは検出される")
    void syntaxErrors() {
        assertThrows(PictureSyntaxException.class, () -> PictureParser.parse(""));
        assertThrows(PictureSyntaxException.class, () -> PictureParser.parse("9S9"));
        assertThrows(PictureSyntaxException.class, () -> PictureParser.parse("9V9V9"));
        assertThrows(PictureSyntaxException.class, () -> PictureParser.parse("9(3"));
        assertThrows(PictureSyntaxException.class, () -> PictureParser.parse("9%9"));
        assertThrows(PictureSyntaxException.class, () -> PictureParser.parse("SZZ9"),
                "数字編集項目に S は書けない");
    }
}
