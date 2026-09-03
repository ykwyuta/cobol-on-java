package dev.cobolonjava.runtime.verb;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.Decimal;

/**
 * 比較の意味論 (要件 FR-046)。
 *
 * <p>数値比較は代数的な値の比較であり、内部表現の違い ({@code DISPLAY} と {@code COMP-3} など) や
 * 桁数の違いに影響されない。負のゼロと正のゼロは等しい。
 *
 * <p>英数字比較はコードページの照合順序による。EBCDIC では英字が数字より小さいという、
 * ASCII とは逆の順序になる。
 */
public final class Compare {

    private Compare() {
    }

    /**
     * 数値比較。
     *
     * @return 第 1 項が小さければ負、等しければ 0、大きければ正
     */
    public static int numeric(Decimal left, Decimal right) {
        return left.compareTo(right);
    }

    /**
     * 英数字比較。短いほうは空白で埋めて比較する。
     *
     * @return 第 1 項が小さければ負、等しければ 0、大きければ正
     */
    public static int alphanumeric(byte[] left, byte[] right, CodePage codePage) {
        return codePage.compare(left, right);
    }

    /**
     * 比較結果をホストの条件コードに対応する値へ写像する。
     *
     * <p>{@code CP} / {@code CLC} 命令は結果を条件コードに残す。
     * 0 は等しい、1 は第 1 オペランドが小さい、2 は第 1 オペランドが大きい、を意味する。
     * この写像は、オラクルによる検証で実機の条件コードと突き合わせるために用いる。
     */
    public static int toConditionCode(int comparison) {
        if (comparison == 0) {
            return 0;
        }
        return comparison < 0 ? 1 : 2;
    }
}
