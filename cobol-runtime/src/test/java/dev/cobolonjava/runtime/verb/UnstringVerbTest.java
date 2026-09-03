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
class UnstringVerbTest {

    private static final CodePage CP = CodePages.IBM_1047;

    private static byte[] b(String s) {
        return CP.encode(s);
    }

    private static List<String> texts(UnstringVerb.Result r) {
        return r.fields().stream().map(CP::decode).toList();
    }

    private static UnstringVerb.Result run(String source, List<UnstringVerb.Delimiter> delimiters,
                                           int... lengths) {
        List<UnstringVerb.Field> fields = java.util.Arrays.stream(lengths)
                .mapToObj(UnstringVerb.Field::of).toList();
        return UnstringVerb.unstring(b(source), 1, delimiters, fields, CP);
    }

    @Test
    @DisplayName("区切り文字で分割し、受取項目へ順に転記する (FR-065)")
    void splitsByDelimiter() {
        UnstringVerb.Result r = run("A,B,C", List.of(UnstringVerb.Delimiter.of(b(","))), 3, 3, 3);
        assertEquals(List.of("A  ", "B  ", "C  "), texts(r));
        assertEquals(List.of(1, 1, 1), r.counts());
        assertEquals(3, r.tallying());
        assertEquals(6, r.pointer());
        assertFalse(r.overflow());
    }

    @Test
    @DisplayName("受取項目への転記は英数字転記の規則に従う。左詰めで空白で埋める (FR-060, FR-065)")
    void receivingFieldsFollowAlphanumericMoveRules() {
        UnstringVerb.Result r = run("ABCDE,X", List.of(UnstringVerb.Delimiter.of(b(","))), 3, 3);
        assertEquals(List.of("ABC", "X  "), texts(r), "あふれた右側は切り捨てられる");
        assertEquals(List.of(5, 1), r.counts(), "COUNT IN は切り捨て前の文字数を受け取る");
    }

    @Test
    @DisplayName("ALL は連続する区切り文字を 1 個として扱う (FR-065)")
    void allTreatsConsecutiveDelimitersAsOne() {
        UnstringVerb.Result withAll = run("A,,B", List.of(UnstringVerb.Delimiter.all(b(","))), 3, 3);
        assertEquals(List.of("A  ", "B  "), texts(withAll));
        assertFalse(withAll.overflow());

        // ALL がなければ 2 個目の区切りで空の項目が生じる
        UnstringVerb.Result withoutAll = run("A,,B", List.of(UnstringVerb.Delimiter.of(b(","))), 3, 3);
        assertEquals(List.of("A  ", "   "), texts(withoutAll));
        assertTrue(withoutAll.overflow(), "B が残るのでオーバーフローになる");
    }

    @Test
    @DisplayName("複数の区切り文字は最も手前に現れたものが使われる (FR-065)")
    void earliestDelimiterWins() {
        UnstringVerb.Result r = run("A;B,C",
                List.of(UnstringVerb.Delimiter.of(b(",")), UnstringVerb.Delimiter.of(b(";"))), 3, 3, 3);
        assertEquals(List.of("A  ", "B  ", "C  "), texts(r));
        assertEquals(List.of(";", ",", ""), r.delimiters().stream().map(CP::decode).toList(),
                "DELIMITER IN は実際に見つかった区切り文字を受け取る");
    }

    @Test
    @DisplayName("受取項目が足りなければオーバーフローになる (FR-065)")
    void tooFewReceivingFieldsOverflows() {
        UnstringVerb.Result r = run("A,B,C", List.of(UnstringVerb.Delimiter.of(b(","))), 3, 3);
        assertEquals(List.of("A  ", "B  "), texts(r));
        assertTrue(r.overflow());
        assertEquals(5, r.pointer(), "次に走査する位置が残る");
    }

    @Test
    @DisplayName("DELIMITED BY がなければ各受取項目がその長さぶんを順に取る (FR-065)")
    void withoutDelimitersEachFieldTakesItsOwnLength() {
        UnstringVerb.Result r = run("ABCDEF", List.of(), 2, 2, 2);
        assertEquals(List.of("AB", "CD", "EF"), texts(r));
        assertFalse(r.overflow());
    }

    @Test
    @DisplayName("ポインタが範囲外なら何も転記せずオーバーフローになる (FR-065)")
    void pointerOutOfRangeTransfersNothing() {
        for (int pointer : new int[] {0, 6}) {
            UnstringVerb.Result r = UnstringVerb.unstring(b("ABCDE"), pointer,
                    List.of(UnstringVerb.Delimiter.of(b(","))),
                    List.of(UnstringVerb.Field.of(3)), CP);
            assertTrue(r.overflow(), "pointer=" + pointer);
            assertEquals(0, r.tallying(), "pointer=" + pointer);
            assertTrue(r.fields().isEmpty(), "pointer=" + pointer);
        }
    }

    @Test
    @DisplayName("JUSTIFIED RIGHT の受取項目は右詰めになる (FR-060)")
    void justifiedRight() {
        UnstringVerb.Result r = UnstringVerb.unstring(b("A,B"),
                1, List.of(UnstringVerb.Delimiter.of(b(","))),
                List.of(new UnstringVerb.Field(3, true), new UnstringVerb.Field(3, true)), CP);
        assertEquals(List.of("  A", "  B"), texts(r));
    }
}
