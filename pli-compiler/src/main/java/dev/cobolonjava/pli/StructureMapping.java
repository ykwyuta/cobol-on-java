package dev.cobolonjava.pli;

import dev.cobolonjava.runtime.data.PackedDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 構造の配置 (Enterprise PL/I Language Reference, "Structure mapping")。
 *
 * <p>PL/I は C のように要素の前へ詰め物を足していくのではない。要素の対を順に 1 つの単位へ
 * まとめ、2 つ目を 1 つ目の後の最初の正しい位置に置いてから、<b>1 つ目を 2 つ目の方へ、自分の
 * 境界合わせが許すだけずらす</b>。余った隙間は対の前 (構造の外) へ押し出される。たとえば
 * {@code 1 S, 2 C CHAR(1), 2 B FIXED BIN(31)} の大きさは 8 ではなく 5 である。
 *
 * <p>単位は「長さ・境界合わせ・倍語の境界からのずれ」を持つ。小構造は先に配置し、そのずれを
 * 保ったまま 1 つの要素として外の構造の対に加わる。{@code UNALIGNED} は境界合わせを 1 byte
 * (ビット列は 1 ビット) にし、構造に書けば要素が受け継ぐ。
 *
 * <p>位置と長さは<b>ビット</b>で数える。UNALIGNED のビット列は前後とビット単位で詰まるからである
 * (LRM "Effect of UNALIGNED attribute")。ビット列でない要素はいつも byte の境界に来る。
 */
final class StructureMapping {

    /** 倍語のビット数。 */
    private static final int DOUBLEWORD = 64;

    /**
     * 配置の結果。添字は宣言の並び (構造の頭が 0) である。
     *
     * @param size       構造の大きさ (byte)
     * @param bitOffsets 構造の頭からの位置 (ビット)
     * @param bitLengths 長さ (ビット)
     */
    record Result(int size, int[] bitOffsets, int[] bitLengths) {

        /** 要素を含む最初の byte。 */
        int byteOffset(int index) {
            return bitOffsets[index] / 8;
        }

        /** 要素にかかる byte の数。 */
        int byteLength(int index) {
            return (bitOffsets[index] % 8 + bitLengths[index] + 7) / 8;
        }

        /** 最初の byte の中で要素が始まるビット (左から 0)。 */
        int bitShift(int index) {
            return bitOffsets[index] % 8;
        }
    }

    /** 対にまとめた単位。{@code start} は倍語の境界からのずれ (0〜63 ビット)。 */
    private record Unit(int start, int length, int alignment, Map<Integer, Integer> offsets) {
    }

    private StructureMapping() {
    }

    /**
     * 構造を配置する。
     *
     * @throws IllegalArgumentException この処理系がまだ配置を表せない要素があるとき
     */
    static Result map(List<PliSyntax.Decl> tree) {
        Unit unit = unit(tree, 0, tree.size(), null);
        // 変数は byte の境界から始まる。対をまとめる手順で、先頭の UNALIGNED のビット列が後ろの
        // byte の境界の要素の方へずれていれば、最初の byte の頭のビットは空きになる。
        // 手順をそのまま当てはめた結果で、実機と突き合わせていない (P-185)
        int lead = unit.start() % 8;
        int[] offsets = new int[tree.size()];
        int[] lengths = new int[tree.size()];
        for (int i = 0; i < tree.size(); i++) {
            offsets[i] = unit.offsets().getOrDefault(i, 0) + lead;
        }
        lengths(tree, unit, offsets, lengths, lead);
        return new Result((lead + unit.length() + 7) / 8, offsets, lengths);
    }

    /**
     * @param inherited 外の構造から受け継いだ {@code ALIGNED} (真) / {@code UNALIGNED} (偽)。
     *                  どこにも書かれていなければ {@code null} で、要素の型の既定になる
     */
    private static Unit unit(List<PliSyntax.Decl> tree, int index, int end, Boolean inherited) {
        PliSyntax.Decl decl = tree.get(index);
        Boolean effective = decl.aligned() != null ? decl.aligned() : inherited;
        if (decl.type() != PliSyntax.Type.GROUP) {
            // 既定は要素ごとに決まる。ビット列・文字・PICTURE は UNALIGNED、ほかは ALIGNED
            // (LRM "ALIGNED and UNALIGNED attributes")
            boolean unaligned = effective != null ? !effective
                    : decl.type() == PliSyntax.Type.BIT || decl.type() == PliSyntax.Type.CHAR
                            || decl.type() == PliSyntax.Type.PICTURE;
            return element(decl, unaligned, index);
        }
        Unit combined = null;
        for (int i = index + 1; i < end;) {
            int childEnd = i + 1;
            while (childEnd < end && tree.get(childEnd).level() > tree.get(i).level()) childEnd++;
            Unit member = unit(tree, i, childEnd, effective);
            combined = combined == null ? member : pair(combined, member);
            i = childEnd;
        }
        if (combined == null) {
            throw new IllegalArgumentException("structure " + decl.name() + " has no members");
        }
        Map<Integer, Integer> offsets = new HashMap<>(combined.offsets());
        offsets.put(index, 0);
        return new Unit(combined.start(), combined.length(), combined.alignment(), offsets);
    }

    private static Unit element(PliSyntax.Decl decl, boolean unaligned, int index) {
        if (decl.type() == PliSyntax.Type.BIT && unaligned) {
            // UNALIGNED のビット列はビットの境界に置き、長さもビットで数える
            return new Unit(0, Math.max(1, decl.precision()), 1, new HashMap<>(Map.of(index, 0)));
        }
        return new Unit(0, length(decl) * 8, (unaligned ? 1 : alignment(decl)) * 8,
                new HashMap<>(Map.of(index, 0)));
    }

    /**
     * 対を 1 つにまとめる (LRM "Rules for mapping one pair")。1 つ目を倍語の境界からのずれに置き、
     * 2 つ目をその後の最初の正しい位置に置き、1 つ目を 2 つ目の方へ、自分の境界合わせが許すだけずらす。
     */
    private static Unit pair(Unit first, Unit second) {
        int p1 = first.start();
        int end1 = p1 + first.length();
        int q = end1 + Math.floorMod(second.start() - end1, second.alignment());
        int shifted = q - first.length();
        shifted -= Math.floorMod(shifted - p1, first.alignment());
        Map<Integer, Integer> offsets = new HashMap<>(first.offsets());
        int gap = q - shifted;
        second.offsets().forEach((index, offset) -> offsets.put(index, offset + gap));
        return new Unit(Math.floorMod(shifted, DOUBLEWORD), q + second.length() - shifted,
                Math.max(first.alignment(), second.alignment()), offsets);
    }

    /** 要素の長さ。小構造は、配置で決まった最初の要素の頭から最後の要素の終わりまで。 */
    private static void lengths(List<PliSyntax.Decl> tree, Unit root, int[] offsets,
                                int[] lengths, int lead) {
        for (int i = 0; i < tree.size(); i++) {
            PliSyntax.Decl decl = tree.get(i);
            if (decl.type() != PliSyntax.Type.GROUP) {
                lengths[i] = elementBits(tree, i, root);
                continue;
            }
            int groupEnd = i + 1;
            while (groupEnd < tree.size() && tree.get(groupEnd).level() > decl.level()) groupEnd++;
            int first = i == 0 ? lead : Integer.MAX_VALUE;
            int last = lead;
            for (int j = i + 1; j < groupEnd; j++) {
                if (tree.get(j).type() != PliSyntax.Type.GROUP) {
                    first = Math.min(first, offsets[j]);
                    last = Math.max(last, offsets[j] + elementBits(tree, j, root));
                }
            }
            offsets[i] = i == 0 ? 0 : first;
            lengths[i] = last - offsets[i];
        }
    }

    private static int elementBits(List<PliSyntax.Decl> tree, int index, Unit root) {
        PliSyntax.Decl decl = tree.get(index);
        return decl.type() == PliSyntax.Type.BIT && root.offsets().containsKey(index)
                && isBitPacked(tree, index)
                ? Math.max(1, decl.precision()) : length(decl) * 8;
    }

    /** そのビット列が UNALIGNED (ビット単位で詰まる) か。受け継ぎを辿って決める。 */
    private static boolean isBitPacked(List<PliSyntax.Decl> tree, int index) {
        Boolean effective = tree.get(index).aligned();
        int level = tree.get(index).level();
        for (int i = index - 1; i >= 0 && effective == null; i--) {
            if (tree.get(i).level() < level) {
                effective = tree.get(i).aligned();
                level = tree.get(i).level();
            }
        }
        return effective == null || !effective;
    }

    /** 記憶域の大きさ (LRM Table 39)。 */
    static int length(PliSyntax.Decl decl) {
        return switch (decl.type()) {
            // VARYING は今の長さを持つ半語が前に付く
            case CHAR -> Math.max(1, decl.precision()) + (decl.varying() ? 2 : 0);
            case PICTURE -> Math.max(1, decl.precision());
            // ALIGNED のビット列は 8 ビットごとに 1 byte
            case BIT -> Math.max(1, (decl.precision() + 7) / 8);
            case BINARY -> binaryLength(decl.precision());
            case DECIMAL -> PackedDecimal.byteLength(Math.max(1, decl.precision()));
            case POINTER -> 4;
            case GROUP, FILE, ENTRY -> 0;
        };
    }

    /** FIXED BINARY(p) の大きさ。p ≦ 7 は 1 byte、≦ 15 は半語、≦ 31 は全語、それを超えれば倍語。 */
    static int binaryLength(int precision) {
        return precision <= 7 ? 1 : precision <= 15 ? 2 : precision <= 31 ? 4 : 8;
    }

    /** ALIGNED のときの境界合わせ (byte、LRM Table 39)。文字・PICTURE・10 進・ビット列は byte。 */
    static int alignment(PliSyntax.Decl decl) {
        return switch (decl.type()) {
            case BINARY -> binaryLength(decl.precision());
            case POINTER -> 4;
            // ALIGNED の VARYING は長さの半語の境界に置く
            case CHAR -> decl.varying() ? 2 : 1;
            default -> 1;
        };
    }
}
