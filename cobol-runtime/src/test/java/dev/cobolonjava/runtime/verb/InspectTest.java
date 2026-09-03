package dev.cobolonjava.runtime.verb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class InspectTest {

    private static final CodePage CP = CodePages.IBM_1047;

    private static byte[] b(String s) {
        return CP.encode(s);
    }

    private static String s(byte[] bytes) {
        return CP.decode(bytes);
    }

    @Test
    @DisplayName("TALLYING FOR ALL は重なりのない一致を数える (FR-065)")
    void tallyAll() {
        assertEquals(3, Inspect.tallyAll(b("ABABAB"), b("AB"), Region.whole()));
        assertEquals(1, Inspect.tallyAll(b("AAA"), b("AA"), Region.whole()),
                "一致した直後から走査を続けるため 2 ではなく 1 になる");
        assertEquals(0, Inspect.tallyAll(b("XYZ"), b("AB"), Region.whole()));
        assertEquals(3, Inspect.tallyAll(b("A-A-A"), b("A"), Region.whole()));
    }

    @Test
    @DisplayName("TALLYING FOR LEADING は先頭から連続する一致だけを数える (FR-065)")
    void tallyLeading() {
        assertEquals(3, Inspect.tallyLeading(b("000123"), b("0"), Region.whole()));
        assertEquals(0, Inspect.tallyLeading(b("123000"), b("0"), Region.whole()));
        assertEquals(2, Inspect.tallyLeading(b("ABABXAB"), b("AB"), Region.whole()));
    }

    @Test
    @DisplayName("TALLYING FOR CHARACTERS は範囲内の文字数を数える (FR-065)")
    void tallyCharacters() {
        assertEquals(6, Inspect.tallyCharacters(b("ABCDEF"), Region.whole()));
        assertEquals(3, Inspect.tallyCharacters(b("AB-CDE"), Region.after(b("AB-"))));
    }

    @Test
    @DisplayName("AFTER INITIAL の区切りが見つからなければ検査は行われない (FR-065)")
    void missingAfterDelimiterMeansNoInspection() {
        // 「全体を検査する」のではなく「一切検査しない」。取り違えると意図しない置換が起きる
        assertEquals(0, Inspect.tallyAll(b("ABCABC"), b("A"), Region.after(b("Z"))));
        assertEquals("ABCABC", s(Inspect.replaceAll(b("ABCABC"), b("A"), b("X"), Region.after(b("Z")))));
    }

    @Test
    @DisplayName("BEFORE INITIAL の区切りが見つからなければ末尾までが範囲になる (FR-065)")
    void missingBeforeDelimiterMeansToTheEnd() {
        assertEquals(2, Inspect.tallyAll(b("ABCABC"), b("A"), Region.before(b("Z"))));
    }

    @Test
    @DisplayName("BEFORE と AFTER を組み合わせると、その間だけが範囲になる (FR-065)")
    void betweenDelimiters() {
        // "[" の後、"]" の前
        assertEquals(2, Inspect.tallyAll(b("XX[AA]AA"), b("A"), Region.between(b("["), b("]"))));
        assertEquals("XX[BB]AA",
                s(Inspect.replaceAll(b("XX[AA]AA"), b("A"), b("B"), Region.between(b("["), b("]")))));
    }

    @Test
    @DisplayName("REPLACING の各形式 (FR-065)")
    void replacing() {
        assertEquals("XBXBXB", s(Inspect.replaceAll(b("ABABAB"), b("A"), b("X"), Region.whole())));
        assertEquals("XXX123", s(Inspect.replaceLeading(b("000123"), b("0"), b("X"), Region.whole())));
        assertEquals("123000", s(Inspect.replaceLeading(b("123000"), b("0"), b("X"), Region.whole())));
        assertEquals("XBABAB", s(Inspect.replaceFirst(b("ABABAB"), b("A"), b("X"), Region.whole())));
        assertEquals("******", s(Inspect.replaceCharacters(b("ABABAB"), b("*"), Region.whole())));
    }

    @Test
    @DisplayName("CONVERTING は文字単位の 1 対 1 変換を行う (FR-065)")
    void converting() {
        assertEquals("XYZ", s(Inspect.convert(b("ABC"), b("ABC"), b("XYZ"), Region.whole())));
        assertEquals("HELLO", s(Inspect.convert(b("hello"),
                b("abcdefghijklmnopqrstuvwxyz"), b("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), Region.whole())));
        // 範囲を限定できる
        assertEquals("abXYZ", s(Inspect.convert(b("abcde"), b("cde"), b("XYZ"), Region.after(b("ab")))));
    }

    @Test
    @DisplayName("CONVERTING で同じ文字が複数回現れた場合は最初の対応が使われる (FR-065)")
    void convertingUsesFirstMapping() {
        assertEquals("X", s(Inspect.convert(b("A"), b("AA"), b("XY"), Region.whole())));
    }

    @Test
    @DisplayName("長さの異なる置換・変換は誤りとして検出する")
    void lengthMismatchIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Inspect.replaceAll(b("ABC"), b("A"), b("XY"), Region.whole()));
        assertThrows(IllegalArgumentException.class,
                () -> Inspect.convert(b("ABC"), b("AB"), b("XYZ"), Region.whole()));
        assertThrows(IllegalArgumentException.class,
                () -> Inspect.replaceCharacters(b("ABC"), b("XY"), Region.whole()));
    }

    @Test
    @DisplayName("変換表は 256 バイトの恒等表に対応を書き込んだものである")
    void translationTable() {
        byte[] table = Inspect.translationTable(b("A"), b("Z"));
        assertEquals(256, table.length);
        assertEquals(CP.ch('Z'), table[CP.ch('A') & 0xFF]);
        assertEquals((byte) 0x00, table[0]);
        assertEquals(CP.ch('B'), table[CP.ch('B') & 0xFF], "対応のない文字はそのまま");
    }
}
