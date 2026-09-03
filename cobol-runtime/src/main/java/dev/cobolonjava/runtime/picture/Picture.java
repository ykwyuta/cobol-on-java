package dev.cobolonjava.runtime.picture;

import dev.cobolonjava.runtime.data.SignPosition;
import java.util.List;

/**
 * 解析済みの PICTURE 句 (要件 FR-030)。
 *
 * <p>{@link #cells()} は、項目が占める各バイト位置がどのような役割を持つかを左から順に並べたものである。
 * 数値編集 ({@link NumericEditor}) はこの列を走査して出力を組み立てる。ホストではこの処理が
 * {@code ED} / {@code EDMK} 命令として実現されており、編集結果のバイト列は L3 互換の対象である。
 */
public final class Picture {

    public enum Category {
        ALPHABETIC, ALPHANUMERIC, ALPHANUMERIC_EDITED, NUMERIC, NUMERIC_EDITED
    }

    /** バイト位置 1 個 (または {@code CR} / {@code DB} の 2 個) の役割。 */
    public enum Kind {
        /** {@code 9}。数字を 1 桁消費し、常に表示する。 */
        DIGIT,
        /** {@code Z} または {@code *}。数字を 1 桁消費し、上位の 0 は抑制される。 */
        SUPPRESS,
        /** 浮動挿入 ({@code $} {@code +} {@code -} の 2 個以上の並び)。先頭の 1 個は数字を消費しない。 */
        FLOAT,
        /** 単純挿入 ({@code ,} {@code /} {@code 0} {@code B}) および固定の通貨記号。 */
        INSERT,
        /** 小数点 {@code .}。 */
        DECIMAL_POINT,
        /** 固定の符号 {@code +} または {@code -}。 */
        SIGN,
        /** {@code CR}。2 バイトを占める。 */
        CR,
        /** {@code DB}。2 バイトを占める。 */
        DB,
        /** {@code A}。英字 1 バイト。 */
        ALPHA,
        /** {@code X}。英数字 1 バイト。 */
        ALNUM
    }

    /**
     * @param kind    役割
     * @param literal 出力する文字、または抑制時の充填文字
     * @param width   占めるバイト数
     */
    public record Cell(Kind kind, char literal, int width) {
    }

    private final String source;
    private final String expanded;
    private final Category category;
    private final int size;
    private final int digits;
    private final int scale;
    private final SignPosition signPosition;
    private final boolean blankWhenZero;
    private final List<Cell> cells;

    Picture(String source, String expanded, Category category, int size, int digits, int scale,
            SignPosition signPosition, boolean blankWhenZero, List<Cell> cells) {
        this.source = source;
        this.expanded = expanded;
        this.category = category;
        this.size = size;
        this.digits = digits;
        this.scale = scale;
        this.signPosition = signPosition;
        this.blankWhenZero = blankWhenZero;
        this.cells = List.copyOf(cells);
    }

    /** 元の PICTURE 文字列。 */
    public String source() {
        return source;
    }

    /** 繰り返し指定 {@code 9(5)} を展開した PICTURE 文字列。 */
    public String expanded() {
        return expanded;
    }

    public Category category() {
        return category;
    }

    /** {@code USAGE DISPLAY} のときに項目が占めるバイト数。 */
    public int size() {
        return size;
    }

    /** 実際に格納される数字の桁数。{@code P} は格納されないため含まない。 */
    public int digits() {
        return digits;
    }

    /**
     * 小数部の桁数。{@code 値 = 格納数字 x 10^-scale} が成り立つ。
     * {@code P} が末尾にある場合は負値になりうる。
     */
    public int scale() {
        return scale;
    }

    public SignPosition signPosition() {
        return signPosition;
    }

    public boolean blankWhenZero() {
        return blankWhenZero;
    }

    public List<Cell> cells() {
        return cells;
    }

    public boolean isNumeric() {
        return category == Category.NUMERIC;
    }

    public boolean isNumericEdited() {
        return category == Category.NUMERIC_EDITED;
    }

    /** {@code SIGN IS} 句による符号位置の指定を反映した新しい PICTURE を返す。 */
    public Picture withSignPosition(SignPosition newSignPosition) {
        if (!signPosition.isSigned() && newSignPosition.isSigned()) {
            throw new IllegalArgumentException(
                    "SIGN clause requires an S in the PICTURE character-string: " + source);
        }
        int newSize = signPosition.isSigned() && !isNumericEdited()
                ? digits + (newSignPosition.isSeparate() ? 1 : 0)
                : size;
        return new Picture(source, expanded, category, newSize, digits, scale,
                newSignPosition, blankWhenZero, cells);
    }

    /** {@code BLANK WHEN ZERO} 句を反映した新しい PICTURE を返す。 */
    public Picture withBlankWhenZero(boolean value) {
        return new Picture(source, expanded, category, size, digits, scale,
                signPosition, value, cells);
    }

    @Override
    public String toString() {
        return "Picture[" + source + " " + category + " size=" + size
                + " digits=" + digits + " scale=" + scale + "]";
    }
}
