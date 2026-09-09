package dev.cobolonjava.runtime.file;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.abend.AbendCode;
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
    @DisplayName("開いていないファイルへの操作は、文ごとに違う番号になる (FR-103)")
    void usingAClosedFileFails() {
        // 42 は<b>CLOSE のための番号</b>である。READ は「INPUT でも I-O でもない
        // ファイルへの READ」なので 47、WRITE は同じ理屈で 48 になる
        // (85 規格 VII-5, 1.3.5(4)F・G)。CCVS85 の SQ147A / SQ151A がここを見ている
        SequentialDataSet file = SequentialDataSet.at(directory.resolve("F.DAT"));

        assertEquals(FileStatus.READ_NOT_ALLOWED, file.read(area(3)));
        assertEquals(FileStatus.WRITE_NOT_ALLOWED, file.write(area(3)));
        assertEquals(FileStatus.NOT_OPEN, file.close());
    }

    @Test
    @DisplayName("順編成の WRITE は I-O では書けない (FR-103)")
    void writingToAsequentialFileOpenedForIoIsRefused() {
        // 読みながら書き戻すのは REWRITE の仕事である。順編成の WRITE は
        // OUTPUT か EXTEND だけである (CCVS85 の SQ156A)
        SequentialDataSet out = SequentialDataSet.at(directory.resolve("IO.DAT"));
        out.open(OpenMode.OUTPUT);
        out.write(area(3));
        out.close();

        SequentialDataSet file = SequentialDataSet.at(directory.resolve("IO.DAT"));
        assertEquals(FileStatus.OK, file.open(OpenMode.IO));
        assertEquals(FileStatus.WRITE_NOT_ALLOWED, file.write(area(3)));
    }

    @Test
    @DisplayName("読めなかったあとの REWRITE は 43 になる (FR-103)")
    void rewritingAfterAnUnsuccessfulReadIsRefused() {
        // 規格は REWRITE の前の入出力文が<b>成功した READ</b> であることを求めている
        // (85 規格 VII-51, 4.6.4(5))。終わりまで読んだあとは何も指していない。
        // 消しておかないと、1 本前のレコードを書き換えてしまう (CCVS85 の SQ144A)
        Path path = directory.resolve("E.DAT");
        DataSetAttributes attributes =
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT);
        SequentialDataSet out = new SequentialDataSet(path, attributes);
        out.open(OpenMode.OUTPUT);
        out.write(CodePages.DEFAULT.encode("abc"));
        out.close();

        SequentialDataSet file = new SequentialDataSet(path, attributes);
        file.open(OpenMode.IO);
        assertEquals(FileStatus.OK, file.read(area(3)));
        assertEquals(FileStatus.AT_END, file.read(area(3)));
        assertEquals(FileStatus.NO_CURRENT_RECORD, file.rewrite(CodePages.DEFAULT.encode("xyz")));
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
    @DisplayName("可変長でも REWRITE は長さを変えられない (FR-102, FR-106)")
    void rewritingAVariableRecordCannotChangeItsLength() {
        // 規格は「書き換えるレコードの文字位置の数は、置き換えられるレコードの
        // 文字位置の数と等しくなければならない」と決めている (85 規格 VII-48)。
        // 順編成では可変長でも同じである——あとのレコードの位置がずれてしまう。
        // CCVS85 の SQ227A / SQ228A がここを見ている
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
        assertEquals(FileStatus.REWRITE_LENGTH, file.rewrite(CodePages.DEFAULT.encode("x")));
        // 同じ長さなら書き換えられる
        assertEquals(FileStatus.OK, file.rewrite(CodePages.DEFAULT.encode("zzzz")));
        file.close();

        SequentialDataSet back = new SequentialDataSet(path, attributes);
        back.open(OpenMode.INPUT);
        byte[] record = area(10);
        back.read(record);
        assertEquals(4, back.lastLength());
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

    // ---- 形が壊れている (FR-141) ----

    @Test
    @DisplayName("固定長で割り切れない半端は、そこで読めなくなる (FR-103, FR-141)")
    void aPartialFixedRecordIsAnIoError() throws IOException {
        Path path = write("F.DAT", "aaabbbcc");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        file.open(OpenMode.INPUT);
        byte[] record = area(3);
        // 切れるところまでは読める。読めていたものを捨てはしない
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals("aaa", decode(record));
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals("bbb", decode(record));
        assertEquals(FileStatus.IO_ERROR, file.read(record));
    }

    @Test
    @DisplayName("半端を短いレコードとして渡さない (FR-141)")
    void aPartialRecordIsNeverHandedOver() throws IOException {
        Path path = write("F.DAT", "aaabbbcc");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        file.open(OpenMode.INPUT);
        byte[] record = area(3);
        file.read(record);
        file.read(record);
        file.read(record);
        // 誤りを返したのだから、受取領域は前のレコードのままである
        assertEquals("bbb", decode(record));
    }

    @Test
    @DisplayName("可変長で RDW がつながらなければ、そこで読めなくなる (FR-103, FR-141)")
    void abrokenRdwIsAnIoError() throws IOException {
        // 1 件目は正しい。2 件目の RDW は残りより長い長さを名乗っている
        byte[] bytes = new byte[]{0, 7, 0, 0, 'a', 'b', 'c', 0, 99, 0, 0, 'x'};
        Path path = directory.resolve("V.DAT");
        Files.write(path, bytes);
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.VARIABLE, 3, CodePages.DEFAULT));

        file.open(OpenMode.INPUT);
        byte[] record = area(3);
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals(FileStatus.IO_ERROR, file.read(record));
    }

    @Test
    @DisplayName("壊れていなければ誤りにはしない (FR-141)")
    void awholeDataSetIsNotAnError() throws IOException {
        Path path = write("F.DAT", "aaabbb");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        file.open(OpenMode.INPUT);
        byte[] record = area(3);
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals(FileStatus.AT_END, file.read(record));
    }

    // ---- 割り当てた領域 (FR-141) ----

    @Test
    @DisplayName("割り当てた領域を使い切れば書けなくなる (FR-103, FR-141)")
    void writingPastTheSpaceFails() {
        SequentialDataSet file = new SequentialDataSet(directory.resolve("O.DAT"),
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));
        file.limit(6);

        file.open(OpenMode.OUTPUT);
        assertEquals(FileStatus.OK, file.write(CodePages.DEFAULT.encode("aaa")));
        assertEquals(FileStatus.OK, file.write(CodePages.DEFAULT.encode("bbb")));
        assertEquals(FileStatus.NO_SPACE, file.write(CodePages.DEFAULT.encode("ccc")));
    }

    @Test
    @DisplayName("書けなくなっても、それまでのレコードは残る (FR-141)")
    void whatFitWasStillWritten() throws IOException {
        Path path = directory.resolve("O.DAT");
        SequentialDataSet file = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));
        file.limit(6);

        file.open(OpenMode.OUTPUT);
        file.write(CodePages.DEFAULT.encode("aaa"));
        file.write(CodePages.DEFAULT.encode("bbb"));
        file.write(CodePages.DEFAULT.encode("ccc"));
        file.close();

        assertEquals("aaabbb", decode(Files.readAllBytes(path)));
    }

    @Test
    @DisplayName("限りを設けなければいくらでも書ける (FR-141)")
    void noLimitMeansNoLimit() {
        SequentialDataSet file = new SequentialDataSet(directory.resolve("O.DAT"),
                new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT));

        file.open(OpenMode.OUTPUT);
        for (int i = 0; i < 100; i++) {
            assertEquals(FileStatus.OK, file.write(CodePages.DEFAULT.encode("aaa")));
        }
    }

    @Test
    @DisplayName("可変長では RDW の 4 バイトも領域を使う (FR-141)")
    void theRdwCountsTowardTheSpace() {
        SequentialDataSet file = new SequentialDataSet(directory.resolve("V.DAT"),
                new DataSetAttributes(RecordFormat.VARIABLE, 3, CodePages.DEFAULT));
        file.limit(7);

        file.open(OpenMode.OUTPUT);
        assertEquals(FileStatus.OK, file.write(CodePages.DEFAULT.encode("aaa")));
        assertEquals(FileStatus.NO_SPACE, file.write(CodePages.DEFAULT.encode("bbb")));
    }

    // ---- 区分データセットのメンバ (要件 FR-113) ----

    @Test
    @DisplayName("メンバが無ければ開けない。状態コードにならない (FR-113, FR-141)")
    void aMissingMemberCannotBeOpened() throws IOException {
        Files.createDirectories(directory.resolve("MY.LIB"));
        SequentialDataSet file = new SequentialDataSet(directory.resolve("MY.LIB/NOSUCH"),
                new DataSetAttributes(RecordFormat.FIXED, 5, CodePages.DEFAULT));
        file.member(true);

        // データセット (ライブラリ) はある。無いのはメンバなので、割当ては通っている。
        // プログラムへ制御は戻らない
        DataSetOpenException failure = org.junit.jupiter.api.Assertions.assertThrows(
                DataSetOpenException.class, () -> file.open(OpenMode.INPUT));
        assertEquals(AbendCode.S013, failure.abendCode());
        assertTrue(failure.getMessage().contains("member not found"), failure.getMessage());
    }

    @Test
    @DisplayName("メンバでなければ、無いファイルは 35 のままである (FR-103, FR-113)")
    void aMissingSequentialFileIsStillAStatus() {
        SequentialDataSet file = new SequentialDataSet(directory.resolve("NOSUCH.DAT"),
                new DataSetAttributes(RecordFormat.FIXED, 5, CodePages.DEFAULT));

        // 順編成なら「無いファイル」であり、FILE STATUS で受け止められる
        assertEquals(FileStatus.NOT_FOUND, file.open(OpenMode.INPUT));
    }

    @Test
    @DisplayName("書くのなら無いメンバでもよい。そこで作る (FR-113)")
    void writingCreatesTheMember() throws IOException {
        Files.createDirectories(directory.resolve("MY.LIB"));
        SequentialDataSet file = new SequentialDataSet(directory.resolve("MY.LIB/NEWMEM"),
                new DataSetAttributes(RecordFormat.FIXED, 5, CodePages.DEFAULT));
        file.member(true);

        assertEquals(FileStatus.OK, file.open(OpenMode.OUTPUT));
        assertEquals(FileStatus.OK, file.write(CodePages.DEFAULT.encode("aaaaa")));
        file.close();
        assertEquals("aaaaa", decode(Files.readAllBytes(directory.resolve("MY.LIB/NEWMEM"))));
    }

    @Test
    @DisplayName("区分データセットそのものは開けない (FR-113, FR-141)")
    void aLibraryIsNotOpenedByItself() throws IOException {
        Files.createDirectories(directory.resolve("MY.LIB"));
        SequentialDataSet file = new SequentialDataSet(directory.resolve("MY.LIB"),
                new DataSetAttributes(RecordFormat.FIXED, 5, CodePages.DEFAULT));

        // どのメンバのバイト列を読むのかが決まっていない
        DataSetOpenException failure = org.junit.jupiter.api.Assertions.assertThrows(
                DataSetOpenException.class, () -> file.open(OpenMode.INPUT));
        assertEquals(AbendCode.S013, failure.abendCode());
    }
}
