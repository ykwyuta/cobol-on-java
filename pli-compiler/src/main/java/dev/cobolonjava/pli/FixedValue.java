package dev.cobolonjava.pli;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 属性を持つ FIXED の値。
 *
 * <p>PL/I では、算術値を文字にしたときの形が<b>値ではなく属性で決まる</b>。{@code FIXED BIN(31)}
 * の 1 は 14 桁の欄に右寄せされ、{@code FIXED DEC(5)} の 1 は 8 桁の欄に右寄せされる
 * (Enterprise PL/I Language Reference, Chapter 4 "Target: CHARACTER")。素の {@code BigDecimal}
 * で運ぶと、この区別が消える。
 *
 * <p>演算結果の属性は RULES(IBM) の表 (LRM Table 28) に従う。精度の上限は LIMITS の既定
 * ({@code FIXEDBIN(31)}、{@code FIXEDDEC(15)}) である。どちらも翻訳時オプションで変わるが、
 * いまは既定しか持たない (暫定判断 P-183)。
 *
 * @param binary    {@code FIXED BINARY} なら真、{@code FIXED DECIMAL} なら偽
 * @param precision 桁数。2 進なら bit 数
 * @param scale     位取り。2 進なら bit 数
 */
record FixedValue(BigDecimal value, boolean binary, int precision, int scale) {

    /** LIMITS(FIXEDBIN) の既定。 */
    static final int MAX_BINARY = 31;
    /** LIMITS(FIXEDDEC) の既定。 */
    static final int MAX_DECIMAL = 15;

    FixedValue {
        Objects.requireNonNull(value, "value");
    }

    /**
     * 10 進定数。精度は書かれた桁の数、位取りは小数点より右の桁の数である
     * (LRM "Decimal fixed-point constants")。{@code 0.5} は {@code FIXED DEC(2,1)} であり、
     * 値からは戻せないので原文の綴りから決める。
     */
    static FixedValue constant(String text) {
        int digits = 0;
        int fraction = 0;
        boolean afterPoint = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.') {
                afterPoint = true;
            } else if (Character.isDigit(c)) {
                digits++;
                if (afterPoint) fraction++;
            }
        }
        return new FixedValue(new BigDecimal(text), false, Math.max(1, digits), fraction);
    }

    static FixedValue binary(BigDecimal value, int precision, int scale) {
        return new FixedValue(value, true, precision, scale);
    }

    static FixedValue decimal(BigDecimal value, int precision, int scale) {
        return new FixedValue(value, false, precision, scale);
    }

    FixedValue negate() {
        return new FixedValue(value.negate(), binary, precision, scale);
    }

    FixedValue add(FixedValue other) {
        return additive(other, value.add(other.value));
    }

    FixedValue subtract(FixedValue other) {
        return additive(other, value.subtract(other.value));
    }

    /** 加減算: p = 1 + MAX(p1-q1, p2-q2) + q、q = MAX(q1, q2)。 */
    private FixedValue additive(FixedValue other, BigDecimal result) {
        FixedValue a = common(other);
        FixedValue b = other.common(this);
        int q = Math.max(a.scale, b.scale);
        int p = 1 + Math.max(a.precision - a.scale, b.precision - b.scale) + q;
        return a.result(result, p, q);
    }

    /** 乗算: p = 1 + p1 + p2、q = q1 + q2。 */
    FixedValue multiply(FixedValue other) {
        FixedValue a = common(other);
        FixedValue b = other.common(this);
        return a.result(value.multiply(other.value), 1 + a.precision + b.precision,
                a.scale + b.scale);
    }

    /**
     * 除算: p は上限いっぱい、q = 上限 - p1 + q1 - q2。
     *
     * <p>FIXED の除算は q 桁で<b>切り捨てる</b>。{@code 7/2} を 2 進の整数どうしで割れば 3 である。
     * 2 進の位取りが 0 でないものは、2 進の小数を 10 進の桁で切ることになり正しく表せないので、
     * 値を切らずに返す (暫定判断 P-183)。
     */
    FixedValue divide(FixedValue other) {
        if (other.value.signum() == 0) {
            throw new PliRuntime.PliExecutionException("ZERODIVIDE");
        }
        FixedValue a = common(other);
        FixedValue b = other.common(this);
        int limit = a.binary ? MAX_BINARY : MAX_DECIMAL;
        int q = limit - a.precision + a.scale - b.scale;
        int decimalScale = a.binary ? (q == 0 ? 0 : Integer.MIN_VALUE) : q;
        BigDecimal quotient = decimalScale == Integer.MIN_VALUE
                ? value.divide(other.value, java.math.MathContext.DECIMAL128)
                : value.divide(other.value, decimalScale, RoundingMode.DOWN);
        return new FixedValue(quotient, a.binary, limit, q);
    }

    /**
     * 相手が 2 進なら、10 進の側を同じ値の 2 進へ移した属性にする (RULES(IBM))。
     * {@code FIXED DEC(p,q)} は {@code FIXED BIN(1+CEIL(p*3.32), CEIL(ABS(q*3.32))*SIGN(q))} になる。
     */
    private FixedValue common(FixedValue other) {
        if (binary || !other.binary) {
            return this;
        }
        return new FixedValue(value, true, 1 + ceilTimes332(precision),
                Integer.signum(scale) * ceilTimes332(Math.abs(scale)));
    }

    private FixedValue result(BigDecimal result, int p, int q) {
        return new FixedValue(result, binary, Math.min(p, binary ? MAX_BINARY : MAX_DECIMAL), q);
    }

    /**
     * 文字への変換 (LRM "Target: CHARACTER")。list-directed の出力も同じ規則を使う。
     *
     * <p>2 進は先に 10 進の精度 {@code p = 1 + CEIL(p1/3.32)}、{@code q = CEIL(ABS(q1/3.32))*SIGN(q1)}
     * へ移す。そのうえで {@code p >= q >= 0} なら、幅 {@code p+3} の欄に右寄せし、先頭の 0 は空白に
     * する。負なら最初の数字の前に負号、正は符号なし、{@code q > 0} なら小数点と q 桁の小数を付ける。
     * 小数の点の前の 0 は 1 つ残す。
     */
    String toCharacter() {
        int p = binary ? 1 + ceilDividedBy332(precision) : precision;
        int q = binary ? Integer.signum(scale) * ceilDividedBy332(Math.abs(scale)) : scale;
        if (q < 0 || p < q) {
            // 位取りの因子 (F+n) を付ける形。まだ持たない
            throw new PliRuntime.PliExecutionException(
                    "conversion of FIXED(" + p + "," + q + ") to character is not supported yet");
        }
        BigDecimal shown = value.setScale(q, RoundingMode.DOWN);
        String digits = shown.abs().toPlainString();
        String text = (shown.signum() < 0 ? "-" : "") + digits;
        int width = p + 3;
        return text.length() >= width ? text : " ".repeat(width - text.length()) + text;
    }

    /** CEIL(n*3.32)。浮動小数で掛けると 25*3.32 が 83 を超えて 84 に丸まるので整数で計算する。 */
    static int ceilTimes332(int n) {
        return Math.ceilDiv(n * 332, 100);
    }

    /** CEIL(n/3.32)。 */
    static int ceilDividedBy332(int n) {
        return Math.ceilDiv(n * 100, 332);
    }
}
