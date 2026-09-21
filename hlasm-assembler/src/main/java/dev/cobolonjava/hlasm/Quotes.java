package dev.cobolonjava.hlasm;

/**
 * 引用符の読み分け。
 *
 * <p>HLASM では {@code '} が 2 つの意味を持つ。文字列を囲む引用符と、属性参照 ({@code L'記号}) の
 * 印である。{@code DC A(L'FIELD)} を前者と読むと、開いたままの引用符として原文全体が壊れる。
 *
 * <p>属性の印と読むのは、直前が属性の文字であり、<b>そのさらに前が名前の文字でない</b>ときだけである。
 * 後半の条件がないと {@code DC 0D'0'} の {@code D} を属性と読んでしまう。
 *
 * <p>扱う属性は長さ {@code L'} だけである。{@code K'} {@code N'} {@code T'} は条件付きアセンブリの
 * ものであり、増分 2 で入れる。ここで先に読み分けてしまうと、支えていない書き方が
 * 「引用符が閉じていない」という別の理由で止まり、原因が散る。
 */
final class Quotes {

    private Quotes() {
    }

    /** {@code index} の {@code '} が、文字列を囲む引用符かどうか。 */
    static boolean isDelimiter(CharSequence text, int index) {
        if (index == 0) {
            return true;
        }
        char previous = text.charAt(index - 1);
        if (previous != 'L' && previous != 'l') {
            return true;
        }
        return index >= 2 && Expressions.isNamePart(text.charAt(index - 2));
    }
}
