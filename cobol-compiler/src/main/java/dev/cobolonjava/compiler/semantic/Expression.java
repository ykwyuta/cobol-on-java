package dev.cobolonjava.compiler.semantic;

/**
 * 算術式 (要件 FR-040, FR-044)。{@code COMPUTE} だけが取る。
 *
 * <p>ほかの算術文は被演算子の並びであり、畳み方が文の種類で決まっている
 * ({@link Statement.Arithmetic})。式は木であり、<b>中間結果の桁数が節ごとに決まる</b>点が違う。
 * 桁数の決め方は {@link IntermediateDigits} にある。
 */
public sealed interface Expression {

    /** 被演算子 1 個。 */
    record Value(Operand operand) implements Expression {
    }

    /** 単項の符号。{@code +} は何もしないので、ここへ来るのは {@code -} だけである。 */
    record Negate(Expression operand) implements Expression {
    }

    /** 2 項演算。 */
    record Binary(Operator operator, Expression left, Expression right) implements Expression {
    }

    /** 演算の種類。べき乗はまだ扱わない (暫定判断 P-028)。 */
    enum Operator {
        ADD, SUBTRACT, MULTIPLY, DIVIDE
    }
}
