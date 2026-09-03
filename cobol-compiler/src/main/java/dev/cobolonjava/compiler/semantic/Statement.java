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
    record Move(Operand source, List<Target> targets, boolean corresponding, Origin origin)
            implements Statement {

        public Move {
            targets = List.copyOf(targets);
        }

        /**
         * 受け取り側 1 個と、そこへの転記の種類。
         *
         * <p>種類は分類の組み合わせから翻訳時に決まる。<b>呼ぶ先が決まっていなければ
         * コードは生成できない</b>ため、実行時まで残さない。
         */
        public record Target(DataReference reference, MoveRules.Kind kind) {
        }
    }

    /**
     * 算術文 ({@code ADD} {@code SUBTRACT} {@code MULTIPLY} {@code DIVIDE})。
     *
     * <p>4 つの文はどれも「被演算子を左から畳んだ値を、受取項目へ入れるか、
     * 受取項目に対して演算する」形に落ちる。文ごとの違いは<b>畳み方と、
     * 受取項目を巻き込むかどうか</b>だけである。
     *
     * <pre>
     * ADD A B TO C          C = C + (A + B)      fold=ADD,      accumulate=ADD
     * ADD A B GIVING C      C = A + B            fold=ADD,      accumulate=null
     * SUBTRACT A B FROM C   C = C - (A + B)      fold=ADD,      accumulate=SUBTRACT
     * SUBTRACT A FROM B GIVING C
     *                       C = B - A            fold=SUBTRACT, accumulate=null
     * MULTIPLY A BY B       B = B * A            fold=MULTIPLY, accumulate=MULTIPLY
     * DIVIDE A INTO B       B = B / A            fold=DIVIDE,   accumulate=DIVIDE
     * DIVIDE A BY B GIVING C
     *                       C = A / B            fold=DIVIDE,   accumulate=null
     * </pre>
     *
     * @param fold       被演算子を左から畳む演算
     * @param operands   畳む順に並べた被演算子
     * @param accumulate 受取項目を巻き込む演算。{@code GIVING} の形では {@code null}
     * @param targets    受取項目
     */
    record Arithmetic(Operator fold, List<Operand> operands, Operator accumulate,
                      List<Target> targets, Origin origin) implements Statement {

        public Arithmetic {
            operands = List.copyOf(operands);
            targets = List.copyOf(targets);
        }

        /** 演算の種類。 */
        public enum Operator {
            ADD, SUBTRACT, MULTIPLY, DIVIDE
        }

        /**
         * 受取項目 1 個。
         *
         * @param rounded {@code ROUNDED} 指定。ないときは切り捨てになる
         */
        public record Target(DataReference reference, boolean rounded) {
        }
    }

    /**
     * {@code IF} 文。
     *
     * @param onTrue  条件が成り立つときの文。{@code NEXT SENTENCE} は空の並びになる
     * @param onFalse {@code ELSE} の文。なければ空
     */
    record If(Condition condition, List<Statement> onTrue, List<Statement> onFalse, Origin origin)
            implements Statement {

        public If {
            onTrue = List.copyOf(onTrue);
            onFalse = List.copyOf(onFalse);
        }
    }

    /** {@code CONTINUE}。何もしない。 */
    record Continue(Origin origin) implements Statement {
    }

    /**
     * {@code PERFORM} 文。
     *
     * <p>繰り返しの指定と、繰り返す中身の 2 つからなる。中身は<b>段落を呼ぶか、
     * その場に書いた文か</b>のどちらかで、両方ということはない。
     *
     * @param target    呼ぶ段落の名前。その場に書く形では {@code null}
     * @param through   {@code THRU} で指定した最後の段落。なければ {@code null}
     * @param times     {@code TIMES} の回数。指定がなければ {@code null}
     * @param until     {@code UNTIL} の条件。指定がなければ {@code null}
     * @param testAfter {@code WITH TEST AFTER} 指定。中身を 1 度実行してから条件を見る
     * @param body      その場に書いた文。段落を呼ぶ形では空
     */
    record Perform(String target, String through, Operand times, Condition until,
                   boolean testAfter, List<Statement> body, Origin origin) implements Statement {

        public Perform {
            body = List.copyOf(body);
        }

        /** 段落を呼ぶ形かどうか。 */
        public boolean callsParagraph() {
            return target != null;
        }
    }
}
