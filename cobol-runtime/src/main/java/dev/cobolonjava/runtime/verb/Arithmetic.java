package dev.cobolonjava.runtime.verb;

import dev.cobolonjava.runtime.decimal.CobolRounding;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.decimal.DecimalDivideException;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.storage.DataView;

/**
 * 算術文の格納の意味論 (要件 FR-043, FR-044)。
 *
 * <p>演算そのものは {@link Decimal} が担う。ここで扱うのは<b>結果を受取項目へ格納する際の規則</b>
 * であり、COBOL の算術文の互換性はほぼここに集約される。
 *
 * <h2>ON SIZE ERROR の有無で挙動が変わる</h2>
 * <ul>
 *   <li>{@code ON SIZE ERROR} を指定した場合 — 結果が受取項目に収まらなければ
 *       <b>受取項目を変更せず</b>、SIZE ERROR 条件を立てる</li>
 *   <li>指定しない場合 — 上位桁を黙って切り捨てて格納する</li>
 * </ul>
 *
 * <p>この違いは「桁あふれしたときに受取項目に何が残っているか」に直結する。
 * 変更しないのか、切り捨てた値が入るのかで、後続の処理結果が変わる。
 *
 * <p>小数部の切り捨ては SIZE ERROR ではない。丸めモードに従って処理されるだけである。
 */
public final class Arithmetic {

    private Arithmetic() {
    }

    /**
     * {@code ON SIZE ERROR} を指定しない場合の格納。上位桁は黙って切り捨てられる。
     *
     * @param rounding {@code ROUNDED} を指定しない場合は {@link CobolRounding#TRUNCATION}
     */
    public static void store(NumericItem target, DataView view, Decimal value,
                             CobolRounding rounding) {
        Decimal rounded = value.rescale(target.picture().scale(), rounding);
        target.store(view, rounded);
    }

    /**
     * {@code ON SIZE ERROR} を指定した場合の格納。
     *
     * <p>結果が受取項目に収まらなければ受取項目を変更しない。
     *
     * @return SIZE ERROR 条件が立ったかどうか
     */
    public static boolean storeChecked(NumericItem target, DataView view, Decimal value,
                                       CobolRounding rounding) {
        Decimal rounded = value.rescale(target.picture().scale(), rounding);
        if (!target.fits(rounded)) {
            return true;
        }
        target.store(view, rounded);
        return false;
    }

    /**
     * {@code DIVIDE} における除算。ゼロ除算は {@code ON SIZE ERROR} の対象である。
     *
     * @return 商。ゼロ除算の場合は空
     */
    public static java.util.Optional<Decimal> divideChecked(Decimal dividend, Decimal divisor,
                                                            int resultScale, CobolRounding rounding) {
        if (divisor.isZero()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(dividend.divide(divisor, resultScale, rounding));
    }

    /**
     * {@code ON SIZE ERROR} を指定しない場合の除算。
     * ゼロ除算はホストの 10 進除算例外 ({@code S0CB}) に相当する異常終了となる。
     *
     * @throws DecimalDivideException 除数がゼロの場合
     */
    public static Decimal divide(Decimal dividend, Decimal divisor, int resultScale,
                                 CobolRounding rounding) {
        return dividend.divide(divisor, resultScale, rounding);
    }
}
