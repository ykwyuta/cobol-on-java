package dev.cobolonjava.oracle.cases;

import java.util.ArrayList;
import java.util.List;

/**
 * 10 進演算の合成テストジェネレータ (要件 NFR-041、決定事項 D-15)。
 *
 * <p>境界値は全列挙し、それ以外の軸は 2-way カバリングアレイで縮約する。
 *
 * <h2>因子</h2>
 * <table>
 *   <caption>組み合わせの因子と水準</caption>
 *   <tr><th>因子</th><th>水準</th></tr>
 *   <tr><td>項目のバイト長</td><td>1, 2, 3, 4, 8, 16 — 数字ニブル数 1, 3, 5, 7, 15, 31 に対応する。
 *       {@code ARITH(COMPAT)} の 18 桁と {@code ARITH(EXTEND)} の 31 桁の境界を含む</td></tr>
 *   <tr><td>第 1 オペランドの値</td><td>{@link OperandClass} の全 11 分類</td></tr>
 *   <tr><td>第 2 オペランドの値</td><td>同上</td></tr>
 *   <tr><td>演算</td><td>{@link DecimalOperation} の全種別</td></tr>
 * </table>
 *
 * <p>完全列挙すると {@code 6 x 11 x 11 x 3 = 2178} 件になる。pairwise では
 * 任意の 2 因子のあらゆる組が現れることを保ちつつ、これを大幅に縮約する。
 */
public final class DecimalCaseGenerator {

    /** 検証対象とする項目のバイト長。数字ニブル数は {@code 2n - 1} になる。 */
    private static final int[] BYTE_LENGTHS = {1, 2, 3, 4, 8, 16};

    private DecimalCaseGenerator() {
    }

    /** pairwise によるテストケースの生成。 */
    public static List<DecimalCase> generate() {
        OperandClass[] operands = OperandClass.values();
        DecimalOperation[] operations = DecimalOperation.values();
        int[] levelCounts = {
                BYTE_LENGTHS.length, operands.length, operands.length, operations.length
        };

        List<DecimalCase> cases = new ArrayList<>();
        for (int[] row : PairwiseCovering.generate(levelCounts)) {
            cases.add(new DecimalCase(
                    BYTE_LENGTHS[row[0]], operands[row[1]], operands[row[2]], operations[row[3]]));
        }
        return cases;
    }

    /** 完全列挙した場合の件数。縮約の効果を報告するために用いる。 */
    public static int exhaustiveCount() {
        return BYTE_LENGTHS.length * OperandClass.values().length
                * OperandClass.values().length * DecimalOperation.values().length;
    }
}
