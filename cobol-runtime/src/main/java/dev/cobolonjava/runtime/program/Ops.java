package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.data.ZonedDecimal;
import dev.cobolonjava.runtime.decimal.CobolRounding;
import dev.cobolonjava.runtime.decimal.Decimal;
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

    // ---- 表示 ----

    /**
     * {@code DISPLAY} の 1 項目。
     *
     * @param advancing 行を改めるかどうか
     */
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
