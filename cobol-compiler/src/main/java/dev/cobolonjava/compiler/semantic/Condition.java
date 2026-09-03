package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.source.Origin;

/**
 * 条件 (要件 FR-046, FR-061)。
 *
 * <p>関係条件と、その否定・連言・選言だけを持つ。<b>符号条件と条件名 (88 レベル) は
 * 意味解析で関係条件へ展開する</b> — どちらも「値を比べる」ことの言い換えでしかなく、
 * 別の形として持つと後段が同じ処理を 2 度書くことになる。
 */
public sealed interface Condition {

    /** 比較の向き。 */
    enum Comparison {
        EQUAL, NOT_EQUAL, LESS, LESS_OR_EQUAL, GREATER, GREATER_OR_EQUAL;

        /** 否定した向き。 */
        public Comparison negate() {
            return switch (this) {
                case EQUAL -> NOT_EQUAL;
                case NOT_EQUAL -> EQUAL;
                case LESS -> GREATER_OR_EQUAL;
                case LESS_OR_EQUAL -> GREATER;
                case GREATER -> LESS_OR_EQUAL;
                case GREATER_OR_EQUAL -> LESS;
            };
        }
    }

    /**
     * 関係条件。
     *
     * @param numeric 数値として比べるかどうか。両辺が数値なら代数的な比較、
     *                そうでなければコードページの照合順序による比較になる
     */
    record Relation(Operand left, Comparison comparison, Operand right, boolean numeric,
                    Origin origin) implements Condition {
    }

    record Not(Condition inner) implements Condition {
    }

    record And(Condition left, Condition right) implements Condition {
    }

    record Or(Condition left, Condition right) implements Condition {
    }
}
