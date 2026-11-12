package dev.cobolonjava.runtime.picture;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 外部浮動小数点の項目 ({@code PIC +99.99E+99})。字で持つ浮動小数点である。
 *
 * <p>形は「仮数の符号・仮数・E・指数の符号・指数 2 桁」である (Enterprise COBOL 言語解説書
 * "External floating-point items")。仮数は左端の桁が 0 にならないように寄せ、そのぶんを
 * 指数に入れる。{@code 1.25} を {@code +99.99E+99} に入れると {@code +12.50E-01} になる。
 * 符号の位置に {@code +} と書けば正負どちらも字が出て、{@code -} と書けば正は空白になる。
 *
 * <p>仮数に入りきらない桁は切り捨てる。実機が丸めるか切り捨てるかは突き合わせていない。
 */
public final class ExternalFloating {

    private ExternalFloating() {
    }

    /** 書いた PICTURE が外部浮動小数点の形か。{@code template} は繰り返しを展開した字の並び。 */
    static boolean matches(String template) {
        return template.matches("[+-](9+[.V]9*|[.V]9+|9+)E[+-]99");
    }

    private static int mantissaEnd(String template) {
        return template.indexOf('E');
    }

    /** 仮数のうち、小数点より左の桁数。 */
    static int integerDigits(String template) {
        String mantissa = template.substring(1, mantissaEnd(template));
        int point = Math.max(mantissa.indexOf('.'), mantissa.indexOf('V'));
        return point < 0 ? mantissa.length() : point;
    }

    /** 仮数のうち、小数点より右の桁数。 */
    static int fractionDigits(String template) {
        String mantissa = template.substring(1, mantissaEnd(template));
        int point = Math.max(mantissa.indexOf('.'), mantissa.indexOf('V'));
        return point < 0 ? 0 : mantissa.length() - point - 1;
    }

    /** 項目が占める字の数。{@code V} は場所を取らない。 */
    static int size(String template) {
        return template.length() - (template.indexOf('V') >= 0 ? 1 : 0);
    }

    /** 値を字にする。 */
    public static String format(BigDecimal value, String template) {
        int integers = integerDigits(template);
        int fractions = fractionDigits(template);
        int digits = integers + fractions;
        boolean negative = value.signum() < 0;
        BigDecimal magnitude = value.abs();
        int exponent = 0;
        String mantissa = "0".repeat(digits);
        if (magnitude.signum() != 0) {
            // 左端の桁が 0 でなくなるところまで寄せる
            int order = magnitude.precision() - magnitude.scale() - 1;
            exponent = order - (integers - 1);
            if (integers == 0) {
                exponent = order + 1;
            }
            mantissa = magnitude.movePointLeft(exponent).setScale(fractions, RoundingMode.DOWN)
                    .unscaledValue().toString();
            mantissa = "0".repeat(Math.max(0, digits - mantissa.length())) + mantissa;
        }
        if (Math.abs(exponent) > 99) {
            throw new ArithmeticException(
                    "the exponent " + exponent + " does not fit an external floating-point item");
        }
        StringBuilder out = new StringBuilder();
        out.append(sign(template.charAt(0), negative));
        out.append(mantissa, 0, integers);
        if (template.indexOf('.') >= 0) {
            out.append('.');
        }
        out.append(mantissa, integers, digits);
        out.append('E');
        out.append(sign(template.charAt(mantissaEnd(template) + 1), exponent < 0));
        out.append(String.format("%02d", Math.abs(exponent)));
        return out.toString();
    }

    private static char sign(char written, boolean negative) {
        return negative ? '-' : written == '+' ? '+' : ' ';
    }

    /**
     * 字を値に戻す。形が合わなければ {@code null}。
     *
     * @param implied {@code V} で書いた、字に出ない小数の桁数。小数点が字にあれば 0
     */
    public static BigDecimal parse(String text, int implied) {
        if (!text.matches("[+\\- ]\\d*\\.?\\d*E[+\\- ]\\d\\d") || !text.matches(".*\\d.*E.*")) {
            return null;
        }
        int e = text.indexOf('E');
        BigDecimal mantissa = new BigDecimal(text.substring(1, e).isEmpty() ? "0"
                : text.substring(1, e)).movePointLeft(implied);
        int exponent = Integer.parseInt(text.substring(e + 2));
        if (text.charAt(e + 1) == '-') {
            exponent = -exponent;
        }
        BigDecimal value = mantissa.movePointRight(exponent);
        return text.charAt(0) == '-' ? value.negate() : value;
    }
}
