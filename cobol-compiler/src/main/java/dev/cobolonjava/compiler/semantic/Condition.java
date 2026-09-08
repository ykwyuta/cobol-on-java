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
     * <p>両辺は<b>算術式</b>である。{@code IF 1 + (TWO * 3) = 7} と書けるので、
     * 被演算子 1 個では足りない。ふつうの項目や定数は {@link Expression.Value} 1 つに
     * なるので、式にしても後段の作りは変わらない。
     *
     * @param numeric 数値として比べるかどうか。両辺が数値なら代数的な比較、
     *                そうでなければコードページの照合順序による比較になる
     */
    record Relation(Expression left, Comparison comparison, Expression right, boolean numeric,
                    Origin origin) implements Condition {

        /** 被演算子 1 個どうしの比較を作る。符号条件と条件名の展開が使う。 */
        public static Relation of(Operand left, Comparison comparison, Operand right,
                                  boolean numeric, Origin origin) {
            return new Relation(new Expression.Value(left), comparison,
                    new Expression.Value(right), numeric, origin);
        }

        /**
         * 左辺が被演算子 1 個なら、それ。
         *
         * @return 式なら {@code null}
         */
        public static Operand operandOf(Expression side) {
            return side instanceof Expression.Value value ? value.operand() : null;
        }
    }

    /**
     * 級条件 (要件 FR-046)。
     *
     * <p>比べる相手を持たない。項目の<b>中身が何でできているか</b>を問う条件である。
     *
     * @param allowed 書いて決めた級 ({@code CLASS} 句) に入るバイト。
     *                組み込みの級では {@code null}
     */
    record ClassTest(DataReference item, Kind kind, byte[] allowed, Origin origin)
            implements Condition {

        /** 問う中身。 */
        public enum Kind {
            /** 数字。符号を持つ項目では符号の正しさも見る。 */
            NUMERIC,
            /** 英字。空白も通る。 */
            ALPHABETIC,
            /** 小文字。 */
            ALPHABETIC_LOWER,
            /** 大文字。 */
            ALPHABETIC_UPPER,
            /** 書いて決めた級。 */
            DEFINED
        }
    }

    record Not(Condition inner) implements Condition {
    }

    record And(Condition left, Condition right) implements Condition {
    }

    record Or(Condition left, Condition right) implements Condition {
    }
}
