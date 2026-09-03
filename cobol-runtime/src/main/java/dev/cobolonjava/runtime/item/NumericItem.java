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
        Picture p = PictureParser.parse(pictureString);
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
        return switch (usage) {
            case DISPLAY -> ZonedDecimal.encode(value, picture.digits(), picture.scale(),
                    signPosition, codePage);
            case COMP_3 -> PackedDecimal.encode(value, picture.digits(), picture.scale(),
                    signPosition.isSigned());
            case COMP -> BinaryDecimal.encode(value, picture.digits(), picture.scale(),
                    truncMode.resolve(undefinedBehavior));
            case COMP_5 -> BinaryDecimal.encode(value, picture.digits(), picture.scale(),
                    TruncMode.BIN);
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
