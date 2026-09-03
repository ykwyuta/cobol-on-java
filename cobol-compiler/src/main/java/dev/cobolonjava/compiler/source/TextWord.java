package dev.cobolonjava.compiler.source;

/**
 * プリプロセッサが扱う字句 1 個 (要件 FR-090, FR-091)。
 *
 * <p>{@code COPY ... REPLACING} と {@code REPLACE} の照合は<b>語単位</b>で行われ、
 * 語と語の間の空白の数は照合に影響しない。したがってプリプロセッサは文字列ではなく
 * この字句の列を扱う。
 *
 * <p>文字ごとの出自 ({@link Origin}) を保持しているのは、継続行をまたいだ語や
 * コピー句から来た語でも、正しい位置を報告できるようにするためである (要件 FR-094)。
 *
 * <p>{@link #precededBySpace()} を持つのは、再構成の際に空白の有無を復元するためである。
 * これがないと {@code PIC 9(5)} が {@code PIC 9 ( 5 )} になってしまう。
 */
public final class TextWord {

    private final String text;
    private final Origin[] origins;
    private final TextWordKind kind;
    private final boolean precededBySpace;

    public TextWord(String text, Origin[] origins, TextWordKind kind, boolean precededBySpace) {
        if (origins.length != text.length()) {
            throw new IllegalArgumentException(
                    "origin count " + origins.length + " does not match text length " + text.length());
        }
        this.text = text;
        this.origins = origins;
        this.kind = kind;
        this.precededBySpace = precededBySpace;
    }

    public String text() {
        return text;
    }

    public TextWordKind kind() {
        return kind;
    }

    /** 直前に空白があったかどうか。再構成のときに空白を復元するために用いる。 */
    public boolean precededBySpace() {
        return precededBySpace;
    }

    /** 先頭の文字の出自。 */
    public Origin origin() {
        return origins[0];
    }

    public Origin originAt(int index) {
        return origins[index];
    }

    /** 空白の有無だけを差し替えた字句を返す。置換結果を差し込む際に用いる。 */
    public TextWord withPrecededBySpace(boolean value) {
        return new TextWord(text, origins, kind, value);
    }

    /**
     * 照合。COBOL 語は大文字と小文字を区別せず、文字定数と区切り文字は区別する。
     */
    public boolean matches(TextWord other) {
        if (kind != other.kind) {
            return false;
        }
        return kind == TextWordKind.WORD
                ? text.equalsIgnoreCase(other.text)
                : text.equals(other.text);
    }

    /** この字句が指定した COBOL 語かどうか。 */
    public boolean isWord(String word) {
        return kind == TextWordKind.WORD && text.equalsIgnoreCase(word);
    }

    /** この字句が指定した区切り文字かどうか。 */
    public boolean isSeparator(char c) {
        return kind == TextWordKind.SEPARATOR && text.length() == 1 && text.charAt(0) == c;
    }

    @Override
    public String toString() {
        return kind + "(" + text + ")@" + origin();
    }
}
