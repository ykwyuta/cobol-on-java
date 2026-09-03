package dev.cobolonjava.compiler.source;

/**
 * 固定形式の標識領域 (7 桁目) の値 (要件 FR-003)。
 */
public enum LineIndicator {

    /** 空白。通常の行。 */
    NORMAL,
    /** {@code *}。注釈行。 */
    COMMENT,
    /** {@code /}。改ページを伴う注釈行。 */
    EJECT,
    /** {@code -}。継続行。 */
    CONTINUATION,
    /** {@code D} または {@code d}。デバッグ行。{@code WITH DEBUGGING MODE} のときだけ有効になる。 */
    DEBUG;

    /**
     * 標識領域の文字から種別を求める。
     *
     * @throws SourceFormatException 標識として妥当でない文字の場合
     */
    public static LineIndicator of(char c) {
        return switch (c) {
            case ' ' -> NORMAL;
            case '*' -> COMMENT;
            case '/' -> EJECT;
            case '-' -> CONTINUATION;
            case 'D', 'd' -> DEBUG;
            default -> throw new SourceFormatException(
                    "invalid indicator character '" + c + "' in column 7");
        };
    }
}
