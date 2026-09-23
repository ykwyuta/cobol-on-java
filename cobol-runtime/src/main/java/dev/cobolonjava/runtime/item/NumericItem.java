package dev.cobolonjava.runtime.item;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.config.UndefinedBehavior;
import dev.cobolonjava.runtime.data.BinaryDecimal;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.data.TruncMode;
import dev.cobolonjava.runtime.data.ZonedDecimal;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.picture.Picture;
import dev.cobolonjava.runtime.picture.PictureParser;
import dev.cobolonjava.runtime.storage.DataView;

/**
 * PICTURE と {@code USAGE} を束ねた数値項目の記述子。
 *
 * <p>「バイト列 in - バイト列 out」の純関数として符号化・復号を提供する (要件 ARC-7)。
 * 状態を持たないため、Hercules から採取した V2 期待値を直接ぶつけて検証できる。
 */
public final class NumericItem {

    private final Picture picture;
    private final Usage usage;
    private final SignPosition signPosition;
    private final TruncMode truncMode;
    private final NumProcMode numProcMode;
    private final UndefinedBehavior undefinedBehavior;
    private final CodePage codePage;

    private NumericItem(Picture picture, Usage usage, SignPosition signPosition, TruncMode truncMode,
                        NumProcMode numProcMode, UndefinedBehavior undefinedBehavior, CodePage codePage) {
        if (!picture.isNumeric()) {
            throw new IllegalArgumentException("not a numeric picture: " + picture);
        }
        if (usage.isFloatingPoint()) {
            // 浮動小数点項目は PICTURE を持たない。FloatingItem を使うこと
            throw new IllegalArgumentException(
                    "floating-point usage " + usage + " has no PICTURE; use FloatingItem instead");
        }
        if (usage == Usage.NATIONAL) {
            // 国字の数字 (PIC 9 USAGE NATIONAL) はまだ持たない (P-006)
            throw new IllegalArgumentException("national numeric items are not supported yet");
        }
        this.picture = picture;
        this.usage = usage;
        this.signPosition = signPosition;
        this.truncMode = truncMode;
        this.numProcMode = numProcMode;
        this.undefinedBehavior = undefinedBehavior;
        this.codePage = codePage;
    }

    /** 既定 (TRUNC(STD)、NUMPROC(NOPFD)、安全側、IBM-1047) の項目を作る。 */
    public static NumericItem of(String pictureString, Usage usage) {
        return of(pictureString, usage, PictureParser.DEFAULT_CURRENCY);
    }

    /**
     * 通貨記号を指定して項目を作る (要件 FR-054)。
     *
     * <p>{@code CURRENCY SIGN IS} で差し替えられていると、PICTURE の解釈が変わる。
     * 生成コードは翻訳時の指定をここへ渡す。
     */
    public static NumericItem of(String pictureString, Usage usage, char currency) {
        return of(pictureString, usage, currency, PictureParser.DEFAULT_DECIMAL_POINT);
    }

    /** 小数点の文字まで決めて作る。{@code DECIMAL-POINT IS COMMA} が使う。 */
    public static NumericItem of(String pictureString, Usage usage, char currency,
                                 char decimalPoint) {
        Picture p = PictureParser.parse(pictureString, currency, decimalPoint);
        return new NumericItem(p, usage, p.signPosition(), TruncMode.STD, NumProcMode.NOPFD,
                UndefinedBehavior.SAFE, CodePages.DEFAULT);
    }

    public NumericItem withSignPosition(SignPosition v) {
        return new NumericItem(picture, usage, v, truncMode, numProcMode, undefinedBehavior, codePage);
    }

    public NumericItem withTruncMode(TruncMode v) {
        return new NumericItem(picture, usage, signPosition, v, numProcMode, undefinedBehavior, codePage);
    }

    public NumericItem withNumProcMode(NumProcMode v) {
        return new NumericItem(picture, usage, signPosition, truncMode, v, undefinedBehavior, codePage);
    }

    public NumericItem withUndefinedBehavior(UndefinedBehavior v) {
        return new NumericItem(picture, usage, signPosition, truncMode, numProcMode, v, codePage);
    }

    public NumericItem withCodePage(CodePage v) {
        return new NumericItem(picture, usage, signPosition, truncMode, numProcMode, undefinedBehavior, v);
    }

    /** 符号の置き場。 */
    public SignPosition signPosition() {
        return signPosition;
    }

    public Picture picture() {
        return picture;
    }

    public Usage usage() {
        return usage;
    }

    /** この項目が占めるバイト長。 */
    public int byteLength() {
        return switch (usage) {
            case DISPLAY -> ZonedDecimal.byteLength(picture.digits(), signPosition);
            case COMP_3 -> PackedDecimal.byteLength(picture.digits());
            case COMP, COMP_5 -> BinaryDecimal.byteLength(picture.digits());
            case COMP_1, COMP_2, NATIONAL -> throw new IllegalStateException(
                    "floating-point and national usages are rejected by the constructor");
        };
    }

    /**
     * 値をこの項目の外部表現へ符号化する。
     *
     * <p>桁があふれた場合、上位桁は黙って切り捨てられる。これは {@code ON SIZE ERROR} を
     * 指定しない場合にホストで実際に起きる挙動である。SIZE ERROR 条件を立てたい呼び出し側は
     * 事前に {@link #fits(Decimal)} で判定する (要件 FR-043)。
     */
    public byte[] encode(Decimal value) {
        // 符号を持たない受取項目には<b>絶対値</b>が入る。規格がそう決めている。
        // 符号を残すと、-70717 を PIC 9(9) COMP へ移したときに負のまま読み戻される
        // (NC105A の MOVE-TEST-F1-114「MOVE TO COMP (ABS)」がそこだけを確かめている)
        if (!signPosition.isSigned() && value.signum() < 0) {
            value = value.negate();
        }
        return switch (usage) {
            case DISPLAY -> ZonedDecimal.encode(value, picture.digits(), picture.scale(),
                    signPosition, codePage);
            case COMP_3 -> PackedDecimal.encode(value, picture.digits(), picture.scale(),
                    signPosition.isSigned());
            case COMP -> BinaryDecimal.encode(value, picture.digits(), picture.scale(),
                    truncMode.resolve(undefinedBehavior));
            case COMP_5 -> BinaryDecimal.encode(value, picture.digits(), picture.scale(),
                    TruncMode.BIN);
            case COMP_1, COMP_2, NATIONAL -> throw new IllegalStateException(
                    "floating-point and national usages are rejected by the constructor");
        };
    }

    /** この項目の外部表現を値へ復号する。 */
    public Decimal decode(byte[] bytes) {
        if (bytes.length != byteLength()) {
            throw new IllegalArgumentException(
                    "length mismatch: item=" + byteLength() + ", bytes=" + bytes.length);
        }
        return switch (usage) {
            case DISPLAY -> ZonedDecimal.decode(bytes, picture.scale(), signPosition, codePage, numProcMode);
            case COMP_3 -> PackedDecimal.decode(bytes, picture.scale(), numProcMode);
            case COMP, COMP_5 -> BinaryDecimal.decode(bytes, picture.scale());
            case COMP_1, COMP_2, NATIONAL -> throw new IllegalStateException(
                    "floating-point and national usages are rejected by the constructor");
        };
    }

    /** 値がこの項目に桁落ちなく収まるかどうか (要件 FR-043 の SIZE ERROR 判定)。 */
    public boolean fits(Decimal value) {
        return value.fitsInDigits(picture.digits(), picture.scale());
    }

    public void store(DataView view, Decimal value) {
        view.setBytes(encode(value));
    }

    public Decimal load(DataView view) {
        return decode(view.toByteArray());
    }

    @Override
    public String toString() {
        return "NumericItem[" + picture.source() + " " + usage + " " + signPosition
                + " " + byteLength() + " bytes]";
    }
}
