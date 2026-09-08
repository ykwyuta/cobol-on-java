package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.source.Origin;
import java.util.List;

/** 文の被演算子 (要件 FR-060)。データ項目への参照か、定数か、組み込み関数の呼び出しである。 */
public sealed interface Operand {

    /** データ項目への参照。 */
    record Reference(DataReference reference) implements Operand {
    }

    /** 定数。 */
    record Literal(LiteralValue value) implements Operand {
    }

    /**
     * 組み込み関数の呼び出し (要件 FR-070)。
     *
     * <p>被演算子として持つのは、関数が書ける場所が<b>被演算子の書ける場所そのもの</b>
     * だからである。算術式にも条件にも {@code MOVE} の送出側にも書ける。ここへ置けば、
     * 送出側を作る道 1 本を直すだけで全部に効く。
     *
     * @param returns 戻り値の分類。{@code MAX} のように引数を見て決まるものがあるので、
     *                関数そのものではなく<b>この呼び出し</b>が持つ
     */
    record Function(Intrinsic intrinsic, List<Expression> arguments, Intrinsic.Result returns,
                    Origin origin) implements Operand {
    }
}
