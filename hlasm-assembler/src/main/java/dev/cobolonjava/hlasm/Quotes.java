package dev.cobolonjava.hlasm;

/**
 * 引用符の読み分け。
 *
 * <p>HLASM では {@code '} が 2 つの意味を持つ。文字列を囲む引用符と、属性参照 ({@code L'記号}) の
 * 印である。{@code DC A(L'FIELD)} を前者と読むと、開いたままの引用符として原文全体が壊れる。
 *
 * <p>属性の印と読むのは、文字列の<b>外</b>にいて、直前が属性の文字であり、そのさらに前が名前の
 * 文字でなく、後ろが記号の頭になれる字のときだけである。
 * <ul>
 *   <li>文字列の中の {@code '} はすべて引用符である。{@code C'L''A'} の {@code L''} は属性ではない
 *       (暫定判断 P-171。以前は文字列の中でも属性と読み、この定数を断っていた)</li>
 *   <li>前が名前の文字なら属性ではない。{@code DC 0D'0'} の {@code D} を属性と読まないため</li>
 *   <li>後ろが数字や符号なら属性ではない。{@code DC L'0.1'} は長さ属性ではなく L 型の定数である
 *       (記号は数字から始まらない)</li>
 * </ul>
 *
 * <p>扱う属性は長さ {@code L'} だけである。{@code K'} {@code N'} {@code T'} は条件付きアセンブリの
 * ものであり、増分 2 で入れる。
 */
final class Quotes {

    private Quotes() {
    }

    /**
     * {@code index} の {@code '} が、文字列を囲む引用符かどうか。
     *
     * @param quoted {@code index} の手前で文字列の中にいるかどうか
     */
    static boolean isDelimiter(CharSequence text, int index, boolean quoted) {
        if (quoted || index == 0) {
            return true;
        }
        char previous = text.charAt(index - 1);
        if (previous != 'L' && previous != 'l') {
            return true;
        }
        if (index >= 2 && Expressions.isNamePart(text.charAt(index - 2))) {
            return true;
        }
        if (index + 1 < text.length()) {
            char next = text.charAt(index + 1);
            if (!Character.isLetter(next) && next != '@' && next != '#' && next != '$'
                    && next != '_' && next != '*') {
                return true;
            }
        }
        return false;
    }
}
