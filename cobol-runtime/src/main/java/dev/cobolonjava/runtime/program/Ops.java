package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.data.ZonedDecimal;
import dev.cobolonjava.runtime.decimal.CobolRounding;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.file.DataSet;
import dev.cobolonjava.runtime.file.FileStatus;
import dev.cobolonjava.runtime.file.IndexedDataSet;
import dev.cobolonjava.runtime.file.KeyRelation;
import dev.cobolonjava.runtime.file.KeyedDataSet;
import dev.cobolonjava.runtime.file.OpenMode;
import dev.cobolonjava.runtime.file.Organization;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.file.RelativeDataSet;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.picture.Picture;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import dev.cobolonjava.runtime.verb.Arithmetic;
import dev.cobolonjava.runtime.verb.Compare;
import dev.cobolonjava.runtime.verb.Inspect;
import dev.cobolonjava.runtime.verb.InspectScan;
import dev.cobolonjava.runtime.verb.Region;
import dev.cobolonjava.runtime.verb.StringVerb;
import dev.cobolonjava.runtime.verb.UnstringVerb;
import java.util.Arrays;
import java.util.List;
import dev.cobolonjava.runtime.verb.Move;

/**
 * 生成コードが呼ぶ入口 (方針 ARC-7)。
 *
 * <p>意味論そのものは {@link Move} などランタイムの動詞が持つ。ここにあるのは
 * <b>記憶域の位置と動詞をつなぐだけ</b>の薄い層である。生成コードを短く保ち、
 * バイトコードの誤りが入り込む余地を減らすために置いている。
 */
public final class Ops {

    private Ops() {
    }

    /** 記憶域の一部を取り出す。 */
    public static byte[] read(Storage storage, int offset, int length) {
        return storage.view(offset, length).toByteArray();
    }

    /** 英数字転記。 */
    public static void moveAlphanumeric(byte[] source, Storage storage, int offset, int length,
                                        boolean justifiedRight, CodePage codePage) {
        storage.view(offset, length)
                .setBytes(Move.alphanumeric(source, length, justifiedRight, codePage));
    }

    /** 数値転記。 */
    public static void moveNumeric(Decimal value, NumericItem target, Storage storage, int offset) {
        Move.numeric(value, target, storage.view(offset, target.byteLength()));
    }

    /** 数字編集項目への転記。 */
    public static void moveNumericEdited(Decimal value, Picture target, Storage storage, int offset,
                                         CodePage codePage) {
        Move.toNumericEdited(value, target, storage.view(offset, target.size()), codePage);
    }

    /** 数値項目の読み出し。 */
    public static Decimal readNumeric(NumericItem source, Storage storage, int offset) {
        DataView view = storage.view(offset, source.byteLength());
        return source.load(view);
    }

    /**
     * 繰り返しの回数として読む。
     *
     * <p>小数部は切り捨てる。{@code PERFORM n TIMES} の {@code n} は整数項目であることを
     * 規格が求めているため、切り捨てが起きるのは規格外のソースだけである。
     */
    public static int toInt(Decimal value) {
        return value.toBigDecimal().intValue();
    }

    /**
     * {@code STOP RUN} と {@code GOBACK}。実行を終える。
     *
     * <p>段落は別々のメソッドとして生成されるため、単に戻るだけでは呼び出し元へ
     * 制御が返ってしまう。<b>例外で一気に抜ける</b>ことで、どこから呼ばれていても
     * プログラムの実行そのものが終わる。
     */
    public static void stopRun() {
        throw new ProgramStop();
    }

    /**
     * {@code GOBACK} と手続き部の終わり。<b>呼んだ側へ戻る</b> (要件 FR-067)。
     *
     * <p>{@link #stopRun()} との違いはどこまで抜けるかである。副プログラムから投げれば
     * 呼んだ側が受け止めて続きを実行し、主プログラムなら実行の終わりになる。
     */
    public static void programReturn() {
        throw new ProgramReturn();
    }

    // ---- 表示 ----

    /**
     * {@code DISPLAY} の 1 項目。
     *
     * @param advancing 行を改めるかどうか
     */
    public static void display(byte[] bytes, ProgramContext context, boolean advancing,
                               boolean toError) {
        context.display(bytes, advancing, toError);
    }

    public static void display(byte[] bytes, ProgramContext context, boolean advancing) {
        context.display(bytes, advancing);
    }

    /**
     * 数値を表示の形へ直す。
     *
     * <p>{@code COMP} や {@code COMP-3} の項目をそのまま出しても読めない。
     * 同じ桁数・同じ小数部の {@code DISPLAY} 項目として符号化し直す。
     */
    public static byte[] displayForm(Decimal value, NumericItem shape) {
        return shape.encode(value);
    }

    // ---- STRING / UNSTRING ----

    /**
     * {@code STRING}。つないだ結果を受取項目へ書き、実行結果を返す。
     *
     * <p><b>受取項目の残りは埋めない</b>。書いた分だけが変わる。
     */
    public static StringVerb.Result string(Storage storage, int offset, int length, int pointer,
                                           StringVerb.Source... sources) {
        StringVerb.Result result = StringVerb.string(read(storage, offset, length), pointer,
                List.of(sources));
        storage.view(offset, length).setBytes(result.target());
        return result;
    }

    /** {@code UNSTRING}。転記は結果から取り出して行う。 */
    public static UnstringVerb.Result unstring(Storage storage, int offset, int length,
                                               int pointer, UnstringVerb.Delimiter[] delimiters,
                                               UnstringVerb.Field[] fields, CodePage codePage) {
        return UnstringVerb.unstring(read(storage, offset, length), pointer,
                List.of(delimiters), List.of(fields), codePage);
    }

    /** {@code UNSTRING} の受取項目 1 個。転記が行われなかった項目は変えない。 */
    public static void storeUnstringField(UnstringVerb.Result result, int index, Storage storage,
                                          int offset, int length) {
        if (index < result.fields().size()) {
            storage.view(offset, length).setBytes(result.fields().get(index));
        }
    }

    /** {@code DELIMITER IN} の受取項目。 */
    public static void storeUnstringDelimiter(UnstringVerb.Result result, int index,
                                              Storage storage, int offset, int length,
                                              boolean justifiedRight, CodePage codePage) {
        if (index < result.delimiters().size()) {
            moveAlphanumeric(result.delimiters().get(index), storage, offset, length,
                    justifiedRight, codePage);
        }
    }

    /** {@code COUNT IN} の受取項目。 */
    public static void storeUnstringCount(UnstringVerb.Result result, int index,
                                          NumericItem counter, Storage storage, int offset) {
        if (index < result.counts().size()) {
            storeInteger(result.counts().get(index), counter, storage, offset);
        }
    }

    /** 数値項目を {@code int} として読む。長さそのものが値である場面で使う。 */
    public static int readInteger(NumericItem source, Storage storage, int offset) {
        return readNumeric(source, storage, offset).toBigDecimal().intValue();
    }

    /** 整数を数値項目へ入れる。{@code POINTER} や {@code TALLYING} が使う。 */
    public static void storeInteger(int value, NumericItem target, Storage storage, int offset) {
        store(Decimal.of(value, 0), target, storage, offset, CobolRounding.TRUNCATION);
    }

    // ---- INSPECT ----

    /** 検査する範囲。指定のない側は {@code null} を渡す。 */
    public static Region region(byte[] after, byte[] before) {
        if (after == null && before == null) {
            return Region.whole();
        }
        if (before == null) {
            return Region.after(after);
        }
        return after == null ? Region.before(before) : Region.between(after, before);
    }

    /** 数える走査。返るのは句ごとの計数である。 */
    public static int[] tally(Storage storage, int offset, int length,
                              InspectScan.Clause... clauses) {
        return InspectScan.tally(read(storage, offset, length), clauses);
    }

    /** 置き換える走査。 */
    public static void replace(Storage storage, int offset, int length,
                               InspectScan.Clause... clauses) {
        storage.view(offset, length)
                .setBytes(InspectScan.replace(read(storage, offset, length), clauses));
    }

    /** {@code CONVERTING}。1 バイトずつの読み替えである。 */
    public static void convert(Storage storage, int offset, int length, byte[] from, byte[] to,
                               Region region) {
        storage.view(offset, length)
                .setBytes(Inspect.convert(read(storage, offset, length), from, to, region));
    }

    /** 数えた結果を計数の項目へ足し込む。 */
    public static void addTally(int count, NumericItem counter, Storage storage, int offset) {
        Decimal current = readNumeric(counter, storage, offset);
        store(current.add(Decimal.of(count, 0)), counter, storage, offset,
                CobolRounding.TRUNCATION);
    }

    // ---- 比較 ----

    /** 数値比較。内部表現と桁数の違いに影響されない。 */
    public static int compareNumeric(Decimal left, Decimal right) {
        return Compare.numeric(left, right);
    }

    /** 英数字比較。短いほうは空白で埋めて比べる。 */
    public static int compareAlphanumeric(byte[] left, byte[] right, CodePage codePage) {
        return Compare.alphanumeric(left, right, codePage);
    }

    // ---- ファイル入出力 ----

    /**
     * {@code OPEN} (要件 FR-102)。
     *
     * @return ファイル状態コードのバイト列。2 バイトである
     */
    public static byte[] open(ProgramContext context, String name, String ddName, int mode,
                              int organization, int format, int recordLength, boolean optional) {
        return status(context, context.file(name, ddName,
                Organization.values()[organization], RecordFormat.values()[format], recordLength)
                .open(OpenMode.values()[mode], optional));
    }

    /**
     * {@code READ} (要件 FR-102)。読んだレコードは記憶域へ直に書き込む。
     *
     * @return ファイル状態コード
     */
    public static byte[] read(ProgramContext context, String name, String ddName,
                              Storage storage, int offset, int length) {
        DataSet file = context.file(name, ddName);
        byte[] record = new byte[length];
        String status = file.read(record);
        if (FileStatus.succeeded(status)) {
            // 読めなかったときにレコード領域を触らないのは、前の内容が残る規則のためである。
            // 可変長では読めた分だけを入れる。その先の中身は規格上決まっていない (要件 FR-106)
            int copied = file.attributes().format() == RecordFormat.VARIABLE
                    ? file.lastLength()
                    : length;
            storage.view(offset, copied).setBytes(Arrays.copyOf(record, copied));
        }
        return status(context, status);
    }

    /**
     * {@code WRITE} (要件 FR-102, FR-106)。
     *
     * @param length  書き出す長さ。可変長では {@code DEPENDING ON} の項目の値である
     * @param minimum 宣言された下限
     * @param maximum 宣言された上限。レコード領域より長くはならない
     */
    public static byte[] write(ProgramContext context, String name, String ddName,
                               Storage storage, int offset, int length, int minimum,
                               int maximum) {
        int actual = clamp(length, minimum, maximum);
        return status(context, lengthChecked(
                context.file(name, ddName).write(read(storage, offset, actual)), actual, length));
    }

    /**
     * {@code REWRITE} (要件 FR-102)。直前に読んだレコードを書き換える。
     *
     * @return ファイル状態コード
     */
    public static byte[] rewrite(ProgramContext context, String name, String ddName,
                                 Storage storage, int offset, int length, int minimum,
                                 int maximum) {
        int actual = clamp(length, minimum, maximum);
        return status(context, lengthChecked(
                context.file(name, ddName).rewrite(read(storage, offset, actual)), actual, length));
    }

    /**
     * 宣言された範囲へ収める (要件 FR-106)。
     *
     * <p>{@code DEPENDING ON} の項目に範囲外の値が入っていることはありうる。そのまま使えば
     * <b>レコード領域の外を読み書きする</b>。収めたうえで、食い違ったことを状態コードで知らせる。
     */
    private static int clamp(int length, int minimum, int maximum) {
        return Math.max(minimum, Math.min(length, maximum));
    }

    /** 長さを収めたなら {@code 04} を立てる。成功しているときだけである。 */
    private static String lengthChecked(String status, int actual, int requested) {
        return actual != requested && FileStatus.succeeded(status)
                ? FileStatus.LENGTH_MISMATCH
                : status;
    }

    /**
     * 索引編成のファイルを開く (要件 FR-100, FR-101)。
     *
     * <p>鍵の場所を渡す。{@code keys} は 3 つずつの組であり、位置・長さ・重複を許すかの順に
     * 並ぶ。先頭の組が主鍵である。
     */
    public static byte[] openIndexed(ProgramContext context, String name, String ddName, int mode,
                                     int format, int recordLength, boolean optional, int[] keys) {
        List<IndexedDataSet.Key> described = new java.util.ArrayList<>();
        for (int at = 0; at + 2 < keys.length; at += 3) {
            described.add(new IndexedDataSet.Key(keys[at], keys[at + 1], keys[at + 2] != 0));
        }
        return status(context, context.file(name, ddName, RecordFormat.values()[format],
                recordLength, described).open(OpenMode.values()[mode], optional));
    }

    /**
     * 鍵で読む (要件 FR-101)。索引編成だけである。
     *
     * <p>鍵の値は<b>レコード領域の中にある</b>。プログラムが鍵の項目へ入れてから読む。
     *
     * @param keyIndex {@code 0} が主鍵、{@code 1} 以降が副鍵の並び順
     */
    public static byte[] readKey(ProgramContext context, String name, String ddName, int keyIndex,
                                 Storage storage, int keyOffset, int keyLength, int offset,
                                 int length) {
        IndexedDataSet file = indexed(context, name, ddName);
        byte[] record = new byte[length];
        String status = file.readKey(keyIndex, read(storage, keyOffset, keyLength), record);
        if (FileStatus.succeeded(status)) {
            int copied = file.attributes().format() == RecordFormat.VARIABLE
                    ? file.lastLength()
                    : length;
            storage.view(offset, copied).setBytes(Arrays.copyOf(record, copied));
        }
        return status(context, status);
    }

    /** 鍵で引いて書く (要件 FR-101)。鍵はレコードの中にある。 */
    public static byte[] writeKey(ProgramContext context, String name, String ddName,
                                  Storage storage, int offset, int length, int minimum,
                                  int maximum) {
        int actual = clamp(length, minimum, maximum);
        return status(context, lengthChecked(
                indexed(context, name, ddName).writeKey(read(storage, offset, actual)),
                actual, length));
    }

    /** 鍵で引いて書き換える (要件 FR-101)。 */
    public static byte[] rewriteKey(ProgramContext context, String name, String ddName,
                                    Storage storage, int offset, int length, int minimum,
                                    int maximum) {
        int actual = clamp(length, minimum, maximum);
        return status(context, lengthChecked(
                indexed(context, name, ddName).rewriteKey(read(storage, offset, actual)),
                actual, length));
    }

    /** 鍵で引いて消す (要件 FR-101)。 */
    public static byte[] deleteKey(ProgramContext context, String name, String ddName,
                                   Storage storage, int keyOffset, int keyLength) {
        return status(context, indexed(context, name, ddName)
                .deleteKey(read(storage, keyOffset, keyLength)));
    }

    /** 鍵で位置だけを決める (要件 FR-101)。 */
    public static byte[] startKey(ProgramContext context, String name, String ddName,
                                  int keyIndex, Storage storage, int keyOffset, int keyLength,
                                  int relation) {
        return status(context, indexed(context, name, ddName).start(keyIndex,
                read(storage, keyOffset, keyLength), KeyRelation.values()[relation]));
    }

    private static IndexedDataSet indexed(ProgramContext context, String name, String ddName) {
        return (IndexedDataSet) context.file(name, ddName);
    }

    /**
     * 番号で読む (要件 FR-101)。相対編成だけである。
     *
     * @param number 相対レコード番号 (1 起点)
     */
    public static byte[] readAt(ProgramContext context, String name, String ddName, int number,
                                Storage storage, int offset, int length) {
        RelativeDataSet file = relative(context, name, ddName);
        byte[] record = new byte[length];
        String status = file.readAt(number, record);
        if (FileStatus.succeeded(status)) {
            storage.view(offset, length).setBytes(record);
        }
        return status(context, status);
    }

    /** 番号を指定して書く (要件 FR-101)。 */
    public static byte[] writeAt(ProgramContext context, String name, String ddName, int number,
                                 Storage storage, int offset, int length, int minimum,
                                 int maximum) {
        int actual = clamp(length, minimum, maximum);
        return status(context, lengthChecked(
                relative(context, name, ddName).writeAt(number, read(storage, offset, actual)),
                actual, length));
    }

    /** 番号を指定して書き換える (要件 FR-101)。 */
    public static byte[] rewriteAt(ProgramContext context, String name, String ddName, int number,
                                   Storage storage, int offset, int length, int minimum,
                                   int maximum) {
        int actual = clamp(length, minimum, maximum);
        return status(context, lengthChecked(
                relative(context, name, ddName).rewriteAt(number, read(storage, offset, actual)),
                actual, length));
    }

    /** 直前に読んだレコードを消す (要件 FR-102)。鍵で引く編成だけである。 */
    public static byte[] delete(ProgramContext context, String name, String ddName) {
        return status(context, ((KeyedDataSet) context.file(name, ddName)).delete());
    }

    /** 番号を指定して消す (要件 FR-101)。 */
    public static byte[] deleteAt(ProgramContext context, String name, String ddName, int number) {
        return status(context, relative(context, name, ddName).deleteAt(number));
    }

    /**
     * 位置だけを決める (要件 FR-101)。
     *
     * @param relation {@link KeyRelation} の並び順
     */
    public static byte[] start(ProgramContext context, String name, String ddName, int number,
                               int relation) {
        return status(context, relative(context, name, ddName)
                .start(number, KeyRelation.values()[relation]));
    }

    /**
     * 直前に読んだレコードの相対レコード番号 (要件 FR-101)。
     *
     * <p>順次読みでは<b>読んでみるまで番号が決まらない</b>。空きスロットを飛ばすためである。
     * {@code RELATIVE KEY} の項目へ返す値がこれである。
     */
    public static int relativeNumber(ProgramContext context, String name, String ddName) {
        return relative(context, name, ddName).currentNumber();
    }

    /** 相対編成として引く。編成は翻訳時に決まっているので、ここは必ず当たる。 */
    private static RelativeDataSet relative(ProgramContext context, String name, String ddName) {
        return (RelativeDataSet) context.file(name, ddName);
    }

    /**
     * 直前に読み書きしたレコードの長さ (要件 FR-106)。
     *
     * <p>可変長では<b>長さそのものがデータである</b>。{@code RECORD IS VARYING ... DEPENDING ON}
     * の項目へ入れる値がこれである。
     */
    public static int recordLength(ProgramContext context, String name, String ddName) {
        return context.file(name, ddName).lastLength();
    }

    /** {@code CLOSE} (要件 FR-102)。 */
    public static byte[] close(ProgramContext context, String name, String ddName) {
        return status(context, context.file(name, ddName).close());
    }

    /**
     * ファイル状態コードをバイト列にする。
     *
     * <p>{@code FILE STATUS} の項目は<b>2 文字の英数字</b>である。数値ではない。
     * 拡張コードに数字でないものがあるためである。
     */
    private static byte[] status(ProgramContext context, String status) {
        return context.codePage().encode(status);
    }

    /**
     * ファイル状態コードが成功を表すか (要件 FR-103)。
     *
     * <p>先頭が {@code 0} なら成功か軽微な注意である。{@code 1} で始まれば
     * ファイルの終わりであり、それ以外は誤りである。
     */
    public static boolean fileSucceeded(byte[] status, CodePage codePage) {
        return FileStatus.succeeded(codePage.decode(status));
    }

    /** ファイルの終わりかどうか。{@code AT END} の分岐に使う。 */
    public static boolean fileAtEnd(byte[] status, CodePage codePage) {
        return FileStatus.AT_END.equals(codePage.decode(status));
    }

    /**
     * 鍵に関する誤りかどうか (要件 FR-103)。{@code INVALID KEY} の分岐に使う。
     *
     * <p>順編成の {@code AT END} にあたるものが、鍵で引く編成ではこれである。
     */
    public static boolean fileInvalidKey(byte[] status, CodePage codePage) {
        return FileStatus.invalidKey(codePage.decode(status));
    }

    /**
     * {@code FILE STATUS} を書いていないファイルで異常が起きたときの扱い (要件 FR-104)。
     *
     * <p>黙って続けると、<b>読めていないデータで処理が進む</b>。異常終了させる。
     *
     * <p>ただし文に受け止める句が書いてあれば、そちらへ分岐するのが正しい。
     * {@code AT END} を書いた {@code READ} でファイルの終わりに来るのは誤りではない。
     *
     * @param atEndHandled      文に {@code AT END} が書かれているか
     * @param invalidKeyHandled 文に {@code INVALID KEY} が書かれているか
     */
    public static void checkFile(byte[] status, CodePage codePage, String name,
                                 boolean atEndHandled, boolean invalidKeyHandled) {
        String text = codePage.decode(status);
        if (FileStatus.succeeded(text)
                || (atEndHandled && FileStatus.AT_END.equals(text))
                || (invalidKeyHandled && FileStatus.invalidKey(text))) {
            return;
        }
        throw new FileOperationException(name, text);
    }

    // ---- ACCEPT ----

    /**
     * 日付と時刻の特殊レジスタ (要件 FR-060、テスト時の固定は FR-204)。
     *
     * <p>返すのは<b>符号なし整数の表示形式</b>のバイト列である。受け取る項目が英数字なら
     * 数字がそのまま並び、数値なら {@link #asInteger} で整数として読まれる。
     */
    public static byte[] register(ProgramContext context, String form) {
        return SpecialRegisters.valueOf(SpecialRegisters.Form.valueOf(form), context.clock(),
                context.codePage());
    }

    /**
     * {@code ACCEPT} が端末から読む 1 行 (要件 FR-060)。
     *
     * <p>読んだ文字を実行時のコードページのバイト列へ直す。受け取る項目への詰め方は
     * 普通の転記と同じである。
     */
    public static byte[] acceptLine(ProgramContext context) {
        return context.codePage().encode(context.readLine());
    }

    /**
     * バイト列を符号なし整数として読む。
     *
     * <p>{@code ACCEPT} の送出側は<b>符号なし整数の表示形式</b>と決まっている。
     * 数値項目が受け取るときはこれを通す。
     */
    public static Decimal asInteger(byte[] bytes, CodePage codePage) {
        return ZonedDecimal.decode(bytes, 0, SignPosition.UNSIGNED, codePage, NumProcMode.NOPFD);
    }

    // ---- 副プログラムの呼び出し ----

    /**
     * {@code CALL} (要件 FR-080, FR-081)。
     *
     * <p>呼び先は名前ごとに 1 つだけ作って持ち続ける。COBOL では<b>副プログラムの
     * 作業場所は呼び出しをまたいで残る</b>ためである。
     *
     * <p>{@code GOBACK} と手続き部の終わりはここで受け止める。{@code STOP RUN} は
     * 受け止めない。<b>どこまで抜けるかが違う</b>のがこの 2 つの違いである。
     *
     * @param loader 呼ぶ側のクラスを読み込んだもの。生成クラスは同じところにある
     * @throws ProgramNotFoundException 呼び先が見つからない場合
     */
    public static void call(ProgramContext context, String name, ClassLoader loader,
                            DataView[] arguments) {
        ProgramContext.Loaded target = context.resolve(name, loader);
        try {
            target.program().run(target.storage(), context, arguments);
        } catch (ProgramReturn returned) {
            // 呼ばれた側が戻っただけである
        }
    }

    /** 動的な {@code CALL}。呼び先の名前をデータ項目から読む。 */
    public static void call(ProgramContext context, byte[] name, ClassLoader loader,
                            DataView[] arguments) {
        call(context, context.codePage().decode(name).trim(), loader, arguments);
    }

    /**
     * {@code CANCEL} (要件 FR-083)。
     *
     * <p>読み込んだ副プログラムを忘れる。次に呼ばれたときは<b>作業場所が初期状態から</b>
     * 始まる。呼んでいないプログラムを取り消しても誤りではない。
     */
    public static void cancel(ProgramContext context, String name) {
        context.forget(name);
    }

    /** 動的な {@code CANCEL}。取り消す名前をデータ項目から読む。 */
    public static void cancel(ProgramContext context, byte[] name) {
        cancel(context, context.codePage().decode(name).trim());
    }

    /**
     * {@code BY CONTENT} の引数。
     *
     * <p>写しを渡す。呼ばれた側が書き換えても<b>呼ぶ側には届かない</b>。
     * これが {@code BY REFERENCE} との違いである。
     */
    public static DataView byContent(Storage storage, int offset, int length) {
        return Storage.copyOf(read(storage, offset, length)).whole();
    }

    /** 翻訳時に決まったバイト列を {@code BY CONTENT} で渡す。 */
    public static DataView byContent(byte[] bytes) {
        return Storage.copyOf(bytes).whole();
    }

    /** {@code BY REFERENCE} の引数。呼ぶ側の領域をそのまま渡す。 */
    public static DataView byReference(Storage storage, int offset, int length) {
        return storage.view(offset, length);
    }

    /** 引数の並びを作る。 */
    public static DataView[] arguments(DataView... views) {
        return views;
    }

    // ---- SSRANGE の検査 ----

    /**
     * 添字が {@code 1..occurs} に収まっているか検査する (要件 FR-024)。
     *
     * <p>値をそのまま返すのは、位置の計算の<b>途中に挟める</b>ようにするためである。
     * 検査のために計算を組み替えると、指定がないときの命令列まで変わってしまう。
     *
     * @param name 診断に出す項目の名前
     * @return 渡された添字
     */
    public static int checkSubscript(int value, int occurs, String name) {
        if (value < 1 || value > occurs) {
            throw new RangeCheckException("subscript " + value + " is outside 1.." + occurs
                    + " for " + name);
        }
        return value;
    }

    /**
     * 部分参照が項目の中に収まっているか検査する (要件 FR-026)。
     *
     * @param leftmost 開始位置 (1 起点)
     * @param length   長さ
     * @param size     項目の長さ
     * @param name     診断に出す項目の名前
     * @return 渡された開始位置
     */
    public static int checkRefMod(int leftmost, int length, int size, String name) {
        if (leftmost < 1 || leftmost > size) {
            throw new RangeCheckException("reference modification starts at " + leftmost
                    + " which is outside 1.." + size + " for " + name);
        }
        if (length < 1 || leftmost + length - 1 > size) {
            throw new RangeCheckException("reference modification of length " + length
                    + " at " + leftmost + " runs past the end of " + name
                    + " (" + size + " bytes)");
        }
        return leftmost;
    }

    // ---- 算術 ----

    public static Decimal add(Decimal left, Decimal right) {
        return left.add(right);
    }

    public static Decimal subtract(Decimal left, Decimal right) {
        return left.subtract(right);
    }

    public static Decimal multiply(Decimal left, Decimal right) {
        return left.multiply(right);
    }

    /**
     * 除算。
     *
     * <p>商の桁数は<b>受取項目の小数部に合わせる</b>。除算だけは結果の桁数が
     * 被演算子から決まらないため、受取側を見て決めるほかない。
     */
    public static Decimal divide(Decimal dividend, Decimal divisor, int scale,
                                 CobolRounding rounding) {
        return Arithmetic.divide(dividend, divisor, scale, rounding);
    }

    public static Decimal negate(Decimal value) {
        return value.negate();
    }

    /**
     * 中間結果を指定の小数桁へ切り捨てる (要件 FR-047)。
     *
     * <p>中間結果の総桁数が上限を超えたときにだけ呼ばれる。丸めるのは受取項目へ
     * 格納する最後の 1 回だけであり、途中は切り捨てる。
     */
    public static Decimal truncate(Decimal value, int scale) {
        return value.rescale(scale, CobolRounding.TRUNCATION);
    }

    /**
     * {@code DIVIDE ... REMAINDER} の剰余 (要件 FR-044)。
     *
     * <p>剰余は<b>切り捨てた商</b>から求める。{@code ROUNDED} を書いても、剰余の計算に
     * 使う商は丸めない。丸めた商から求めると、商と剰余を足し戻したときに元の値にならない。
     *
     * @param quotientScale 商を受け取る項目の小数桁。ここで商を切り捨てる
     */
    public static Decimal remainder(Decimal dividend, Decimal divisor, int quotientScale) {
        return dividend.remainder(divisor, quotientScale);
    }

    /** 除数が 0 かどうか。{@code ON SIZE ERROR} つきの除算で、割る前に見る。 */
    public static boolean isZero(Decimal value) {
        return value.isZero();
    }

    /**
     * {@code ON SIZE ERROR} つきの格納。
     *
     * <p>桁に収まらなければ<b>受取項目を変えず</b>に {@code true} を返す。
     * 指定がないときとの違いは、あふれたときに受取項目へ何が残るかである。
     */
    public static boolean storeChecked(Decimal value, NumericItem target, Storage storage,
                                       int offset, CobolRounding rounding) {
        return Arithmetic.storeChecked(target, storage.view(offset, target.byteLength()),
                value, rounding);
    }

    /** 算術文の結果を受取項目へ格納する。上位桁は黙って切り捨てられる。 */
    public static void store(Decimal value, NumericItem target, Storage storage, int offset,
                             CobolRounding rounding) {
        Arithmetic.store(target, storage.view(offset, target.byteLength()), value, rounding);
    }

    /**
     * 英数字項目を数値として読む。
     *
     * <p>参照実装は英数字項目から数値項目への転記で、送出側を<b>符号なしの整数</b>として
     * 扱う。ゾーン 10 進の復号をそのまま使う。
     */
    public static Decimal readAsInteger(Storage storage, int offset, int length,
                                        CodePage codePage) {
        return ZonedDecimal.decode(read(storage, offset, length), 0,
                SignPosition.UNSIGNED, codePage, NumProcMode.NOPFD);
    }
}
