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
