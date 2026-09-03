package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.source.Origin;
import java.util.List;

/**
 * 手続き部の文 1 個 (要件 FR-060)。
 *
 * <p>意味論そのものはランタイムが持つ (方針 ARC-7)。ここにあるのは
 * <b>何をどれに対して行うか</b>だけであり、実際の移送や算術は行わない。
 */
public sealed interface Statement {

    /** ソース上の位置。 */
    Origin origin();

    /**
     * {@code MOVE} 文。
     *
     * @param source        送り出す側
     * @param targets       受け取る側。複数書ける
     * @param corresponding {@code CORRESPONDING} 指定かどうか
     */
    record Move(Operand source, List<DataReference> targets, boolean corresponding, Origin origin)
            implements Statement {

        public Move {
            targets = List.copyOf(targets);
        }
    }
}
