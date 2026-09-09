package dev.cobolonjava.runtime.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 区分データセットのディレクトリ (要件 FR-113, FR-053、暫定判断 P-056 の解消)。
 *
 * <p>ここで確かめたいのは<b>メンバの並び</b>と<b>どこまでがメンバか</b>の 2 つである。
 * 並びはライブラリを丸ごと扱う操作の出力そのものを決め、メンバの見分けは覚え書きの
 * サイドカーがメンバに紛れないことを決める。
 *
 * <p>項目の大きさ (暫定判断 P-059 の解消) も同じ場所の話である。ホストのディレクトリは
 * あらかじめ取ったブロックしかなく、統計の有無で 1 ブロックに入る数が変わる。
 */
@Tag("V1")
class PartitionedDataSetTest {

    @TempDir
    Path directory;

    private void member(String name) {
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(name), new byte[0]);
            Files.writeString(Path.of(directory.resolve(name) + ".meta"),
                    "recfm=F\nlrecl=80\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> members() {
        return PartitionedDataSet.members(directory, CodePages.DEFAULT);
    }

    // ---- 名前の決まり ----

    @Test
    @DisplayName("メンバ名は 8 文字まで (FR-113)")
    void aMemberNameIsEightCharactersAtMost() {
        assertTrue(PartitionedDataSet.validName("PAYROLL"));
        assertTrue(PartitionedDataSet.validName("ABCDEFGH"));
        assertFalse(PartitionedDataSet.validName("ABCDEFGHI"));
        assertFalse(PartitionedDataSet.validName(""));
    }

    @Test
    @DisplayName("メンバ名は数字で始まらない (FR-113)")
    void aMemberNameDoesNotStartWithADigit() {
        assertTrue(PartitionedDataSet.validName("A1"));
        assertFalse(PartitionedDataSet.validName("1A"));
    }

    @Test
    @DisplayName("英数字と @ # $ だけが通る (FR-113)")
    void onlyNationalCharactersJoinTheLetters() {
        assertTrue(PartitionedDataSet.validName("A@B#C$D"));
        assertFalse(PartitionedDataSet.validName("A-B"));
        assertFalse(PartitionedDataSet.validName("a"));
        assertFalse(PartitionedDataSet.validName("A.B"));
    }

    /**
     * 覚え書きの紛れが名前の決まりで消える。
     *
     * <p>{@code .} はメンバ名に入らないので、{@code PAYROLL.meta} はメンバになりえない。
     * 名前の決まりを入れて初めて<b>ディレクトリが言える</b>ようになる。
     */
    @Test
    @DisplayName("サイドカーはメンバではない (FR-113)")
    void aSidecarIsNotAMember() {
        member("PAYROLL");

        assertEquals(List.of("PAYROLL"), members());
    }

    // ---- 並び ----

    /**
     * EBCDIC では英字が数字より前である。
     *
     * <p>ここが Java の {@code String} の順と逆になる。ライブラリを丸ごと写したときに
     * <b>出てくるバイト列が実機と変わる</b>ので、細かい違いでは済まない。
     */
    @Test
    @DisplayName("並びはコードページの順である。英字が数字より前に来る (FR-053, FR-113)")
    void membersComeOutInCodePageOrder() {
        member("PAY1");
        member("PAYA");

        assertEquals(List.of("PAYA", "PAY1"), members());
        // Java の順なら逆である。並べ直さずに済ませてはならない
        assertEquals(List.of("PAY1", "PAYA"), members().stream().sorted().toList());
    }

    @Test
    @DisplayName("ASCII で並べれば逆になる (FR-053)")
    void theSameNamesGoTheOtherWayInAscii() {
        member("PAY1");
        member("PAYA");

        assertEquals(List.of("PAY1", "PAYA"),
                PartitionedDataSet.members(directory, CodePages.ASCII));
    }

    @Test
    @DisplayName("短い名前が先に来る。ホストは空白で埋めて持っている (FR-113)")
    void aShorterNameComesFirst() {
        member("PAYA");
        member("PAY");

        assertEquals(List.of("PAY", "PAYA"), members());
    }

    @Test
    @DisplayName("区分データセットでなければメンバはない (FR-113)")
    void aSequentialDataSetHasNoMembers() {
        assertEquals(List.of(),
                PartitionedDataSet.members(directory.resolve("NOSUCH"), CodePages.DEFAULT));
    }

    // ---- 別名 (暫定判断 P-059 の解消) ----

    @Test
    @DisplayName("別名は一覧にメンバとして並ぶ (FR-113)")
    void anAliasIsListedLikeAMember() {
        member("PAYCALC");
        PartitionedDataSet.link(directory, "PAYOLD", "PAYCALC");

        assertEquals(List.of("PAYCALC", "PAYOLD"), members());
        assertTrue(PartitionedDataSet.alias(directory, "PAYOLD"));
        assertFalse(PartitionedDataSet.alias(directory, "PAYCALC"));
        assertEquals("PAYCALC", PartitionedDataSet.aliasOf(directory, "PAYOLD"));
        assertNull(PartitionedDataSet.aliasOf(directory, "PAYCALC"));
    }

    /**
     * 指す先を消しても項目は残る。
     *
     * <p>ホストでも同じで、ディレクトリの項目は名前を消したことを知らない。開こうとして
     * 初めて失敗する。
     */
    @Test
    @DisplayName("切れた別名も項目である (FR-113)")
    void aDanglingAliasIsStillAnEntry() {
        member("PAYCALC");
        PartitionedDataSet.link(directory, "PAYOLD", "PAYCALC");
        PartitionedDataSet.unlink(directory, "PAYCALC");

        assertEquals(List.of("PAYOLD"), members());
        assertTrue(PartitionedDataSet.alias(directory, "PAYOLD"));
    }

    @Test
    @DisplayName("そのメンバを指す別名を数え上げる (FR-113)")
    void theAliasesOfAMemberAreFound() {
        member("PAYCALC");
        member("TAXES");
        PartitionedDataSet.link(directory, "PAYOLD", "PAYCALC");
        PartitionedDataSet.link(directory, "PAYA", "PAYCALC");

        assertEquals(List.of("PAYA", "PAYOLD"),
                PartitionedDataSet.aliasesOf(directory, "PAYCALC", CodePages.DEFAULT));
        assertEquals(List.of(),
                PartitionedDataSet.aliasesOf(directory, "TAXES", CodePages.DEFAULT));
    }

    @Test
    @DisplayName("項目を消せば覚え書きも消える (FR-110, FR-113)")
    void unlinkingTakesTheSidecarsAway() {
        member("PAYCALC");

        assertTrue(PartitionedDataSet.unlink(directory, "PAYCALC"));

        assertFalse(Files.exists(Path.of(directory.resolve("PAYCALC") + ".meta")),
                "次に同じ名前で作った人のものになってしまう");
        assertFalse(PartitionedDataSet.unlink(directory, "PAYCALC"));
    }

    // ---- ディレクトリの大きさ (暫定判断 P-059 の解消) ----

    @Test
    @DisplayName("統計を持たない項目は 12 バイト (FR-113)")
    void anEntryWithoutStatisticsIsTwelveBytes() {
        member("PAYCALC");

        assertEquals(12, PartitionedDataSet.entrySize(directory, "PAYCALC"));
    }

    /**
     * 統計が付けば 30 バイト増える。
     *
     * <p>1 ブロック (254 バイト使える) に入る数が 21 と 6 で変わる。だから<b>統計を
     * 持たずにブロックの数だけを決められない</b>。決めれば、統計を入れた日に止まる場所が動く。
     */
    @Test
    @DisplayName("統計が付いた項目は 42 バイト (FR-113)")
    void statisticsMakeTheEntryLonger() {
        member("PAYCALC");
        new MemberStatistics(1, 0, null, null, 10, 10, 0, "DEV").write(
                directory.resolve("PAYCALC"));

        assertEquals(42, PartitionedDataSet.entrySize(directory, "PAYCALC"));
    }

    @Test
    @DisplayName("1 ブロックには 21 項目まで。項目はブロックをまたがない (FR-113)")
    void entriesDoNotSpanBlocks() {
        List<String> names = new java.util.ArrayList<>();
        for (int i = 0; i < 22; i++) {
            names.add("M" + (char) ('A' + i));
        }

        assertEquals(1, PartitionedDataSet.blocksNeeded(directory, names.subList(0, 21)));
        assertEquals(2, PartitionedDataSet.blocksNeeded(directory, names));
    }

    @Test
    @DisplayName("大きさを知らないライブラリはいくつでも入る (FR-113, FR-141)")
    void aLibraryWithoutASizeTakesAnything() {
        member("PAYCALC");

        assertTrue(PartitionedDataSet.roomFor(directory, CodePages.DEFAULT, 0, "MORE"));
    }

    @Test
    @DisplayName("すでにある名前は項目を増やさない (FR-113, FR-141)")
    void anExistingNameNeedsNoNewEntry() {
        for (int i = 0; i < 21; i++) {
            member("M" + (char) ('A' + i));
        }

        assertFalse(PartitionedDataSet.roomFor(directory, CodePages.DEFAULT, 1, "MORE"));
        assertTrue(PartitionedDataSet.roomFor(directory, CodePages.DEFAULT, 1, "MA"));
    }
}
