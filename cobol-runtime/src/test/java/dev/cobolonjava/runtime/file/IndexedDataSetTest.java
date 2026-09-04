package dev.cobolonjava.runtime.file;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * 索引編成のデータセット (要件 FR-100, FR-101, FR-102, FR-103)。
 *
 * <p>レコードを<b>中身の一部で引く</b>。鍵はレコードの中にあり、住所ではなく持ち物である。
 * 索引はデータから導けるので、ファイルに持つのはレコードだけである。
 */
@Tag("V1")
class IndexedDataSetTest {

    @TempDir
    Path directory;

    /** レコードは 8 バイト。主鍵が先頭 3 バイト、副鍵が次の 2 バイトである。 */
    private static final DataSetAttributes LAYOUT =
            new DataSetAttributes(RecordFormat.FIXED, 8, CodePages.DEFAULT);
    private static final IndexedDataSet.Key PRIMARY = new IndexedDataSet.Key(0, 3, false);

    private IndexedDataSet at(String name, IndexedDataSet.Key... alternates) {
        return new IndexedDataSet(directory.resolve(name), LAYOUT, PRIMARY, List.of(alternates));
    }

    private static byte[] area() {
        byte[] out = new byte[8];
        java.util.Arrays.fill(out, CodePages.DEFAULT.space());
        return out;
    }

    private static String decode(byte[] bytes) {
        return CodePages.DEFAULT.decode(bytes);
    }

    private static byte[] record(String key, String alternate, String rest) {
        return CodePages.DEFAULT.encode(key + alternate + rest);
    }

    private static byte[] key(String text) {
        return CodePages.DEFAULT.encode(text);
    }

    /** 主鍵 B / A / C の順に書いたデータセット。 */
    private IndexedDataSet seeded(String name, IndexedDataSet.Key... alternates) {
        IndexedDataSet file = at(name, alternates);
        file.open(OpenMode.OUTPUT, false);
        file.writeKey(record("BBB", "y1", "two"));
        file.writeKey(record("AAA", "x1", "one"));
        file.writeKey(record("CCC", "x1", "thr"));
        file.close();
        return at(name, alternates);
    }

    @Test
    @DisplayName("書き出す並びは主鍵の順である (FR-100)")
    void recordsAreStoredInKeyOrder() throws IOException {
        seeded("K.DAT");

        assertEquals("AAAx1oneBBBy1twoCCCx1thr",
                decode(Files.readAllBytes(directory.resolve("K.DAT"))));
    }

    @Test
    @DisplayName("順次読みは主鍵の順に返す (FR-102)")
    void sequentialReadingFollowsTheKeyOrder() {
        IndexedDataSet file = seeded("K.DAT");

        file.open(OpenMode.INPUT, false);
        byte[] record = area();
        file.read(record);
        assertEquals("AAAx1one", decode(record));
        file.read(record);
        assertEquals("BBBy1two", decode(record));
        file.read(record);
        assertEquals("CCCx1thr", decode(record));
        assertEquals(FileStatus.AT_END, file.read(record));
    }

    @Test
    @DisplayName("主鍵で引ける (FR-101)")
    void recordsAreReadByPrimaryKey() {
        IndexedDataSet file = seeded("K.DAT");

        file.open(OpenMode.INPUT, false);
        byte[] record = area();
        assertEquals(FileStatus.OK, file.readKey(0, key("BBB"), record));
        assertEquals("BBBy1two", decode(record));
        assertEquals(FileStatus.NO_RECORD, file.readKey(0, key("ZZZ"), record));
    }

    @Test
    @DisplayName("鍵で読んだあとの順次読みはその次から続く (FR-101)")
    void sequentialReadingResumesAfterAKeyedRead() {
        IndexedDataSet file = seeded("K.DAT");

        file.open(OpenMode.INPUT, false);
        byte[] record = area();
        file.readKey(0, key("AAA"), record);
        file.read(record);
        assertEquals("BBBy1two", decode(record));
    }

    @Test
    @DisplayName("同じ主鍵は 2 つ持てない (FR-103)")
    void aDuplicatePrimaryKeyIsRejected() {
        IndexedDataSet file = seeded("K.DAT");

        file.open(OpenMode.IO, false);
        assertEquals(FileStatus.DUPLICATE_KEY, file.writeKey(record("AAA", "z9", "dup")));
    }

    @Test
    @DisplayName("順アクセスの書き込みは昇順でなければならない (FR-103)")
    void sequentialWritesMustAscend() {
        IndexedDataSet file = at("K.DAT");

        file.open(OpenMode.OUTPUT, false);
        assertEquals(FileStatus.OK, file.write(record("AAA", "x1", "one")));
        assertEquals(FileStatus.OK, file.write(record("BBB", "y1", "two")));
        assertEquals(FileStatus.KEY_SEQUENCE, file.write(record("ABC", "z1", "bad")));
    }

    @Test
    @DisplayName("START は読まずに位置を決める (FR-101)")
    void startPositionsWithoutReading() {
        IndexedDataSet file = seeded("K.DAT");
        file.open(OpenMode.INPUT, false);
        byte[] record = area();

        assertEquals(FileStatus.OK, file.start(0, key("BBB"), KeyRelation.NOT_LESS));
        file.read(record);
        assertEquals("BBBy1two", decode(record));

        assertEquals(FileStatus.OK, file.start(0, key("BBB"), KeyRelation.GREATER));
        file.read(record);
        assertEquals("CCCx1thr", decode(record));

        // 小さいほうを探す関係では、満たす最後のレコードが位置になる
        assertEquals(FileStatus.OK, file.start(0, key("CCC"), KeyRelation.LESS));
        file.read(record);
        assertEquals("BBBy1two", decode(record));

        assertEquals(FileStatus.NO_RECORD, file.start(0, key("ZZZ"), KeyRelation.GREATER));
    }

    @Test
    @DisplayName("副鍵で引ける (FR-100, FR-101)")
    void recordsAreReadByAnAlternateKey() {
        IndexedDataSet.Key alternate = new IndexedDataSet.Key(3, 2, true);
        IndexedDataSet file = seeded("K.DAT", alternate);

        file.open(OpenMode.INPUT, false);
        byte[] record = area();
        assertEquals(FileStatus.OK, file.readKey(1, key("y1"), record));
        assertEquals("BBBy1two", decode(record));
        assertEquals(FileStatus.NO_RECORD, file.readKey(1, key("zz"), record));
    }

    @Test
    @DisplayName("副鍵の重複は書いた順に返る (FR-100)")
    void duplicateAlternateKeysComeBackInOrder() {
        IndexedDataSet.Key alternate = new IndexedDataSet.Key(3, 2, true);
        IndexedDataSet file = seeded("K.DAT", alternate);

        file.open(OpenMode.INPUT, false);
        byte[] record = area();
        assertEquals(FileStatus.OK, file.readKey(1, key("x1"), record));
        assertEquals("AAAx1one", decode(record));
        // 同じ副鍵の続きは、主鍵の順にたどる
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals("CCCx1thr", decode(record));
        // 副鍵を読み切れば次の副鍵へ移る
        assertEquals(FileStatus.OK, file.read(record));
        assertEquals("BBBy1two", decode(record));
    }

    @Test
    @DisplayName("重複を許さない副鍵は同じ値を 2 つ持てない (FR-103)")
    void anAlternateWithoutDuplicatesRejectsTheSecond() {
        IndexedDataSet.Key alternate = new IndexedDataSet.Key(3, 2, false);
        IndexedDataSet file = at("K.DAT", alternate);

        file.open(OpenMode.OUTPUT, false);
        assertEquals(FileStatus.OK, file.writeKey(record("AAA", "x1", "one")));
        assertEquals(FileStatus.DUPLICATE_KEY, file.writeKey(record("BBB", "x1", "two")));
        assertEquals(FileStatus.OK, file.writeKey(record("CCC", "x2", "thr")));
    }

    @Test
    @DisplayName("副鍵の順に順次読みできる (FR-101)")
    void startCanFollowAnAlternateIndex() {
        IndexedDataSet.Key alternate = new IndexedDataSet.Key(3, 2, true);
        IndexedDataSet file = seeded("K.DAT", alternate);

        file.open(OpenMode.INPUT, false);
        assertEquals(FileStatus.OK, file.start(1, key("x1"), KeyRelation.NOT_LESS));
        byte[] record = area();
        file.read(record);
        assertEquals("AAAx1one", decode(record));
        file.read(record);
        assertEquals("CCCx1thr", decode(record));
        file.read(record);
        assertEquals("BBBy1two", decode(record));
        assertEquals(FileStatus.AT_END, file.read(record));
    }

    @Test
    @DisplayName("書き換えで主鍵は変えられない (FR-103)")
    void rewritingCannotChangeThePrimaryKey() {
        IndexedDataSet file = seeded("K.DAT");

        file.open(OpenMode.IO, false);
        byte[] record = area();
        file.read(record);
        assertEquals(FileStatus.KEY_SEQUENCE, file.rewrite(record("ZZZ", "x1", "one")));
        assertEquals(FileStatus.OK, file.rewrite(record("AAA", "x9", "ONE")));
    }

    @Test
    @DisplayName("鍵で引いた書き換えと削除ができる (FR-101)")
    void recordsAreChangedByKey() {
        IndexedDataSet file = seeded("K.DAT");

        file.open(OpenMode.IO, false);
        assertEquals(FileStatus.OK, file.rewriteKey(record("BBB", "y9", "TWO")));
        assertEquals(FileStatus.NO_RECORD, file.rewriteKey(record("ZZZ", "y9", "non")));
        assertEquals(FileStatus.OK, file.deleteKey(key("AAA")));
        assertEquals(FileStatus.NO_RECORD, file.deleteKey(key("AAA")));
        file.close();

        IndexedDataSet back = at("K.DAT");
        back.open(OpenMode.INPUT, false);
        byte[] record = area();
        back.read(record);
        assertEquals("BBBy9TWO", decode(record));
        back.read(record);
        assertEquals("CCCx1thr", decode(record));
        assertEquals(FileStatus.AT_END, back.read(record));
    }

    @Test
    @DisplayName("読んだあとなら削除できる (FR-102)")
    void deletingFollowsARead() {
        IndexedDataSet file = seeded("K.DAT");

        file.open(OpenMode.IO, false);
        assertEquals(FileStatus.NO_CURRENT_RECORD, file.delete());
        file.read(area());
        assertEquals(FileStatus.OK, file.delete());
        assertEquals(FileStatus.NO_CURRENT_RECORD, file.delete());
    }

    @Test
    @DisplayName("I-O 以外での書き換えと削除は 49 になる (FR-103)")
    void changingOutsideInputOutputReportsFortyNine() {
        IndexedDataSet file = seeded("K.DAT");

        file.open(OpenMode.INPUT, false);
        file.read(area());
        assertEquals(FileStatus.REWRITE_NOT_ALLOWED, file.rewrite(record("AAA", "x1", "one")));
        assertEquals(FileStatus.REWRITE_NOT_ALLOWED, file.delete());
    }

    @Test
    @DisplayName("開いていなければどの操作も 42 になる (FR-103)")
    void everyOperationNeedsAnOpenFile() {
        IndexedDataSet file = at("K.DAT");

        assertEquals(FileStatus.NOT_OPEN, file.read(area()));
        assertEquals(FileStatus.NOT_OPEN, file.readKey(0, key("AAA"), area()));
        assertEquals(FileStatus.NOT_OPEN, file.write(record("AAA", "x1", "one")));
        assertEquals(FileStatus.NOT_OPEN, file.writeKey(record("AAA", "x1", "one")));
        assertEquals(FileStatus.NOT_OPEN, file.rewrite(record("AAA", "x1", "one")));
        assertEquals(FileStatus.NOT_OPEN, file.delete());
        assertEquals(FileStatus.NOT_OPEN, file.start(0, key("AAA"), KeyRelation.EQUAL));
        assertEquals(FileStatus.NOT_OPEN, file.close());
    }

    @Test
    @DisplayName("ないファイルは OUTPUT 以外では 35 になる (FR-103)")
    void aMissingFileNeedsOutputOrOptional() {
        assertEquals(FileStatus.NOT_FOUND, at("NONE.DAT").open(OpenMode.INPUT, false));
        assertEquals(FileStatus.OPTIONAL_CREATED, at("NONE.DAT").open(OpenMode.INPUT, true));
    }
}
