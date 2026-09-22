package dev.cobolonjava.runtime.japanese;

import static dev.cobolonjava.runtime.TestSupport.assertHex;
import static dev.cobolonjava.runtime.TestSupport.bytes;
import static dev.cobolonjava.runtime.TestSupport.hex;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.codepage.JapaneseFixtures;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.FileStatus;
import dev.cobolonjava.runtime.file.OpenMode;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.file.SequentialDataSet;
import dev.cobolonjava.runtime.storage.Storage;
import dev.cobolonjava.runtime.verb.Compare;
import dev.cobolonjava.runtime.verb.Inspect;
import dev.cobolonjava.runtime.verb.Move;
import dev.cobolonjava.runtime.verb.Region;
import dev.cobolonjava.runtime.verb.StringVerb;
import dev.cobolonjava.runtime.verb.UnstringVerb;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 日本語のバイトが演算を通っても変わらないこと (要件 FR-050)。
 *
 * <p><b>なぜこれで足りるのか</b>: 日本語には外の基準が無い。Hercules の MVS 3.8j に日本語
 * コードページは無く、CCVS85 に日本語の試験も無い。しかし要件 FR-050 は、文字データを
 * {@code String} ではなく<b>バイト列として持つ</b>と決めている。これは測って確かめる
 * 振る舞いではなく、<b>処理系の作りそのもの</b>である。
 *
 * <p>作りがそのとおりなら、文字の意味を見ない演算は<b>バイトに対して透明</b>でなければ
 * ならない。透明なら、期待値は参照実装ではなく<b>入力から導ける</b>。だから外の基準が
 * 無くても書ける。ここで固定するのはこの 1 つである。
 *
 * <blockquote><b>処理系は、渡されていないバイトを増やさない。渡されたバイトを
 * 減らさない。置き換えない。</b></blockquote>
 *
 * <p><b>「化けない」の意味</b>: {@code PIC X(3)} に全角 2 文字を転記すればホストでも
 * 3 バイト目で切れ、2 文字目は壊れる。それは資産が決めた桁数の帰結であり、実機でもそう
 * なる。処理系の責任は<b>自分でバイトを作り変えないこと</b>までである。切れた位置まで
 * 実機と同じであることは、ここでは言っていない。
 *
 * <p>各試験は既存の試験と同じ演算を、検体だけ日本語に替えて呼ぶ。対応は
 * {@code docs/design/28-japanese-verification.md} の表にある。既存の試験を書き替えては
 * いない。<b>壊さずに足す</b>のがこの増分の形である。
 */
@Tag("V1")
class JapaneseByteTransparencyTest {

    /** 日本語の資産が使う混在コードページ。 */
    private static final CodePage CP = CodePages.IBM_930;

    /** 氏名 (全角 4 文字、10 バイト)。 */
    private static final byte[] NAME = CP.encode(JapaneseFixtures.NAME);

    /** 品名 (全角 3 文字、8 バイト)。 */
    private static final byte[] ITEM = CP.encode(JapaneseFixtures.ITEM);

    @TempDir
    Path directory;

    // ---- 転記 (MoveTest / AlphanumericEditorTest に対応) ----

    @Test
    @DisplayName("受取項目がちょうどの長さなら、転記はバイトを 1 つも変えない (FR-060)")
    void moveIntoAnExactlySizedItemKeepsEveryByte() {
        assertArrayEquals(NAME, Move.alphanumeric(NAME, NAME.length, false, CP));
    }

    @Test
    @DisplayName("余った位置は空白で埋まる。埋め草は日本語コードページでも X'40' (FR-060)")
    void theRemainderIsFilledWithSpaces() {
        byte[] moved = Move.alphanumeric(NAME, NAME.length + 4, false, CP);

        assertHex(hex(NAME) + "40404040", moved);
    }

    @Test
    @DisplayName("右詰めの転記も送出側のバイトをそのまま置く (FR-060)")
    void justifiedRightKeepsTheSourceBytes() {
        byte[] moved = Move.alphanumeric(NAME, NAME.length + 2, true, CP);

        assertHex("4040" + hex(NAME), moved);
    }

    @Test
    @DisplayName("短い受取項目は頭から詰めて切る。処理系は切り口を繕わない (FR-060)")
    void aShorterItemTruncatesWithoutRepair() {
        // 5 バイトはシフトアウト + 全角 2 文字である。組が閉じないまま切れる。
        // ホストの PIC X も同じところで切れる。整えて返すほうが嘘になる
        byte[] moved = Move.alphanumeric(NAME, 5, false, CP);

        assertArrayEquals(java.util.Arrays.copyOf(NAME, 5), moved);
    }

    // ---- 部分参照 (DataViewTest に対応) ----

    @Test
    @DisplayName("部分参照はバイトの位置で切り出す (FR-024)")
    void referenceModificationCutsByByte() {
        Storage storage = Storage.copyOf(NAME);

        // WS-NAME(2:8) — シフトコードの内側だけを見る書き方である
        assertArrayEquals(java.util.Arrays.copyOfRange(NAME, 1, 9),
                storage.view(0, NAME.length).subView(1, 8).toByteArray());
    }

    @Test
    @DisplayName("記憶域へ置いて取り出すとバイトは同一である (FR-050)")
    void storageRoundTripsTheBytes() {
        Storage storage = Storage.allocate(NAME.length);
        storage.view(0, NAME.length).setBytes(NAME);

        assertArrayEquals(NAME, storage.view(0, NAME.length).toByteArray());
        assertEquals(JapaneseFixtures.NAME, CP.decode(storage.view(0, NAME.length).toByteArray()));
    }

    // ---- 連結と分解 (StringVerbTest / UnstringVerbTest に対応) ----

    @Test
    @DisplayName("STRING DELIMITED BY SIZE は日本語をつなげても中身を変えない (FR-065)")
    void stringConcatenatesWithoutTouchingTheBytes() {
        byte[] target = new byte[NAME.length + ITEM.length];
        StringVerb.Result r = StringVerb.string(target, 1,
                List.of(StringVerb.Source.bySize(NAME), StringVerb.Source.bySize(ITEM)));

        assertHex(hex(NAME) + hex(ITEM), r.target());
        assertEquals(target.length + 1, r.pointer());
    }

    @Test
    @DisplayName("UNSTRING は区切りのバイトで切る。全角の中のバイトは区切りにならない (FR-065)")
    void unstringSplitsOnTheDelimiterBytes() {
        // 区切りにはコンマを使う。EBCDIC の X'6B' であり、この検体の全角のバイトには
        // 現れない。現れる 1 バイトを区切りにすれば全角の途中で切れるが、
        // それはホストの UNSTRING でも起きる。処理系が足す壊れ方ではない
        byte[] comma = CP.encode(",");
        byte[] source = concat(NAME, comma, ITEM);

        UnstringVerb.Result r = UnstringVerb.unstring(source, 1,
                List.of(UnstringVerb.Delimiter.of(comma)),
                List.of(UnstringVerb.Field.of(NAME.length), UnstringVerb.Field.of(ITEM.length)), CP);

        assertArrayEquals(NAME, r.fields().get(0));
        assertArrayEquals(ITEM, r.fields().get(1));
        assertEquals(JapaneseFixtures.NAME, CP.decode(r.fields().get(0)));
        assertEquals(JapaneseFixtures.ITEM, CP.decode(r.fields().get(1)));
    }

    // ---- 走査 (InspectTest に対応) ----

    @Test
    @DisplayName("INSPECT TALLYING はバイトの並びを数える (FR-066)")
    void inspectTalliesByteSequences() {
        byte[] data = concat(NAME, NAME);

        assertEquals(2, Inspect.tallyAll(data, NAME, Region.whole()));
        assertEquals(2, Inspect.tallyAll(data, bytes("0E"), Region.whole()),
                "シフトアウトも 1 バイトとして数に入る");
    }

    @Test
    @DisplayName("INSPECT REPLACING は置き換えた所だけを変え、残りのバイトを保つ (FR-066)")
    void inspectReplacingLeavesTheRestAlone() {
        byte[] data = concat(NAME, CP.encode("  "));
        byte[] replaced = Inspect.replaceAll(data, CP.encode("  "), CP.encode("00"),
                Region.whole());

        assertHex(hex(NAME) + "F0F0", replaced);
    }

    // ---- 比較 (CompareTest に対応) ----

    @Test
    @DisplayName("同じバイト列は等しい。短いほうは空白で埋めて比べる (FR-046)")
    void equalBytesCompareEqual() {
        assertEquals(0, Compare.alphanumeric(NAME, NAME, CP));
        assertEquals(0, Compare.alphanumeric(NAME, concat(NAME, CP.encode("  ")), CP));
    }

    @Test
    @DisplayName("比較はバイト値の順で決まる。コードページを替えても結果は変わらない (FR-053)")
    void comparisonIsByByteValueAndDoesNotDependOnTheCodePage() {
        // EBCDIC 系はどれもバイト値の並びがそのまま照合順序である。
        // 生成コードが CodePages.DEFAULT を焼き込んでいても日本語の比較が狂わないのは
        // これが理由である (設計 28 章)
        int with930 = Compare.alphanumeric(NAME, ITEM, CodePages.IBM_930);
        int with939 = Compare.alphanumeric(NAME, ITEM, CodePages.IBM_939);
        int with1047 = Compare.alphanumeric(NAME, ITEM, CodePages.IBM_1047);

        assertEquals(Integer.signum(with930), Integer.signum(with939));
        assertEquals(Integer.signum(with930), Integer.signum(with1047));
    }

    // ---- ファイル入出力 (SequentialDataSetTest に対応) ----

    @Test
    @DisplayName("書いて読み直したレコードはバイトが同一である (FR-110)")
    void aRecordSurvivesTheRoundTripThroughAFile() throws IOException {
        int length = NAME.length + ITEM.length;
        Path path = directory.resolve("JP.DAT");
        DataSetAttributes attributes = new DataSetAttributes(RecordFormat.FIXED, length, CP);

        SequentialDataSet out = new SequentialDataSet(path, attributes);
        assertEquals(FileStatus.OK, out.open(OpenMode.OUTPUT));
        assertEquals(FileStatus.OK, out.write(concat(NAME, ITEM)));
        assertEquals(FileStatus.OK, out.close());

        SequentialDataSet in = new SequentialDataSet(path, attributes);
        assertEquals(FileStatus.OK, in.open(OpenMode.INPUT));
        byte[] record = new byte[length];
        assertEquals(FileStatus.OK, in.read(record));
        assertEquals(FileStatus.OK, in.close());

        assertArrayEquals(concat(NAME, ITEM), record);
        assertEquals(JapaneseFixtures.NAME + JapaneseFixtures.ITEM, CP.decode(record));
    }

    @Test
    @DisplayName("ファイルの中身は記憶域のバイトと同じ。処理系は途中で変換しない (FR-050)")
    void theFileHoldsExactlyTheBytesFromStorage() throws IOException {
        Path path = directory.resolve("RAW.DAT");
        SequentialDataSet out = new SequentialDataSet(path,
                new DataSetAttributes(RecordFormat.FIXED, NAME.length, CP));
        out.open(OpenMode.OUTPUT);
        out.write(NAME);
        out.close();

        assertArrayEquals(NAME, java.nio.file.Files.readAllBytes(path));
    }

    // ---- 全体 ----

    @Test
    @DisplayName("連結・分解・走査・比較を通しても、出てきた文字は入れた文字である (FR-050)")
    void theWholeChainPreservesTheCharacters() {
        byte[] slash = CP.encode("/");
        byte[] joined = StringVerb.string(
                new byte[NAME.length + slash.length + ITEM.length], 1, List.of(
                        StringVerb.Source.bySize(NAME),
                        StringVerb.Source.bySize(slash),
                        StringVerb.Source.bySize(ITEM)))
                .target();

        UnstringVerb.Result split = UnstringVerb.unstring(joined, 1,
                List.of(UnstringVerb.Delimiter.of(slash)),
                List.of(UnstringVerb.Field.of(NAME.length), UnstringVerb.Field.of(ITEM.length)), CP);

        assertEquals(JapaneseFixtures.NAME, CP.decode(split.fields().get(0)));
        assertEquals(JapaneseFixtures.ITEM, CP.decode(split.fields().get(1)));
        assertTrue(Inspect.tallyAll(joined, slash, Region.whole()) == 1,
                "区切りは 1 つだけ。全角のバイトが区切りに化けていない");
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}
