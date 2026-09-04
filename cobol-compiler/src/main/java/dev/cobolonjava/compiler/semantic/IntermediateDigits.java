package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.picture.Picture;
import java.util.ArrayList;
import java.util.List;

/**
 * 算術式の中間結果の桁数 (要件 5.5.1, FR-040, FR-047)。
 *
 * <h2>なぜ翻訳時に決めるのか</h2>
 * <p>除算は一般に有限桁で終わらない。どこで打ち切るかを決めなければ結果が定まらないため、
 * 桁数は<b>実行前に決まっていなければならない</b>。実行時に必要な精度を見てから決める、
 * という選択肢はない。
 *
 * <h2>dmax は文全体から決まる</h2>
 * <p>除算の商の小数部の桁数は、その場の被演算子ではなく<b>文全体</b>から決めた
 * {@code dmax} である。除数は数えない。{@code ROUNDED} を指定した受取項目があれば 1 加える。
 *
 * <p>その場の被演算子だけから決めると {@code COMPUTE A = B / C * D} のように
 * 後段で桁が伸びる式で精度が足りなくなる。参照実装が文全体から決めているのはこのためである。
 *
 * <h2>中間結果は切り捨てる</h2>
 * <p>丸めるのは受取項目へ格納する最後の 1 回だけである。
 */
public final class IntermediateDigits {

    /** {@code ARITH(COMPAT)} の中間結果の総桁数の上限。 */
    public static final int MAX_DIGITS = 30;

    private final int dmax;

    private IntermediateDigits(int dmax) {
        this.dmax = dmax;
    }

    /**
     * 桁数 1 組。
     *
     * @param integerDigits 整数部の桁数
     * @param scale         小数部の桁数
     */
    public record Digits(int integerDigits, int scale) {

        /**
         * 総桁数を上限へ収めた桁数。
         *
         * <p>削るのは<b>小数部だけ</b>である。整数部を削ると桁あふれが検出できなくなる。
         */
        Digits capped() {
            int excess = integerDigits + scale - MAX_DIGITS;
            return excess <= 0 ? this : new Digits(integerDigits, Math.max(0, scale - excess));
        }
    }

    /**
     * 文全体を見て {@code dmax} を決める。
     *
     * @param expression 式
     * @param targets    受取項目。{@code ROUNDED} の指定を見るために要る
     */
    public static IntermediateDigits of(Expression expression,
                                        List<Statement.Arithmetic.Target> targets) {
        int dmax = 0;
        for (int scale : dividendScales(expression)) {
            dmax = Math.max(dmax, scale);
        }
        boolean rounded = false;
        for (Statement.Arithmetic.Target target : targets) {
            dmax = Math.max(dmax, scaleOf(target.reference()));
            rounded |= target.rounded();
        }
        // 丸めるには 1 桁余分に持っていなければならない
        return new IntermediateDigits(rounded ? dmax + 1 : dmax);
    }

    /** {@code dmax} に数える被演算子の小数部の桁数。除数は数えない。 */
    private static List<Integer> dividendScales(Expression expression) {
        List<Integer> scales = new ArrayList<>();
        collect(expression, scales);
        return scales;
    }

    private static void collect(Expression expression, List<Integer> scales) {
        if (expression instanceof Expression.Value value) {
            scales.add(scaleOf(value.operand()));
            return;
        }
        if (expression instanceof Expression.Negate negate) {
            collect(negate.operand(), scales);
            return;
        }
        Expression.Binary binary = (Expression.Binary) expression;
        collect(binary.left(), scales);
        if (binary.operator() != Expression.Operator.DIVIDE) {
            collect(binary.right(), scales);
        }
    }

    /** 節の桁数。{@code dmax} が決まっていないと除算の桁数が決まらない。 */
    public Digits of(Expression expression) {
        if (expression instanceof Expression.Value value) {
            return digitsOf(value.operand());
        }
        if (expression instanceof Expression.Negate negate) {
            return of(negate.operand());
        }
        Expression.Binary binary = (Expression.Binary) expression;
        Digits left = of(binary.left());
        Digits right = of(binary.right());
        return switch (binary.operator()) {
            case ADD, SUBTRACT -> new Digits(
                    Math.max(left.integerDigits(), right.integerDigits()) + 1,
                    Math.max(left.scale(), right.scale())).capped();
            case MULTIPLY -> new Digits(
                    left.integerDigits() + right.integerDigits(),
                    left.scale() + right.scale()).capped();
            case DIVIDE -> new Digits(
                    left.integerDigits() + right.scale(), dmax).capped();
        };
    }

    private static Digits digitsOf(Operand operand) {
        if (operand instanceof Operand.Literal literal) {
            Decimal value = numberOf(literal.value());
            if (value == null) {
                return new Digits(1, 0);
            }
            int scale = Math.max(0, value.scale());
            int total = value.magnitude().toString().length();
            return new Digits(Math.max(1, total - scale), scale);
        }
        DataReference reference = ((Operand.Reference) operand).reference();
        Picture picture = reference.item().picture();
        if (picture == null || !picture.isNumeric()) {
            // 英数字項目は符号なし整数として読まれる。長さがそのまま桁数になる
            return new Digits(Math.max(1, reference.constantLength().orElse(1)), 0);
        }
        return new Digits(picture.digits() - picture.scale(), picture.scale());
    }

    private static int scaleOf(Operand operand) {
        return digitsOf(operand).scale();
    }

    private static int scaleOf(DataReference reference) {
        Picture picture = reference.item().picture();
        return picture == null ? 0 : picture.scale();
    }

    private static Decimal numberOf(LiteralValue value) {
        if (value instanceof LiteralValue.Number number) {
            return number.value();
        }
        if (value instanceof LiteralValue.Figure figure
                && figure.constant() == LiteralValue.FigurativeConstant.ZERO) {
            return Decimal.zero(0);
        }
        return null;
    }
}
