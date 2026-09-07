package dev.cobolonjava.runtime.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
