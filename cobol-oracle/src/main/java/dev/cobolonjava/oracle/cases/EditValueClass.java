package dev.cobolonjava.oracle.cases;

import dev.cobolonjava.runtime.decimal.Decimal;
import java.math.BigInteger;

/**
 * 数値編集の検証に用いる値の分類 (要件 NFR-041 の境界値)。
 *
 * <p>決定事項 D-15 に従い、値の境界は<b>全列挙</b>する。PICTURE の形は pairwise で縮約するが、
 * 各 PICTURE に対してここに挙げた値はすべて試す。ゼロ抑制の境界はゼロ近傍と最大値に現れるためである。
 */
public enum EditValueClass {

    /** ゼロ。全桁抑制の分岐に入る。 */
    ZERO,
    /** 表現できる最小の正数。抑制が最後の 1 桁で止まる境界。 */
    SMALLEST_POSITIVE,
    /** 整数部の最下位だけが 1 の値。小数点の直前で抑制が止まる境界。 */
    ONE,
    /** 桁ごとに異なる値。桁の取り違えを検出する。 */
    MIXED,
    /** 全桁 9 の正数。抑制が 1 桁も起きない境界。 */
    MAX,
    /** 最小の負数。 */
    SMALLEST_NEGATIVE,
    /** -1。 */
    MINUS_ONE,
    /** 桁ごとに異なる負数。 */
    MINUS_MIXED,
    /** 全桁 9 の負数。 */
    MINUS_MAX;

    /**
     * この分類に対応する値を作る。
     *
     * @param digits PICTURE の桁数
     * @param scale  小数部の桁数
     */
    public Decimal value(int digits, int scale) {
        return switch (this) {
            case ZERO -> Decimal.zero(scale);
            case SMALLEST_POSITIVE -> unscaled(BigInteger.ONE, scale, 1);
            case ONE -> unscaled(BigInteger.TEN.pow(scale), scale, 1);
            case MIXED -> unscaled(mixed(digits), scale, 1);
            case MAX -> unscaled(max(digits), scale, 1);
            case SMALLEST_NEGATIVE -> unscaled(BigInteger.ONE, scale, -1);
            case MINUS_ONE -> unscaled(BigInteger.TEN.pow(scale), scale, -1);
            case MINUS_MIXED -> unscaled(mixed(digits), scale, -1);
            case MINUS_MAX -> unscaled(max(digits), scale, -1);
        };
    }

    private static Decimal unscaled(BigInteger magnitude, int scale, int sign) {
        return Decimal.of(magnitude, scale, sign);
    }

    private static BigInteger max(int digits) {
        return BigInteger.TEN.pow(digits).subtract(BigInteger.ONE);
    }

    private static BigInteger mixed(int digits) {
        StringBuilder sb = new StringBuilder(digits);
        for (int i = 0; i < digits; i++) {
            sb.append((char) ('1' + (i % 9)));
        }
        return new BigInteger(sb.toString());
    }
}
