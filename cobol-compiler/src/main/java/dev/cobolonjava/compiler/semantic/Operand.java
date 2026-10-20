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
     * @param start   値の部分参照の開始位置 (1 起点)。部分参照が無ければ {@code 0}
     * @param length  値の部分参照の長さ。終わりまでなら {@code -1}
     */
    record Function(Intrinsic intrinsic, List<Expression> arguments, Intrinsic.Result returns,
                    Origin origin, int start, int length) implements Operand {

        public Function(Intrinsic intrinsic, List<Expression> arguments, Intrinsic.Result returns,
                        Origin origin) {
            this(intrinsic, arguments, returns, origin, 0, -1);
        }

        /** 値に部分参照を書いたか。 */
        public boolean referenceModified() {
            return start > 0;
        }
    }
}
