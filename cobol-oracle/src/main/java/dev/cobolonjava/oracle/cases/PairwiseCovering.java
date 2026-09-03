package dev.cobolonjava.oracle.cases;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 2-way カバリングアレイ (pairwise) の生成 (要件 NFR-041、決定事項 D-15)。
 *
 * <p>合成テストジェネレータの組み合わせ空間は完全列挙できない。境界値は全列挙し、
 * それ以外の軸は「任意の 2 つの因子のあらゆる水準の組み合わせが少なくとも 1 回現れる」
 * ところまで縮約する。バグの大半は境界と 2 要素の相互作用で顕在化するという経験則に依拠する。
 *
 * <p>貪欲法による構成である。最小性は保証しないが、決定的であり、
 * 生成された表がすべての組を覆っていることは検証できる ({@link #uncoveredPairs})。
 */
public final class PairwiseCovering {

    /** 主基準を同点打開より優先させるための重み。 */
    private static final int WEIGHT = 1_000_000;

    private PairwiseCovering() {
    }

    /**
     * カバリングアレイを生成する。
     *
     * @param levelCounts 各因子の水準数
     * @return 各行が因子ごとの水準番号を持つ表
     */
    public static List<int[]> generate(int[] levelCounts) {
        int factors = levelCounts.length;
        if (factors < 2) {
            throw new IllegalArgumentException("at least two factors are required");
        }
        for (int c : levelCounts) {
            if (c < 1) {
                throw new IllegalArgumentException("each factor needs at least one level");
            }
        }

        Set<Long> uncovered = allPairs(levelCounts);
        List<int[]> rows = new ArrayList<>();
        while (!uncovered.isEmpty()) {
            int before = uncovered.size();
            int[] row = buildRow(levelCounts, uncovered);
            removeCovered(row, uncovered);
            if (uncovered.size() == before) {
                // 1 行で 1 つも覆えないなら、以降も進まない。無限ループを避けるために止める
                throw new IllegalStateException(
                        "greedy construction made no progress with " + before + " pairs uncovered");
            }
            rows.add(row);
        }
        return rows;
    }

    /** 生成された表が覆っていない組を返す。空であれば pairwise を満たしている。 */
    public static Set<Long> uncoveredPairs(int[] levelCounts, List<int[]> rows) {
        Set<Long> uncovered = allPairs(levelCounts);
        for (int[] row : rows) {
            removeCovered(row, uncovered);
        }
        return uncovered;
    }

    /**
     * 1 行を貪欲に組み立てる。因子を順に見て、次の 2 つの基準で水準を選ぶ。
     *
     * <ol>
     *   <li>すでに決めた因子との間で、まだ覆われていない組をいくつ新たに覆うか (主基準)</li>
     *   <li>まだ決めていない因子との間に、まだ覆われていない組がいくつ残っているか (同点の打開)</li>
     * </ol>
     *
     * <p>2 番目の基準は必須である。これがないと最初の因子は常に水準 0 が選ばれ
     * (決めた因子がないため主基準がすべて同点になる)、その因子の他の水準を含む組が
     * 永久に覆われず、生成が終わらない。
     */
    private static int[] buildRow(int[] levelCounts, Set<Long> uncovered) {
        int factors = levelCounts.length;
        int[] row = new int[factors];
        java.util.Arrays.fill(row, -1);

        for (int f = 0; f < factors; f++) {
            int bestLevel = 0;
            long bestScore = -1;
            for (int level = 0; level < levelCounts[f]; level++) {
                long score = (long) immediateGain(row, f, level, uncovered) * WEIGHT
                        + remainingPotential(levelCounts, f, level, uncovered);
                if (score > bestScore) {
                    bestScore = score;
                    bestLevel = level;
                }
            }
            row[f] = bestLevel;
        }
        return row;
    }

    /** すでに決めた因子との間で新たに覆える組の数。 */
    private static int immediateGain(int[] row, int factor, int level, Set<Long> uncovered) {
        int gain = 0;
        for (int g = 0; g < factor; g++) {
            if (uncovered.contains(pairKey(g, row[g], factor, level))) {
                gain++;
            }
        }
        return gain;
    }

    /** まだ決めていない因子との間に残っている、覆われていない組の数。 */
    private static int remainingPotential(int[] levelCounts, int factor, int level,
                                          Set<Long> uncovered) {
        int potential = 0;
        for (int g = factor + 1; g < levelCounts.length; g++) {
            for (int lv = 0; lv < levelCounts[g]; lv++) {
                if (uncovered.contains(pairKey(factor, level, g, lv))) {
                    potential++;
                }
            }
        }
        return potential;
    }

    private static Set<Long> allPairs(int[] levelCounts) {
        Set<Long> pairs = new HashSet<>();
        for (int i = 0; i < levelCounts.length; i++) {
            for (int j = i + 1; j < levelCounts.length; j++) {
                for (int vi = 0; vi < levelCounts[i]; vi++) {
                    for (int vj = 0; vj < levelCounts[j]; vj++) {
                        pairs.add(pairKey(i, vi, j, vj));
                    }
                }
            }
        }
        return pairs;
    }

    private static void removeCovered(int[] row, Set<Long> uncovered) {
        for (int i = 0; i < row.length; i++) {
            for (int j = i + 1; j < row.length; j++) {
                uncovered.remove(pairKey(i, row[i], j, row[j]));
            }
        }
    }

    /** 因子と水準の組を 1 個の long へ詰める。 */
    private static long pairKey(int factorA, int levelA, int factorB, int levelB) {
        return (((long) factorA) << 48) | (((long) levelA) << 32)
                | (((long) factorB) << 16) | levelB;
    }
}
