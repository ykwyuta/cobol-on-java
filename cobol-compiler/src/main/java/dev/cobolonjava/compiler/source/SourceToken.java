package dev.cobolonjava.compiler.source;

import java.util.Locale;

/**
 * 構文解析器へ渡すトークン 1 個 (方針 ARC-8)。
 *
 * <p>{@link TextWord} と同じく<b>文字ごとの出自</b>を保持する。島の中身は専用の小さな
 * パーサが解析するため、そのパーサが報告する誤りも元のファイル・行・桁へ戻せる必要がある。
 * 先頭の位置だけでは、{@code PIC S9(5)V99} の {@code V} を指せない。
 */
public final class SourceToken {

    private final SourceTokenKind kind;
    private final String text;
    private final Origin[] origins;

    public SourceToken(SourceTokenKind kind, String text, Origin[] origins) {
        if (origins.length != text.length()) {
            throw new IllegalArgumentException(
                    "origin count " + origins.length + " does not match text length " + text.length());
        }
        this.kind = kind;
        this.text = text;
        this.origins = origins;
    }

    public SourceTokenKind kind() {
        return kind;
    }

    public String text() {
        return text;
    }

    /** 先頭の文字の出自。 */
    public Origin origin() {
        return origins[0];
    }

    /** 指定位置の文字の出自。島の中身を解析するパーサが誤りを指すために用いる。 */
    public Origin originAt(int index) {
        return origins[index];
    }

    public int length() {
        return text.length();
    }

    /** この字句が指定した COBOL 語かどうか。大文字と小文字は区別しない。 */
    public boolean isWord(String word) {
        return kind == SourceTokenKind.WORD && text.equalsIgnoreCase(word);
    }

    public boolean isSeparator(char c) {
        return kind == SourceTokenKind.SEPARATOR && text.length() == 1 && text.charAt(0) == c;
    }

    /**
     * {@code EXEC} ブロックを処理する言語の名前 ({@code SQL} {@code CICS} など)。
     * 大文字に正規化して返す。どのトランスレータへ渡すかの振り分けに用いる。
     *
     * @throws IllegalStateException {@code EXEC} ブロック以外に対して呼んだ場合
     */
    public String execProcessor() {
        if (kind != SourceTokenKind.EXEC_BLOCK) {
            throw new IllegalStateException("not an EXEC block: " + this);
        }
        int start = "EXEC".length();
        while (start < text.length() && text.charAt(start) == ' ') {
            start++;
        }
        int end = start;
        while (end < text.length() && text.charAt(end) != ' ') {
            end++;
        }
        return text.substring(start, end).toUpperCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return kind + "(" + text + ")@" + origin();
    }
}
