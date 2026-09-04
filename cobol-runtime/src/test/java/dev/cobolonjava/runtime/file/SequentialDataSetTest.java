package dev.cobolonjava.runtime.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 順編成のデータセット (要件 FR-100, FR-102, FR-110)。
 *
 * <p>中身は生バイトである。どこでレコードが切れるかは属性が決める。バイト列の中には
 * 書かれていないので、<b>属性が違えば同じバイト列が違うレコードに切れる</b>。
 */
@Tag("V1")
class SequentialDataSetTest {

    @TempDir
    Path directory;

    private Path write(String name, String text) throws IOException {
        Path path = directory.resolve(name);
        Files.write(path, CodePages.DEFAULT.encode(text));
        return path;
    }

    private static byte[] area(int length) {
        byte[] out = new byte[length];
        java.util.Arrays.fill(out, CodePages.DEFAULT.space());
        return out;
    }

    private static String decode(byte[] bytes) {
        return CodePages.DEFAULT.decode(bytes);
    }

    @Test
    @DisplayName("固定長はレコード長ずつ切る (FR-110)")
    void fixedRecordsAreCutByLength() throws IOException {
        Path path = write("F.DAT", "aaabbbccc");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        assertEquals(FileStatus.OK, file.open(OpenMode.INPUT));
        byte[] record = area(3);
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals("aaa", decode(record));
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals("bbb", decode(record));
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals("ccc", decode(record));
        assertEquals(FileStatus.AT_END, file.read(record));
        assertEquals(FileStatus.OK, file.close());
    }

    @Test
    @DisplayName("属性が違えば同じバイト列が違うレコードに切れる (FR-110)")
    void theAttributesDecideWhereRecordsEnd() throws IOException {
        Path path = write("F.DAT", "aaabbbccc");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.FIXED, 9, CodePages.DEFAULT));

        file.open(OpenMode.INPUT);
        byte[] record = area(9);
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals("aaabbbccc", decode(record));
        assertEquals(FileStatus.AT_END, file.read(record));
    }

    @Test
    @DisplayName("受取領域が長ければ空白で埋める (FR-102)")
    void aLongerAreaIsPadded() throws IOException {
        Path path = write("F.DAT", "abc");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        file.open(OpenMode.INPUT);
        byte[] record = area(5);
        // 長さが合わないので 04 が立つ
        assertEquals(FileStatus.LENGTH_MISMATCH, file.read(record));
        assertEquals("abc  ", decode(record));
    }

    @Test
    @DisplayName("行順は改行までが 1 レコードである (FR-100)")
    void lineRecordsEndAtTheNewline() throws IOException {
        // EBCDIC の改行は 0x15 である。コードページで書けばそうなる
        Path path = write("L.DAT", "one\ntwo\nthree\n");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.LINE, 10, CodePages.DEFAULT));

        file.open(OpenMode.INPUT);
        byte[] record = area(5);
        assertEquals(FileStatus.LENGTH_MISMATCH, file.read(record));
        assertEquals("one  ", decode(record));
        file.read(record);
        assertEquals("two  ", decode(record));
        file.read(record);
        assertEquals("three", decode(record));
        assertEquals(FileStatus.AT_END, file.read(record));
    }

    @Test
    @DisplayName("最後の改行がなくても 1 レコードになる (FR-100)")
    void aMissingFinalNewlineStillEndsARecord() throws IOException {
        Path path = write("L.DAT", "one\ntwo");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.LINE, 10, CodePages.DEFAULT));

        file.open(OpenMode.INPUT);
        byte[] record = area(3);
        file.read(record);
        assertEquals("one", decode(record));
        file.read(record);
        assertEquals("two", decode(record));
        assertEquals(FileStatus.AT_END, file.read(record));
    }

    @Test
    @DisplayName("行順で書くと末尾の空白を落とす (FR-100)")
    void writingLinesTrimsTrailingSpaces() throws IOException {
        Path path = directory.resolve("OUT.DAT");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.LINE, 10, CodePages.DEFAULT));

        file.open(OpenMode.OUTPUT);
        file.write(CodePages.DEFAULT.encode("ab   "));
        file.write(CodePages.DEFAULT.encode("cde  "));
        file.close();

        assertEquals("ab\ncde\n", decode(Files.readAllBytes(path)));
    }

    @Test
    @DisplayName("固定長で書くとレコード長まで空白で埋める (FR-110)")
    void writingFixedRecordsPadsToTheLength() throws IOException {
        Path path = directory.resolve("OUT.DAT");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.FIXED, 5, CodePages.DEFAULT));

        file.open(OpenMode.OUTPUT);
        file.write(CodePages.DEFAULT.encode("ab"));
        file.write(CodePages.DEFAULT.encode("cde"));
        file.close();

        assertEquals("ab   cde  ", decode(Files.readAllBytes(path)));
    }

    @Test
    @DisplayName("可変長は RDW で長さを持つ (FR-110)")
    void variableRecordsCarryTheirLength() throws IOException {
        Path path = directory.resolve("V.DAT");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.VARIABLE, 10, CodePages.DEFAULT));

        file.open(OpenMode.OUTPUT);
        file.write(CodePages.DEFAULT.encode("ab"));
        file.write(CodePages.DEFAULT.encode("cdef"));
        file.close();

        byte[] bytes = Files.readAllBytes(path);
        // RDW は 4 バイト。長さは RDW を含む
        assertArrayEquals(new byte[] {0, 6, 0, 0}, java.util.Arrays.copyOfRange(bytes, 0, 4));
        assertArrayEquals(new byte[] {0, 8, 0, 0}, java.util.Arrays.copyOfRange(bytes, 6, 10));

        SequentialDataSet back = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.VARIABLE, 10, CodePages.DEFAULT));
        back.open(OpenMode.INPUT);
        byte[] record = area(4);
        back.read(record);
        assertEquals("ab  ", decode(record));
        back.read(record);
        assertEquals("cdef", decode(record));
        assertEquals(FileStatus.AT_END, back.read(record));
    }

    @Test
    @DisplayName("OUTPUT で開くと元の中身は消える (FR-102)")
    void openOutputTruncates() throws IOException {
        Path path = write("F.DAT", "oldold");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        file.open(OpenMode.OUTPUT);
        file.write(CodePages.DEFAULT.encode("new"));
        file.close();

        assertEquals("new", decode(Files.readAllBytes(path)));
    }

    @Test
    @DisplayName("EXTEND で開くと末尾から書き足す (FR-102)")
    void openExtendAppends() throws IOException {
        Path path = write("F.DAT", "aaa");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        file.open(OpenMode.EXTEND);
        file.write(CodePages.DEFAULT.encode("bbb"));
        file.close();

        assertEquals("aaabbb", decode(Files.readAllBytes(path)));
    }

    @Test
    @DisplayName("ないファイルを INPUT で開くと 35 になる (FR-103)")
    void openingAMissingFileForInputFails() {
        SequentialDataSet file = SequentialDataSet.at(directory.resolve("NONE.DAT"));

        assertEquals(FileStatus.NOT_FOUND, file.open(OpenMode.INPUT));
        assertFalse(file.isOpen());
    }

    @Test
    @DisplayName("二重に開けば 41 になる (FR-103)")
    void openingTwiceFails() throws IOException {
        SequentialDataSet file = SequentialDataSet.at(write("F.DAT", "abc"));

        assertEquals(FileStatus.OK, file.open(OpenMode.INPUT));
        assertEquals(FileStatus.ALREADY_OPEN, file.open(OpenMode.INPUT));
    }

    @Test
    @DisplayName("開いていなければ 42 になる (FR-103)")
    void usingAClosedFileFails() {
        SequentialDataSet file = SequentialDataSet.at(directory.resolve("F.DAT"));

        assertEquals(FileStatus.NOT_OPEN, file.read(area(3)));
        assertEquals(FileStatus.NOT_OPEN, file.write(area(3)));
        assertEquals(FileStatus.NOT_OPEN, file.close());
    }

    @Test
    @DisplayName("開き方に合わない操作は 47 か 48 になる (FR-103)")
    void theOpenModeDecidesWhatIsAllowed() throws IOException {
        SequentialDataSet input = SequentialDataSet.at(write("F.DAT", "abc"));
        input.open(OpenMode.INPUT);
        assertEquals(FileStatus.WRITE_NOT_ALLOWED, input.write(area(3)));

        SequentialDataSet output = SequentialDataSet.at(directory.resolve("OUT.DAT"));
        output.open(OpenMode.OUTPUT);
        assertEquals(FileStatus.READ_NOT_ALLOWED, output.read(area(3)));
    }

    @Test
    @DisplayName("終わりまで読んだあとにまた読めば 46 になる (FR-103)")
    void readingPastTheEndTwiceFails() throws IOException {
        SequentialDataSet file = new SequentialDataSet(write("F.DAT", "abc"),
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        file.open(OpenMode.INPUT);
        byte[] record = area(3);
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals(FileStatus.AT_END, file.read(record));
        // 位置が定まっていない
        assertEquals(FileStatus.NOT_READABLE, file.read(record));
    }

    @Test
    @DisplayName("サイドカーがあれば属性はそこから来る (FR-110)")
    void theSidecarSuppliesTheAttributes() throws IOException {
        Path path = write("F.DAT", "aabbcc");
        Files.writeString(DataSetAttributes.sidecarOf(path), "recfm=F\nlrecl=2\n");

        SequentialDataSet file = SequentialDataSet.at(path);
        assertEquals(RecordFormat.FIXED, file.attributes().format());
        assertEquals(2, file.attributes().recordLength());

        file.open(OpenMode.INPUT);
        byte[] record = area(2);
        file.read(record);
        assertEquals("aa", decode(record));
    }

    @Test
    @DisplayName("サイドカーがなければ既定を使う (FR-110)")
    void withoutASidecarTheDefaultIsUsed() throws IOException {
        SequentialDataSet file = SequentialDataSet.at(write("F.DAT", "abc"));

        assertEquals(RecordFormat.FIXED, file.attributes().format());
        assertEquals(80, file.attributes().recordLength());
        assertEquals("IBM-1047", file.attributes().codePage().name());
    }

    @Test
    @DisplayName("書いたら属性もサイドカーへ残す (FR-110)")
    void writingLeavesTheAttributesBehind() throws IOException {
        Path path = directory.resolve("OUT.DAT");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.LINE, 20, CodePages.DEFAULT));

        file.open(OpenMode.OUTPUT);
        file.write(CodePages.DEFAULT.encode("x"));
        file.close();

        String sidecar = Files.readString(DataSetAttributes.sidecarOf(path));
        assertTrue(sidecar.contains("recfm=LINE"), sidecar);
        assertTrue(sidecar.contains("lrecl=20"), sidecar);
        assertTrue(sidecar.contains("codepage=IBM-1047"), sidecar);
    }

    @Test
    @DisplayName("書いたものを読み返せる (FR-102)")
    void whatIsWrittenCanBeReadBack() throws IOException {
        Path path = directory.resolve("ROUND.DAT");
        SequentialDataSet out = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.FIXED, 4, CodePages.DEFAULT));
        out.open(OpenMode.OUTPUT);
        out.write(CodePages.DEFAULT.encode("ab"));
        out.write(CodePages.DEFAULT.encode("cd"));
        out.close();

        // サイドカーから属性が復元される
        SequentialDataSet back = SequentialDataSet.at(path);
        assertEquals(4, back.attributes().recordLength());
        back.open(OpenMode.INPUT);
        byte[] record = area(4);
        back.read(record);
        assertEquals("ab  ", decode(record));
        back.read(record);
        assertEquals("cd  ", decode(record));
    }

    // ---- 第 2 段: 可変長・REWRITE・OPEN I-O ----

    @Test
    @DisplayName("REWRITE は直前に読んだレコードを書き換える (FR-102)")
    void rewriteReplacesTheRecordJustRead() throws IOException {
        Path path = write("F.DAT", "aaabbbccc");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        assertEquals(FileStatus.OK, file.open(OpenMode.IO));
        byte[] record = area(3);
        file.read(record);
        file.read(record);
        assertEquals("bbb", decode(record));
        assertEquals(FileStatus.OK, file.rewrite(CodePages.DEFAULT.encode("XYZ")));
        file.close();

        assertEquals("aaaXYZccc", decode(Files.readAllBytes(path)));
    }

    @Test
    @DisplayName("読まずに REWRITE すれば 43 になる (FR-103)")
    void rewritingWithoutReadingFails() throws IOException {
        SequentialDataSet file = new SequentialDataSet(write("F.DAT", "aaa"),
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        file.open(OpenMode.IO);
        assertEquals(FileStatus.NO_CURRENT_RECORD, file.rewrite(CodePages.DEFAULT.encode("bbb")));
    }

    @Test
    @DisplayName("REWRITE を続けて 2 回書けば 2 回目は 43 になる (FR-103)")
    void rewritingTwiceNeedsAnotherRead() throws IOException {
        SequentialDataSet file = new SequentialDataSet(write("F.DAT", "aaa"),
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        file.open(OpenMode.IO);
        file.read(area(3));
        assertEquals(FileStatus.OK, file.rewrite(CodePages.DEFAULT.encode("bbb")));
        assertEquals(FileStatus.NO_CURRENT_RECORD, file.rewrite(CodePages.DEFAULT.encode("ccc")));
    }

    @Test
    @DisplayName("I-O 以外で開いた REWRITE は 49 になる (FR-103)")
    void rewritingOutsideInputOutputFails() throws IOException {
        SequentialDataSet file = new SequentialDataSet(write("F.DAT", "aaa"),
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        file.open(OpenMode.INPUT);
        file.read(area(3));
        assertEquals(FileStatus.REWRITE_NOT_ALLOWED,
                file.rewrite(CodePages.DEFAULT.encode("bbb")));
    }

    @Test
    @DisplayName("固定長で長さの違う REWRITE は 44 になる (FR-103)")
    void rewritingAFixedRecordCannotChangeItsLength() throws IOException {
        SequentialDataSet file = new SequentialDataSet(write("F.DAT", "aaa"),
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        file.open(OpenMode.IO);
        file.read(area(3));
        assertEquals(FileStatus.REWRITE_LENGTH, file.rewrite(CodePages.DEFAULT.encode("bbbb")));
    }

    @Test
    @DisplayName("可変長の REWRITE は長さを変えられる (FR-102, FR-106)")
    void rewritingAVariableRecordMayChangeItsLength() {
        Path path = directory.resolve("V.DAT");
        DataSetAttributes attributes =
                new DataSetAttributes(RecordFormat.VARIABLE, 10, CodePages.DEFAULT);
        SequentialDataSet out = new SequentialDataSet(path, attributes);
        out.open(OpenMode.OUTPUT);
        out.write(CodePages.DEFAULT.encode("aaaa"));
        out.write(CodePages.DEFAULT.encode("bb"));
        out.close();

        SequentialDataSet file = new SequentialDataSet(path, attributes);
        file.open(OpenMode.IO);
        file.read(area(10));
        assertEquals(FileStatus.OK, file.rewrite(CodePages.DEFAULT.encode("x")));
        file.close();

        SequentialDataSet back = new SequentialDataSet(path, attributes);
        back.open(OpenMode.INPUT);
        byte[] record = area(10);
        back.read(record);
        assertEquals(1, back.lastLength());
        back.read(record);
        assertEquals(2, back.lastLength());
    }

    @Test
    @DisplayName("可変長は受取領域の余りに触らない (FR-106)")
    void variableRecordsLeaveTheRestOfTheAreaAlone() {
        Path path = directory.resolve("V.DAT");
        DataSetAttributes attributes =
                new DataSetAttributes(RecordFormat.VARIABLE, 8, CodePages.DEFAULT);
        SequentialDataSet out = new SequentialDataSet(path, attributes);
        out.open(OpenMode.OUTPUT);
        out.write(CodePages.DEFAULT.encode("ab"));
        out.close();

        byte[] record = CodePages.DEFAULT.encode("ZZZZZZZZ");
        SequentialDataSet file = new SequentialDataSet(path, attributes);
        file.open(OpenMode.INPUT);
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals(2, file.lastLength());
        // 読んだ 2 バイトだけが変わり、残りは前のままである
        assertEquals("abZZZZZZ", decode(record));
    }

    @Test
    @DisplayName("受取領域より長い可変長レコードは 04 になる (FR-103)")
    void aVariableRecordLongerThanTheAreaIsTruncated() {
        Path path = directory.resolve("V.DAT");
        SequentialDataSet out = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.VARIABLE, 8, CodePages.DEFAULT));
        out.open(OpenMode.OUTPUT);
        out.write(CodePages.DEFAULT.encode("abcdef"));
        out.close();

        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.VARIABLE, 8, CodePages.DEFAULT));
        file.open(OpenMode.INPUT);
        byte[] record = area(3);
        assertEquals(FileStatus.LENGTH_MISMATCH, file.read(record));
        assertEquals("abc", decode(record));
    }

    @Test
    @DisplayName("OPTIONAL ならないファイルを開いて 05 になる (FR-103)")
    void anOptionalFileMayBeMissing() {
        SequentialDataSet file = SequentialDataSet.at(directory.resolve("NONE.DAT"));

        assertEquals(FileStatus.OPTIONAL_CREATED, file.open(OpenMode.INPUT, true));
        assertTrue(file.isOpen());
        assertEquals(FileStatus.AT_END, file.read(area(80)));
    }

    @Test
    @DisplayName("ないファイルを I-O と EXTEND で開いても 35 になる (FR-103)")
    void missingFilesFailForInputOutputAndExtend() {
        assertEquals(FileStatus.NOT_FOUND,
                SequentialDataSet.at(directory.resolve("A.DAT")).open(OpenMode.IO));
        assertEquals(FileStatus.NOT_FOUND,
                SequentialDataSet.at(directory.resolve("B.DAT")).open(OpenMode.EXTEND));
        // OUTPUT だけは作ってよい。中身を消して書き直す開き方だからである
        assertEquals(FileStatus.OK,
                SequentialDataSet.at(directory.resolve("C.DAT")).open(OpenMode.OUTPUT));
    }

    @Test
    @DisplayName("I-O で開けば読みも書きもできる (FR-102)")
    void inputOutputReadsAndWrites() throws IOException {
        Path path = write("F.DAT", "aaabbb");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        file.open(OpenMode.IO);
        byte[] record = area(3);
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals(FileStatus.AT_END, file.read(record));
        file.close();

        assertEquals("aaabbb", decode(Files.readAllBytes(path)));
    }
}
