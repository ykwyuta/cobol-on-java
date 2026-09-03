package dev.cobolonjava.oracle.cases;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("V1")
@Timeout(30)
class PairwiseCoveringTest {

    @Test
    @DisplayName("生成された表は任意の 2 因子のあらゆる組を覆う (NFR-041)")
    void coversAllPairs() {
        for (int[] levels : new int[][] {{2, 2}, {3, 3, 3}, {6, 11, 11, 3}, {2, 3, 4, 5, 6}}) {
            List<int[]> rows = PairwiseCovering.generate(levels);
            assertTrue(PairwiseCovering.uncoveredPairs(levels, rows).isEmpty(),
                    "覆われていない組が残っている: " + Arrays.toString(levels));
        }
    }

    @Test
    @DisplayName("最初の因子も水準が変化する。固定されると生成が終わらない")
    void firstFactorVaries() {
        int[] levels = {6, 11, 11, 3};
        List<int[]> rows = PairwiseCovering.generate(levels);
        long distinct = rows.stream().map(r -> r[0]).distinct().count();
        assertEquals(6, distinct, "最初の因子の全水準が現れなければならない");
    }

    @Test
    @DisplayName("縮約されている。完全列挙より十分小さい")
    void isSmallerThanExhaustive() {
        int[] levels = {6, 11, 11, 3};
        List<int[]> rows = PairwiseCovering.generate(levels);
        int exhaustive = 6 * 11 * 11 * 3;
        assertTrue(rows.size() < exhaustive / 4,
                "pairwise の行数 " + rows.size() + " は完全列挙 " + exhaustive + " より十分小さいはず");
        assertTrue(rows.size() >= 11 * 11,
                "行数 " + rows.size() + " は最大 2 因子の積 121 以上のはず");
    }

    @Test
    @DisplayName("決定的である。同じ入力からは同じ表が得られる")
    void isDeterministic() {
        int[] levels = {4, 5, 6};
        List<int[]> a = PairwiseCovering.generate(levels);
        List<int[]> b = PairwiseCovering.generate(levels);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertArrayEquals(a.get(i), b.get(i));
        }
    }

    @Test
    @DisplayName("因子が 2 未満なら誤りとする")
    void requiresAtLeastTwoFactors() {
        assertThrows(IllegalArgumentException.class, () -> PairwiseCovering.generate(new int[] {3}));
    }
}
