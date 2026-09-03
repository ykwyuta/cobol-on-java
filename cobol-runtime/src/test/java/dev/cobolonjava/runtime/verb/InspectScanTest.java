package dev.cobolonjava.runtime.verb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.cobolonjava.runtime.codepage.CodePages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class InspectScanTest {

    private static byte[] b(String text) {
        return CodePages.DEFAULT.encode(text);
    }

    private static String s(byte[] bytes) {
        return CodePages.DEFAULT.decode(bytes);
    }

    @Test
    @DisplayName("句は位置ごとに書かれた順で試される (FR-065)")
    void clausesAreTriedInOrderAtEachPosition() {
        // 独立に適用すると 2 つ目も 1 になってしまう
        int[] counts = InspectScan.tally(b("AB"),
                InspectScan.Clause.all(b("AB"), Region.whole()),
                InspectScan.Clause.all(b("B"), Region.whole()));
        assertArrayEquals(new int[] {1, 0}, counts);
    }

    @Test
    @DisplayName("先に書いた句が当たらなければ次の句が試される (FR-065)")
    void alaterClauseGetsItsTurn() {
        int[] counts = InspectScan.tally(b("XB"),
                InspectScan.Clause.all(b("AB"), Region.whole()),
                InspectScan.Clause.all(b("B"), Region.whole()));
        assertArrayEquals(new int[] {0, 1}, counts);
    }

    @Test
    @DisplayName("一致は重ならない (FR-065)")
    void matchesDoNotOverlap() {
        assertArrayEquals(new int[] {1}, InspectScan.tally(b("AAA"),
                InspectScan.Clause.all(b("AA"), Region.whole())));
    }

    @Test
    @DisplayName("CHARACTERS は範囲内のどの 1 バイトにも当たる (FR-065)")
    void charactersMatchesEveryByte() {
        assertArrayEquals(new int[] {5}, InspectScan.tally(b("ABCDE"),
                InspectScan.Clause.characters(Region.whole())));
    }

    @Test
    @DisplayName("CHARACTERS より前の句が優先する (FR-065)")
    void anEarlierClauseWinsOverCharacters() {
        // AB が 1 回、残り 3 バイトが CHARACTERS
        int[] counts = InspectScan.tally(b("ABCDE"),
                InspectScan.Clause.all(b("AB"), Region.whole()),
                InspectScan.Clause.characters(Region.whole()));
        assertArrayEquals(new int[] {1, 3}, counts);
    }

    @Test
    @DisplayName("LEADING は連なりが切れたら終わる (FR-065)")
    void leadingStopsWhenTheRunEnds() {
        assertArrayEquals(new int[] {2}, InspectScan.tally(b("AABAA"),
                InspectScan.Clause.leading(b("A"), Region.whole())));
    }

    @Test
    @DisplayName("ほかの句がその位置を取っても連なりは切れる (FR-065)")
    void anotherClauseAlsoBreaksTheRun() {
        // 先頭の A は 1 つ目の句が取る。2 つ目の LEADING はそこで切れる
        int[] counts = InspectScan.tally(b("AAA"),
                InspectScan.Clause.all(b("AA"), Region.whole()),
                InspectScan.Clause.leading(b("A"), Region.whole()));
        assertArrayEquals(new int[] {1, 0}, counts);
    }

    @Test
    @DisplayName("AFTER の区切りが見つからなければ検査しない (FR-065)")
    void anAbsentAfterDelimiterInspectsNothing() {
        // 「全体を検査する」のではない
        assertArrayEquals(new int[] {0}, InspectScan.tally(b("AAA"),
                InspectScan.Clause.all(b("A"), Region.after(b("Z")))));
    }

    @Test
    @DisplayName("範囲は句ごとに別々である (FR-065)")
    void eachClauseHasItsOwnRegion() {
        int[] counts = InspectScan.tally(b("A-A-A"),
                InspectScan.Clause.all(b("A"), Region.before(b("-"))),
                InspectScan.Clause.all(b("A"), Region.after(b("-"))));
        assertArrayEquals(new int[] {1, 2}, counts);
    }

    @Test
    @DisplayName("REPLACING は同じ走査で置き換える (FR-065)")
    void replacingUsesTheSameScan() {
        assertEquals("XYCDE", s(InspectScan.replace(b("ABCDE"),
                InspectScan.Clause.replaceAll(b("AB"), b("XY"), Region.whole()))));
    }

    @Test
    @DisplayName("置き換えた並びは照合し直さない (FR-065)")
    void whatWasWrittenIsNotMatchedAgain() {
        // 位置 0 の A は 2 つ目の句が Z にする。位置 1 の B は 1 つ目の句が A にする。
        // 書き込んだ A をもう一度照合していれば、それも Z になって "ZZ" になる
        assertEquals("ZA", s(InspectScan.replace(b("AB"),
                InspectScan.Clause.replaceAll(b("B"), b("A"), Region.whole()),
                InspectScan.Clause.replaceAll(b("A"), b("Z"), Region.whole()))));
    }

    @Test
    @DisplayName("REPLACING CHARACTERS は 1 バイトずつ置き換える (FR-065)")
    void replacingCharactersWritesOneByteAtATime() {
        assertEquals("***DE", s(InspectScan.replace(b("ABCDE"),
                InspectScan.Clause.replaceCharacters(b("*"), Region.before(b("D"))))));
    }

    @Test
    @DisplayName("REPLACING FIRST は最初の一致だけを置き換える (FR-065)")
    void replacingFirstWritesOnce() {
        assertEquals("XBAB", s(InspectScan.replace(b("ABAB"),
                InspectScan.Clause.replaceFirst(b("A"), b("X"), Region.whole()))));
    }

    @Test
    @DisplayName("REPLACING LEADING は先頭の連なりだけを置き換える (FR-065)")
    void replacingLeadingWritesTheRunOnly() {
        assertEquals("XXBAA", s(InspectScan.replace(b("AABAA"),
                InspectScan.Clause.replaceLeading(b("A"), b("X"), Region.whole()))));
    }

    @Test
    @DisplayName("数える句と置き換える句を混ぜても走査は 1 度である (FR-065)")
    void countingAndReplacingShareOneScan() {
        byte[] data = b("AABB");
        InspectScan.Clause[] clauses = {
            InspectScan.Clause.replaceAll(b("A"), b("X"), Region.whole()),
            InspectScan.Clause.all(b("B"), Region.whole())
        };
        assertEquals("XXBB", s(InspectScan.replace(data, clauses)));
        assertArrayEquals(new int[] {2, 2}, InspectScan.tally(data, clauses));
    }
}
