package dev.cobolonjava.runtime.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 相対編成のデータセット (要件 FR-100, FR-101, FR-102, FR-103)。
 *
 * <p>レコードを<b>番号で引く</b>。番号が住所なので、消してもあとのレコードは動かない。
 * 消したところは空きスロットとして残る。
 */
@Tag("V1")
class RelativeDataSetTest {

    @TempDir
    Path directory;

    private static final DataSetAttributes SLOTS =
            new DataSetAttributes(RecordFormat.FIXED, 3, CodePages.DEFAULT);

    private RelativeDataSet at(String name) {
        return new RelativeDataSet(directory.resolve(name), SLOTS);
    }

    private static byte[] area() {
        byte[] out = new byte[3];
        java.util.Arrays.fill(out, CodePages.DEFAULT.space());
        return out;
    }

    private static String decode(byte[] bytes) {
        return CodePages.DEFAULT.decode(bytes);
    }

    private static byte[] bytes(String text) {
        return CodePages.DEFAULT.encode(text);
    }

    /** 3 つのレコードを持つデータセットを作る。 */
    private RelativeDataSet filled(String name) {
        RelativeDataSet file = at(name);
        file.open(OpenMode.OUTPUT, false);
        file.write(bytes("aaa"));
        file.write(bytes("bbb"));
        file.write(bytes("ccc"));
        file.close();
        return at(name);
    }

    @Test
    @DisplayName("順に書けば 1 番から並ぶ (FR-102)")
    void sequentialWritesFillFromOne() throws IOException {
        filled("R.DAT");

        assertEquals("aaabbbccc", decode(Files.readAllBytes(directory.resolve("R.DAT"))));
    }

    @Test
    @DisplayName("番号で読める (FR-101)")
    void recordsAreReadByNumber() {
        RelativeDataSet file = filled("R.DAT");

        file.open(OpenMode.INPUT, false);
        byte[] record = area();
        assertEquals(FileStatus.OK, file.readAt(2, record));
        assertEquals("bbb", decode(record));
        assertEquals(2, file.currentNumber());
    }

    @Test
    @DisplayName("番号で読んだあとの順次読みはその次から続く (FR-101)")
    void sequentialReadingResumesAfterARandomRead() {
        RelativeDataSet file = filled("R.DAT");

        file.open(OpenMode.INPUT, false);
        byte[] record = area();
        file.readAt(1, record);
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals("bbb", decode(record));
    }

    @Test
    @DisplayName("ないスロットを読めば 23 になる (FR-103)")
    void readingAnEmptySlotReportsTwentyThree() {
        RelativeDataSet file = filled("R.DAT");

        file.open(OpenMode.INPUT, false);
        assertEquals(FileStatus.NO_RECORD, file.readAt(9, area()));
        assertEquals(FileStatus.NO_RECORD, file.readAt(0, area()));
    }

    @Test
    @DisplayName("番号を空けて書けば穴が残る (FR-100)")
    void writingWithGapsLeavesEmptySlots() throws IOException {
        RelativeDataSet file = at("R.DAT");
        file.open(OpenMode.OUTPUT, false);
        assertEquals(FileStatus.OK, file.writeAt(1, bytes("aaa")));
        assertEquals(FileStatus.OK, file.writeAt(4, bytes("ddd")));
        file.close();

        assertEquals("aaa      ddd", decode(Files.readAllBytes(directory.resolve("R.DAT"))));
        assertEquals(List.of("recfm=F", "lrecl=3", "codepage=IBM-1047", "empty=2,3"),
                Files.readAllLines(DataSetAttributes.sidecarOf(directory.resolve("R.DAT"))));
    }

    @Test
    @DisplayName("順次読みは空きスロットを飛ばす (FR-102)")
    void sequentialReadingSkipsEmptySlots() {
        RelativeDataSet out = at("R.DAT");
        out.open(OpenMode.OUTPUT, false);
        out.writeAt(1, bytes("aaa"));
        out.writeAt(4, bytes("ddd"));
        out.close();

        RelativeDataSet file = RelativeDataSet.at(directory.resolve("R.DAT"), SLOTS);
        file.open(OpenMode.INPUT, false);
        byte[] record = area();
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals("aaa", decode(record));
        assertEquals(1, file.currentNumber());
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals("ddd", decode(record));
        assertEquals(4, file.currentNumber());
        assertEquals(FileStatus.AT_END, file.read(record));
    }

    @Test
    @DisplayName("使われている番号へ書けば 22 になる (FR-103)")
    void writingOverAnExistingRecordReportsTwentyTwo() {
        RelativeDataSet file = filled("R.DAT");

        file.open(OpenMode.IO, false);
        assertEquals(FileStatus.DUPLICATE_KEY, file.writeAt(2, bytes("xxx")));
    }

    @Test
    @DisplayName("0 番より手前へは書けない (FR-103)")
    void writingBelowOneIsOutOfBounds() {
        RelativeDataSet file = at("R.DAT");

        file.open(OpenMode.OUTPUT, false);
        assertEquals(FileStatus.BOUNDARY, file.writeAt(0, bytes("aaa")));
    }

    @Test
    @DisplayName("消しても番号はずれない (FR-100)")
    void deletingKeepsTheOtherNumbers() {
        RelativeDataSet file = filled("R.DAT");

        file.open(OpenMode.IO, false);
        assertEquals(FileStatus.OK, file.deleteAt(2));
        byte[] record = area();
        assertEquals(FileStatus.NO_RECORD, file.readAt(2, record));
        assertEquals(FileStatus.OK, file.readAt(3, record));
        assertEquals("ccc", decode(record));
    }

    @Test
    @DisplayName("消した穴は次に開いたときも空きである (FR-100)")
    void anEmptySlotSurvivesReopening() {
        RelativeDataSet file = filled("R.DAT");
        file.open(OpenMode.IO, false);
        file.deleteAt(2);
        file.close();

        RelativeDataSet back = RelativeDataSet.at(directory.resolve("R.DAT"), SLOTS);
        assertEquals(List.of(2), back.attributes().emptySlots());
        back.open(OpenMode.INPUT, false);
        assertEquals(FileStatus.NO_RECORD, back.readAt(2, area()));
        assertEquals(FileStatus.OK, back.readAt(3, area()));
    }

    @Test
    @DisplayName("読んだあとなら書き換えも削除もできる (FR-102)")
    void rewritingAndDeletingFollowARead() {
        RelativeDataSet file = filled("R.DAT");

        file.open(OpenMode.IO, false);
        byte[] record = area();
        file.read(record);
        assertEquals(FileStatus.OK, file.rewrite(bytes("AAA")));
        file.read(record);
        assertEquals("bbb", decode(record));
        assertEquals(FileStatus.OK, file.delete());
        file.close();

        RelativeDataSet back = RelativeDataSet.at(directory.resolve("R.DAT"), SLOTS);
        back.open(OpenMode.INPUT, false);
        assertEquals(FileStatus.OK, back.readAt(1, record));
        assertEquals("AAA", decode(record));
        assertEquals(FileStatus.NO_RECORD, back.readAt(2, record));
    }

    @Test
    @DisplayName("読まずに削除すれば 43 になる (FR-103)")
    void deletingWithoutReadingReportsFortyThree() {
        RelativeDataSet file = filled("R.DAT");

        file.open(OpenMode.IO, false);
        assertEquals(FileStatus.NO_CURRENT_RECORD, file.delete());
    }

    @Test
    @DisplayName("I-O 以外での書き換えと削除は 49 になる (FR-103)")
    void changingOutsideInputOutputReportsFortyNine() {
        RelativeDataSet file = filled("R.DAT");

        file.open(OpenMode.INPUT, false);
        file.read(area());
        assertEquals(FileStatus.REWRITE_NOT_ALLOWED, file.rewrite(bytes("xxx")));
        assertEquals(FileStatus.REWRITE_NOT_ALLOWED, file.delete());
    }

    @Test
    @DisplayName("ないスロットの書き換えと削除は 23 になる (FR-103)")
    void changingAnEmptySlotReportsTwentyThree() {
        RelativeDataSet file = filled("R.DAT");

        file.open(OpenMode.IO, false);
        file.deleteAt(2);
        assertEquals(FileStatus.NO_RECORD, file.rewriteAt(2, bytes("xxx")));
        assertEquals(FileStatus.NO_RECORD, file.deleteAt(2));
    }

    @Test
    @DisplayName("START は読まずに位置を決める (FR-101)")
    void startPositionsWithoutReading() {
        RelativeDataSet file = filled("R.DAT");

        file.open(OpenMode.INPUT, false);
        assertEquals(FileStatus.OK, file.start(2, KeyRelation.NOT_LESS));
        byte[] record = area();
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals("bbb", decode(record));
    }

    @Test
    @DisplayName("START の関係で位置が変わる (FR-101)")
    void startHonoursTheRelation() {
        RelativeDataSet file = filled("R.DAT");
        file.open(OpenMode.INPUT, false);
        byte[] record = area();

        assertEquals(FileStatus.OK, file.start(2, KeyRelation.GREATER));
        file.read(record);
        assertEquals("ccc", decode(record));

        assertEquals(FileStatus.OK, file.start(2, KeyRelation.EQUAL));
        file.read(record);
        assertEquals("bbb", decode(record));

        // 小さいほうを探す関係では、条件を満たす最後のレコードが位置になる
        assertEquals(FileStatus.OK, file.start(3, KeyRelation.LESS));
        file.read(record);
        assertEquals("bbb", decode(record));
    }

    @Test
    @DisplayName("満たすレコードがなければ START は 23 になる (FR-103)")
    void startWithoutAMatchReportsTwentyThree() {
        RelativeDataSet file = filled("R.DAT");

        file.open(OpenMode.INPUT, false);
        assertEquals(FileStatus.NO_RECORD, file.start(9, KeyRelation.NOT_LESS));
        assertEquals(FileStatus.NO_RECORD, file.start(1, KeyRelation.LESS));
    }

    @Test
    @DisplayName("開いていなければどの操作も 42 になる (FR-103)")
    void everyOperationNeedsAnOpenFile() {
        RelativeDataSet file = at("R.DAT");

        assertEquals(FileStatus.NOT_OPEN, file.read(area()));
        assertEquals(FileStatus.NOT_OPEN, file.readAt(1, area()));
        assertEquals(FileStatus.NOT_OPEN, file.write(bytes("aaa")));
        assertEquals(FileStatus.NOT_OPEN, file.writeAt(1, bytes("aaa")));
        assertEquals(FileStatus.NOT_OPEN, file.rewrite(bytes("aaa")));
        assertEquals(FileStatus.NOT_OPEN, file.delete());
        assertEquals(FileStatus.NOT_OPEN, file.start(1, KeyRelation.EQUAL));
        assertEquals(FileStatus.NOT_OPEN, file.close());
    }

    @Test
    @DisplayName("鍵に関する誤りは 2 で始まる (FR-103)")
    void keyFailuresStartWithTwo() {
        assertTrue(FileStatus.invalidKey(FileStatus.DUPLICATE_KEY));
        assertTrue(FileStatus.invalidKey(FileStatus.NO_RECORD));
        assertTrue(FileStatus.invalidKey(FileStatus.BOUNDARY));
        assertTrue(FileStatus.invalidKey(FileStatus.KEY_SEQUENCE));
        assertTrue(!FileStatus.invalidKey(FileStatus.OK));
        assertTrue(!FileStatus.invalidKey(FileStatus.AT_END));
    }

    @Test
    @DisplayName("同じ入れ物で開き直しても空きスロットは持ち越さない (FR-100)")
    void reopeningRereadsTheEmptySlots() {
        // 1 つの実行の中で、書いてから開き直す。同じ入れ物を使い回すことになる
        RelativeDataSet file = at("R.DAT");
        file.open(OpenMode.OUTPUT, false);
        file.writeAt(1, bytes("aaa"));
        file.writeAt(3, bytes("ccc"));
        file.close();

        file.open(OpenMode.IO, false);
        // 2 番は書いていない。開いたときに読み直さなければ、空白のレコードとして見えてしまう
        assertEquals(FileStatus.NO_RECORD, file.readAt(2, area()));
        assertEquals(FileStatus.OK, file.deleteAt(3));
        file.close();

        file.open(OpenMode.INPUT, false);
        assertEquals(FileStatus.OK, file.readAt(1, area()));
        assertEquals(FileStatus.NO_RECORD, file.readAt(3, area()));
    }

    @Test
    @DisplayName("空きがなくなればサイドカーから行が消える (FR-100)")
    void anEmptyListLeavesNoLine() throws IOException {
        RelativeDataSet file = at("R.DAT");
        file.open(OpenMode.OUTPUT, false);
        file.writeAt(2, bytes("bbb"));
        file.close();
        assertEquals(List.of(1), RelativeDataSet.at(directory.resolve("R.DAT"), SLOTS)
                .attributes().emptySlots());

        // 穴を埋めれば、空きの一覧は空になる
        file.open(OpenMode.IO, false);
        file.writeAt(1, bytes("aaa"));
        file.close();

        assertEquals(List.of("recfm=F", "lrecl=3", "codepage=IBM-1047"),
                Files.readAllLines(DataSetAttributes.sidecarOf(directory.resolve("R.DAT"))));
        assertEquals(List.of(), RelativeDataSet.at(directory.resolve("R.DAT"), SLOTS)
                .attributes().emptySlots());
    }
}
