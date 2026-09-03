package dev.cobolonjava.oracle.cases;

/**
 * {@code MP} / {@code DP} のテストケース。
 *
 * <p>これらの命令はオペランドの長さに制約を持つため、加減算とは別の生成が必要である。
 *
 * <ul>
 *   <li>第 2 オペランドの長さ {@code l2} は 8 バイト以下で、かつ {@code l1} より短くなければならない</li>
 *   <li>{@code MP} は第 1 オペランドの上位 {@code l2} バイトがゼロでなければならない。
 *       違反するとデータ例外になる</li>
 *   <li>{@code DP} は商が上位 {@code l1 - l2} バイトに収まらなければならない。
 *       収まらない場合は 10 進除算例外 ({@code S0CB}) になる。これは抑止できない</li>
 * </ul>
 *
 * @param l1        第 1 オペランドのバイト長
 * @param l2        第 2 オペランドのバイト長
 * @param left      第 1 オペランドの値の分類
 * @param right     第 2 オペランドの値の分類
 * @param operation 演算
 */
public record MulDivCase(int l1, int l2, OperandClass left, OperandClass right,
                         MulDivOperation operation) {

    public MulDivCase {
        if (l2 >= l1) {
            throw new IllegalArgumentException("l2 must be shorter than l1: l1=" + l1 + ", l2=" + l2);
        }
        if (l2 > 8) {
            throw new IllegalArgumentException("l2 must be 8 bytes or fewer: " + l2);
        }
    }

    /**
     * 第 1 オペランドが値に使ってよい桁数。
     *
     * <p>{@code MP} は上位 {@code l2} バイト (= {@code 2 * l2} 桁) がゼロである必要がある。
     * {@code DP} は商が {@code l1 - l2} バイトに収まる必要があり、被除数をこの桁数に抑えれば
     * 商は必ず収まる (商は被除数以下であるため)。どちらも同じ式になる。
     */
    public int leftSignificantDigits() {
        return 2 * (l1 - l2) - 1;
    }

    public byte[] leftBytes() {
        return left.bytes(l1, leftSignificantDigits());
    }

    public byte[] rightBytes() {
        return right.bytes(l2);
    }

    @Override
    public String toString() {
        return String.format("%s l1=%d l2=%d %s %s", operation, l1, l2, left, right);
    }
}
