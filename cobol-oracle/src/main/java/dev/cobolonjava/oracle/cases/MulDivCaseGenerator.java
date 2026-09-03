package dev.cobolonjava.oracle.cases;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@code MP} / {@code DP} の合成テストジェネレータ (要件 NFR-041)。
 *
 * <p>加減算と別立てにしているのは、これらの命令がオペランドの長さに制約を持ち、
 * 一様な組み合わせ表に載せられないためである ({@link MulDivCase} 参照)。
 * 制約を満たす長さの組を 1 個の因子として扱うことで、pairwise の枠組みに載せている。
 */
public final class MulDivCaseGenerator {

    /** 制約 (l2 &lt; l1、l2 &le; 8) を満たす長さの組。 */
    private static final int[][] LENGTH_PAIRS = {
            {2, 1}, {3, 1}, {3, 2}, {4, 1}, {4, 2}, {4, 3}, {8, 1}, {8, 3}, {16, 8}
    };

    private MulDivCaseGenerator() {
    }

    /** {@code MP} のテストケース。 */
    public static List<MulDivCase> multiplyCases() {
        return generate(MulDivOperation.MULTIPLY, OperandClass.values());
    }

    /**
     * {@code DP} のテストケース。
     *
     * <p>除数からゼロを除いている。ゼロ除算は 10 進除算例外になり、これは抑止できないため
     * 組み合わせ表には載せられない。ゼロ除算そのものは個別のテストで扱う。
     */
    public static List<MulDivCase> divideCases() {
        OperandClass[] nonZeroDivisors = Arrays.stream(OperandClass.values())
                .filter(c -> !c.isZero())
                .toArray(OperandClass[]::new);
        return generate(MulDivOperation.DIVIDE, nonZeroDivisors);
    }

    private static List<MulDivCase> generate(MulDivOperation operation, OperandClass[] rightClasses) {
        OperandClass[] leftClasses = OperandClass.values();
        int[] levelCounts = {LENGTH_PAIRS.length, leftClasses.length, rightClasses.length};

        List<MulDivCase> cases = new ArrayList<>();
        for (int[] row : PairwiseCovering.generate(levelCounts)) {
            int[] lengths = LENGTH_PAIRS[row[0]];
            cases.add(new MulDivCase(lengths[0], lengths[1],
                    leftClasses[row[1]], rightClasses[row[2]], operation));
        }
        return cases;
    }
}
