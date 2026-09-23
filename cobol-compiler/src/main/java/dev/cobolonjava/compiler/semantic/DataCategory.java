package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.runtime.picture.Picture;

/**
 * データの分類 (要件 FR-030, FR-060)。
 *
 * <p>COBOL の分類は PICTURE から決まるが、転記の規則では<b>整数かどうか</b>で
 * さらに分かれる。したがって数値を 2 つに割ってある。
 */
public enum DataCategory {

    /** 英字。{@code PIC A} だけで書かれた項目。 */
    ALPHABETIC,
    /** 英数字。{@code PIC X} を含む項目と、定数。 */
    ALPHANUMERIC,
    /** 英数字編集。{@code PIC X} に挿入文字が混ざった項目。 */
    ALPHANUMERIC_EDITED,
    /** 整数の数値。小数部を持たない。 */
    NUMERIC_INTEGER,
    /** 小数部を持つ数値。 */
    NUMERIC_NONINTEGER,
    /** 数字編集。{@code PIC ZZ9.99} のような項目。 */
    NUMERIC_EDITED,
    /** 集団項目。転記では英数字として扱われるが、<b>変換を一切行わない</b>点が違う。 */
    GROUP,
    /**
     * 国字。{@code PIC N} の項目と {@code N'..'} {@code NX'..'} の定数。1 文字が UTF-16 の
     * 2 バイトを占める。英数字とは<b>バイトの意味が違う</b>ので、英数字のように扱ってはならない
     * ({@link #isAlphanumericLike} は偽)。
     */
    NATIONAL;

    /** 数値として扱う分類かどうか。 */
    public boolean isNumeric() {
        return this == NUMERIC_INTEGER || this == NUMERIC_NONINTEGER;
    }

    /** 英数字として扱う分類かどうか。 */
    public boolean isAlphanumericLike() {
        return this == ALPHABETIC || this == ALPHANUMERIC || this == ALPHANUMERIC_EDITED
                || this == GROUP;
    }

    /**
     * データ参照の分類。
     *
     * <p><b>部分参照を書いた項目は英数字になる</b>。数字項目の一部を切り出しても、
     * それは数値ではなくバイトの並びである。
     */
    public static DataCategory of(DataReference reference) {
        if (reference.refMod() != null) {
            return ALPHANUMERIC;
        }
        return of(reference.item());
    }

    /** 項目の分類。 */
    public static DataCategory of(DataItem item) {
        if (!item.isElementary()) {
            return GROUP;
        }
        if (item.picture() == null) {
            // PICTURE を持たない COMP-1 / COMP-2 は小数を持ちうる
            return NUMERIC_NONINTEGER;
        }
        Picture picture = item.picture();
        return switch (picture.category()) {
            case ALPHABETIC -> ALPHABETIC;
            case ALPHANUMERIC -> ALPHANUMERIC;
            case ALPHANUMERIC_EDITED -> ALPHANUMERIC_EDITED;
            case NUMERIC_EDITED -> NUMERIC_EDITED;
            case NUMERIC -> picture.scale() > 0 ? NUMERIC_NONINTEGER : NUMERIC_INTEGER;
            case NATIONAL -> NATIONAL;
        };
    }

    /**
     * 定数の分類。
     *
     * @param numericReceiver 受取側が数値として扱われるかどうか。図形定数 {@code ZERO} は
     *                        受取側に合わせて数値にも文字にもなるため、これが要る
     */
    public static DataCategory of(LiteralValue value, boolean numericReceiver) {
        if (value instanceof LiteralValue.National) {
            return NATIONAL;
        }
        if (value instanceof LiteralValue.Number number) {
            return number.value().scale() > 0 ? NUMERIC_NONINTEGER : NUMERIC_INTEGER;
        }
        if (value instanceof LiteralValue.Figure figure) {
            return switch (figure.constant()) {
                // ZERO だけが数値にも文字にもなる。受取側に合わせる
                case ZERO -> numericReceiver ? NUMERIC_INTEGER : ALPHANUMERIC;
                // SPACE の分類は英字である。だから数値項目へは移せない
                case SPACE -> ALPHABETIC;
                default -> ALPHANUMERIC;
            };
        }
        return ALPHANUMERIC;
    }
}
