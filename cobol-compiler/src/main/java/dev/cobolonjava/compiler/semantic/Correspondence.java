package dev.cobolonjava.compiler.semantic;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code CORRESPONDING} の対応付け (要件 FR-044, FR-060)。
 *
 * <p>2 つの集団項目の配下から<b>同じ名前の組</b>を探す。名前が同じであることに加えて、
 * 集団項目までの経路も同じでなければならない。{@code A OF X} と {@code A OF Y} は
 * 別のものである。
 *
 * <h2>組になる条件</h2>
 * <ul>
 *   <li>名前が同じで、{@code FILLER} ではない</li>
 *   <li><b>少なくとも一方が基本項目</b>である。両方が集団項目なら、そこから下へ降りる</li>
 *   <li>どちらも {@code OCCURS} や {@code REDEFINES} で書かれていない。
 *       これらは<b>同じ名前でも指す範囲が違いうる</b>ため、対応付けから外す</li>
 *   <li>レベル 66 / 88 ではない</li>
 * </ul>
 *
 * <p>両方が集団項目のときに組にせず降りるのは、そうしないと<b>下位の対応付けが
 * 上位の一括転記に飲み込まれる</b>ためである。{@code CORRESPONDING} は名前の合うものだけを
 * 移すものであり、集団項目まるごとの転記とは違う。
 */
public final class Correspondence {

    private Correspondence() {
    }

    /**
     * 対応する組 1 つ。
     *
     * @param source 送出側の項目
     * @param target 受取側の項目
     */
    public record Pair(DataItem source, DataItem target) {
    }

    /**
     * 2 つの集団項目の配下から対応する組を探す。
     *
     * <p>並びは<b>送出側に書かれた順</b>である。受取項目が重なっている場合、
     * あとに書かれたものが残る。
     */
    public static List<Pair> of(DataItem source, DataItem target) {
        List<Pair> pairs = new ArrayList<>();
        collect(source, target, pairs);
        return List.copyOf(pairs);
    }

    private static void collect(DataItem source, DataItem target, List<Pair> pairs) {
        for (DataItem child : source.children()) {
            if (!isEligible(child)) {
                continue;
            }
            DataItem match = childNamed(target, child.name());
            if (match == null) {
                continue;
            }
            if (child.isElementary() || match.isElementary()) {
                pairs.add(new Pair(child, match));
                continue;
            }
            collect(child, match, pairs);
        }
    }

    /** 同じ名前の子。対応付けから外れる項目は最初から見ない。 */
    private static DataItem childNamed(DataItem group, String name) {
        for (DataItem child : group.children()) {
            if (isEligible(child) && name.equals(child.name())) {
                return child;
            }
        }
        return null;
    }

    /**
     * 対応付けの対象になりうる項目か。
     *
     * <p>{@code FILLER} には名前がないので対応付けようがない。{@code OCCURS} と
     * {@code REDEFINES} は、名前が同じでも指す範囲が違いうるため外す。
     * レベル 66 ({@code RENAMES}) と 88 (条件名) はデータ項目ではない。
     */
    private static boolean isEligible(DataItem item) {
        return item.name() != null
                && !item.isTable()
                && item.redefinesName() == null
                && item.level() != 66
                && item.level() != 88;
    }
}
