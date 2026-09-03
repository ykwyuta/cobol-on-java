package dev.cobolonjava.compiler.source;

/**
 * 固定形式と自由形式で共通する、行の文字列を扱う処理 (要件 FR-002)。
 *
 * <p>どちらの形式でも<b>文字定数の中と外を取り違えないこと</b>が要になる。
 * 注釈の切り出しも、継続の判定も、そこを間違えると黙って別のソースになる。
 */
final class SourceText {

    /** 行内注釈の始まり。以降その行の終わりまでが注釈になる。 */
    static final String INLINE_COMMENT = "*>";

    private SourceText() {
    }

    /**
     * 行の末尾の時点で開いたままの引用符を返す。閉じていれば 0。
     *
     * <p>定数の中の引用符 2 個は 1 個の引用符を表すため、定数は閉じない。
     */
    static char openQuoteAtEnd(String content, char initialQuote) {
        char quote = initialQuote;
        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (quote == 0) {
                if (c == '\'' || c == '"') {
                    quote = c;
                }
                i++;
            } else if (c == quote) {
                if (i + 1 < content.length() && content.charAt(i + 1) == quote) {
                    // 引用符 2 個は定数の中の引用符 1 個を表す
                    i += 2;
                } else {
                    quote = 0;
                    i++;
                }
            } else {
                i++;
            }
        }
        return quote;
    }

    /**
     * 行内注釈 {@code *>} 以降を落とす。
     *
     * <p>文字定数の中の {@code *>} は注釈ではない。{@code MOVE '*>' TO X} を
     * 注釈として落としてしまうと、黙って別のソースになる。
     *
     * @param initialQuote 行の頭で開いたままの引用符。0 なら定数の外
     */
    static String stripInlineComment(String content, char initialQuote) {
        char quote = initialQuote;
        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (quote == 0) {
                if (c == '\'' || c == '"') {
                    quote = c;
                    i++;
                    continue;
                }
                if (c == '*' && i + 1 < content.length() && content.charAt(i + 1) == '>') {
                    return content.substring(0, i);
                }
                i++;
            } else if (c == quote) {
                if (i + 1 < content.length() && content.charAt(i + 1) == quote) {
                    i += 2;
                } else {
                    quote = 0;
                    i++;
                }
            } else {
                i++;
            }
        }
        return content;
    }

    static int countLeadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    /** 最後の非空白文字の位置。空白だけなら -1。 */
    static int lastNonBlankIndex(String s) {
        int i = s.length() - 1;
        while (i >= 0 && s.charAt(i) == ' ') {
            i--;
        }
        return i;
    }
}
