package dev.cobolonjava.compiler.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.picture.PictureParser;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 中間結果の桁数の規則 (要件 5.5.1, FR-040, FR-047)。
 *
 * <p>被演算子はすべて数字定数で書く。規則は PICTURE 句からでも定数からでも同じ
 * 「整数部の桁数と小数部の桁数」しか見ないためであり、定数のほうが桁数が読み取りやすい。
 */
@Tag("V1")
class IntermediateDigitsTest {

    private static Expression value(String literal) {
        return new Expression.Value(
                new Operand.Literal(new LiteralValue.Number(Decimal.parse(literal))));
    }

    private static Expression binary(Expression.Operator operator, String left, String right) {
        return new Expression.Binary(operator, value(left), value(right));
    }

    /** 受取項目を持たない式の桁数。{@code dmax} は式だけから決まる。 */
    private static IntermediateDigits.Digits digitsOf(Expression expression) {
        return IntermediateDigits.of(expression, List.of()).of(expression);
    }

    @Test
    @DisplayName("加減は整数部が 1 桁増える")
    void additionGrowsTheIntegerPartByOne() {
        // 999 + 999 は 4 桁になる。桁あふれを検出できるようにするためである
        assertEquals(new IntermediateDigits.Digits(4, 0),
                digitsOf(binary(Expression.Operator.ADD, "999", "999")));
    }

    @Test
    @DisplayName("加減の小数部は大きいほうに合わせる")
    void additionKeepsTheWiderScale() {
        assertEquals(new IntermediateDigits.Digits(2, 2),
                digitsOf(binary(Expression.Operator.ADD, "1.5", "2.25")));
    }

    @Test
    @DisplayName("乗算は整数部も小数部も足し合わせる")
    void multiplicationAddsBothParts() {
        assertEquals(new IntermediateDigits.Digits(2, 3),
                digitsOf(binary(Expression.Operator.MULTIPLY, "1.5", "2.25")));
    }

    @Test
    @DisplayName("単項の符号は桁数を変えない")
    void negationDoesNotChangeTheDigits() {
        Expression negated = new Expression.Negate(value("1.25"));
        assertEquals(new IntermediateDigits.Digits(1, 2), digitsOf(negated));
    }

    @Test
    @DisplayName("除算の整数部は被除数の整数部に除数の小数部を足す")
    void divisionGrowsTheIntegerPartByTheDivisorScale() {
        // 1 / 0.001 は 1000 であり 4 桁になる
        assertEquals(4,
                digitsOf(binary(Expression.Operator.DIVIDE, "1", "0.001")).integerDigits());
    }

    @Test
    @DisplayName("除算の小数部は dmax である")
    void divisionTakesItsScaleFromDmax() {
        // 式の中でいちばん小数部が多いのは 0.25 の 2 桁。除算の節そのものには小数がない
        Expression.Binary whole = new Expression.Binary(Expression.Operator.ADD,
                binary(Expression.Operator.DIVIDE, "1", "3"), value("0.25"));
        IntermediateDigits digits = IntermediateDigits.of(whole, List.of());

        assertEquals(2, digits.of(whole.left()).scale(), "dmax が式全体から決まっていない");
    }

    @Test
    @DisplayName("除数の小数部は dmax に数えない")
    void aDivisorIsExcludedFromDmax() {
        // 除数の 0.001 は 3 桁だが、これを数えると商の小数部が 3 桁になってしまう
        Expression expression = binary(Expression.Operator.DIVIDE, "1", "0.001");
        assertEquals(0, digitsOf(expression).scale());
    }

    @Test
    @DisplayName("受取項目の小数部も dmax に数える")
    void aReceiverScaleCountsTowardsDmax() {
        Expression expression = binary(Expression.Operator.DIVIDE, "1", "3");
        IntermediateDigits digits = IntermediateDigits.of(expression, List.of(
                new Statement.Arithmetic.Target(numericItem("9(3)V99"), false)));

        assertEquals(2, digits.of(expression).scale());
    }

    @Test
    @DisplayName("ROUNDED があれば dmax が 1 増える")
    void roundedAddsOneToDmax() {
        // 丸めるには 1 桁余分に持っていなければならない
        Expression expression = binary(Expression.Operator.DIVIDE, "1", "3");
        IntermediateDigits digits = IntermediateDigits.of(expression, List.of(
                new Statement.Arithmetic.Target(numericItem("9(3)V99"), true)));

        assertEquals(3, digits.of(expression).scale());
    }

    @Test
    @DisplayName("総桁数が上限を超えたら小数部から削る (FR-047)")
    void anOversizedIntermediateResultLosesItsDecimalPlaces() {
        // 小数 15 桁どうしの積は 30 桁になり、整数部の 2 桁と合わせて 32 桁になる
        String fifteen = "1.000000000000000";
        IntermediateDigits.Digits digits =
                digitsOf(binary(Expression.Operator.MULTIPLY, fifteen, fifteen));

        assertEquals(new IntermediateDigits.Digits(2, IntermediateDigits.MAX_DIGITS - 2), digits);
    }

    /** PICTURE 句だけを持つ数値項目。桁数の規則が見るのはそこだけである。 */
    private static DataReference numericItem(String picture) {
        DataItem item = new DataItem(1, "WS-R", null);
        item.setPicture(PictureParser.parse(picture));
        return new DataReference(item, List.of(), null, null);
    }
}
