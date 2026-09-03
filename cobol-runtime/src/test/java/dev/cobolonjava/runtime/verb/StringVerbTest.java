package dev.cobolonjava.runtime.verb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class StringVerbTest {

    private static final CodePage CP = CodePages.IBM_1047;

    private static byte[] b(String s) {
        return CP.encode(s);
    }

    private static String s(byte[] bytes) {
        return CP.decode(bytes);
    }

    @Test
    @DisplayName("DELIMITED BY SIZE は送出項目の全体を連結する (FR-065)")
    void delimitedBySize() {
        StringVerb.Result r = StringVerb.string(b(".........."), 1,
                List.of(StringVerb.Source.bySize(b("ABC")), StringVerb.Source.bySize(b("DE"))));
        assertEquals("ABCDE.....", s(r.target()));
        assertEquals(6, r.pointer());
        assertFalse(r.overflow());
    }

    @Test
    @DisplayName("STRING は受取項目を空白で埋めない。書き込まれなかった位置は元の内容が残る (FR-065)")
    void stringDoesNotPadTheTarget() {
        // 前回の内容が残ることを見落とすと、短い文字列を書いたときに前の値の尾が見えてしまう
        StringVerb.Result r = StringVerb.string(b("XXXXXXXXXX"), 1,
                List.of(StringVerb.Source.bySize(b("AB"))));
        assertEquals("ABXXXXXXXX", s(r.target()));
    }

    @Test
    @DisplayName("DELIMITED BY 定数 は区切り文字の手前までを送る (FR-065)")
    void delimitedByLiteral() {
        StringVerb.Result r = StringVerb.string(b(".........."), 1, List.of(
                StringVerb.Source.delimitedBy(b("ABC-DEF"), b("-")),
                StringVerb.Source.bySize(b("/")),
                StringVerb.Source.delimitedBy(b("GH-IJ"), b("-"))));
        assertEquals("ABC/GH....", s(r.target()));
        assertEquals(7, r.pointer());
    }

    @Test
    @DisplayName("区切り文字が見つからなければ送出項目の全体が送られる (FR-065)")
    void delimiterNotFoundSendsWholeValue() {
        StringVerb.Result r = StringVerb.string(b("....."), 1,
                List.of(StringVerb.Source.delimitedBy(b("ABC"), b("-"))));
        assertEquals("ABC..", s(r.target()));
    }

    @Test
    @DisplayName("WITH POINTER は書き込み開始位置を決める (FR-065)")
    void withPointer() {
        StringVerb.Result r = StringVerb.string(b(".........."), 4,
                List.of(StringVerb.Source.bySize(b("ABC"))));
        assertEquals("...ABC....", s(r.target()));
        assertEquals(7, r.pointer());
    }

    @Test
    @DisplayName("受取項目に収まらなければオーバーフローになる。収まった分は書き込まれる (FR-065)")
    void overflowStopsAtTheEnd() {
        StringVerb.Result r = StringVerb.string(b("...."), 1,
                List.of(StringVerb.Source.bySize(b("ABC")), StringVerb.Source.bySize(b("DEF"))));
        assertTrue(r.overflow());
        assertEquals("ABCD", s(r.target()));
        assertEquals(5, r.pointer());
    }

    @Test
    @DisplayName("ポインタが範囲外なら何も転記せずオーバーフローになる (FR-065)")
    void pointerOutOfRangeTransfersNothing() {
        for (int pointer : new int[] {0, -1, 11}) {
            StringVerb.Result r = StringVerb.string(b(".........."), pointer,
                    List.of(StringVerb.Source.bySize(b("ABC"))));
            assertTrue(r.overflow(), "pointer=" + pointer);
            assertEquals("..........", s(r.target()), "pointer=" + pointer);
        }
    }
}
