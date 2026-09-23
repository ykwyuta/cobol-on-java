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
 * <p>演算結果の属性は {@code RULES(IBM)} なら LRM Table 28、{@code RULES(ANS)} なら Table 26 / 27
 * に従う。精度の上限は {@code LIMITS} で決まる ({@link Arithmetic})。
 *
 * @param binary    {@code FIXED BINARY} なら真、{@code FIXED DECIMAL} なら偽
 * @param precision 桁数。2 進なら bit 数
 * @param scale     位取り。2 進なら bit 数
 */
record FixedValue(BigDecimal value, boolean binary, int precision, int scale) {

    /**
     * 1 つの式の算術の規則。
     *
     * <p>{@code LIMITS(FIXEDDEC(15,31))} は「式に 15 桁を超える被演算子が無ければ 15、あれば 31」
     * を上限にする (Programming Guide "LIMITS")。以前は 15 と 31 で打ち切っており、
     * {@code FIXED DEC(20)} どうしの和まで 15 桁に切っていた。
     *
     * @param wideDecimal 式のどこかに FIXEDDEC の下の限りを超える 10 進の被演算子があるか
     * @param wideBinary  式のどこかに FIXEDBIN の下の限りを超える 2 進の被演算子があるか
     */
    record Arithmetic(PliOptions options, boolean wideDecimal, boolean wideBinary) {

        static final Arithmetic DEFAULT = new Arithmetic(PliOptions.DEFAULT, false, false);

        int decimalLimit(int p1, int p2) {
            return wideDecimal || Math.max(p1, p2) > options.decimalLow()
                    ? options.decimalHigh() : options.decimalLow();
        }

        int binaryLimit(int p1, int p2) {
            return wideBinary || Math.max(p1, p2) > options.binaryLow()
                    ? options.binaryHigh() : options.binaryLow();
        }
    }

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

    FixedValue add(FixedValue other, Arithmetic arithmetic) {
        return additive(other, value.add(other.value), arithmetic);
    }

    FixedValue subtract(FixedValue other, Arithmetic arithmetic) {
        return additive(other, value.subtract(other.value), arithmetic);
    }

    /** 加減算: p = 1 + MAX(p1-q1, p2-q2) + q、q = MAX(q1, q2)。 */
    private FixedValue additive(FixedValue other, BigDecimal result, Arithmetic arithmetic) {
        FixedValue[] pair = common(this, other, arithmetic);
        FixedValue a = pair[0];
        FixedValue b = pair[1];
        int q = Math.max(a.scale, b.scale);
        int p = 1 + Math.max(a.precision - a.scale, b.precision - b.scale) + q;
        return a.result(result, p, q, b, arithmetic);
    }

    /** 乗算: p = 1 + p1 + p2、q = q1 + q2。 */
    FixedValue multiply(FixedValue other, Arithmetic arithmetic) {
        FixedValue[] pair = common(this, other, arithmetic);
        FixedValue a = pair[0];
        FixedValue b = pair[1];
        return a.result(value.multiply(other.value), 1 + a.precision + b.precision,
                a.scale + b.scale, b, arithmetic);
    }

    /**
     * 除算: p は上限いっぱい、q = 上限 - p1 + q1 - q2。RULES(ANS) の 2 進どうしは q = 0 である
     * (LRM Table 26: 位取りの無い 2 進の除算は位取りのある結果を作らない)。
     *
     * <p>FIXED の除算は q 桁で<b>切り捨てる</b>。{@code 7/2} を 2 進の整数どうしで割れば 3 である。
     * 2 進の位取りが 0 でないものは、2 進の小数を 10 進の桁で切ることになり正しく表せないので、
     * 値を切らずに返す (暫定判断 P-183)。
     */
    FixedValue divide(FixedValue other, Arithmetic arithmetic) {
        if (other.value.signum() == 0) {
            throw new PliRuntime.PliExecutionException("ZERODIVIDE");
        }
        FixedValue[] pair = common(this, other, arithmetic);
        FixedValue a = pair[0];
        FixedValue b = pair[1];
        int limit = a.binary ? arithmetic.binaryLimit(a.precision, b.precision)
                : arithmetic.decimalLimit(a.precision, b.precision);
        int q = a.binary && arithmetic.options().ans() ? 0 : limit - a.precision + a.scale - b.scale;
        int decimalScale = a.binary ? (q == 0 ? 0 : Integer.MIN_VALUE) : q;
        BigDecimal quotient = decimalScale == Integer.MIN_VALUE
                ? value.divide(other.value, java.math.MathContext.DECIMAL128)
                : value.divide(other.value, decimalScale, RoundingMode.DOWN);
        return new FixedValue(quotient, a.binary, limit, q);
    }

    /**
     * 2 つの被演算子を同じ基数にそろえる。
     *
     * <p>RULES(IBM) では、どちらかが 2 進なら 10 進の側を 2 進へ移す。
     * {@code FIXED DEC(p,q)} は {@code FIXED BIN(1+CEIL(p*3.32), CEIL(ABS(q*3.32))*SIGN(q))} になる。
     *
     * <p>RULES(ANS) では、位取りのある 10 進と 2 進なら 2 進の側を 10 進へ移す
     * ({@code FIXED BIN(p)} は {@code FIXED DEC(CEIL(p/3.32))}、LRM Table 27)。位取りの無い
     * 10 進と 2 進は IBM と同じく 2 進にする (Table 26)。位取りのある 2 進は ANS では許されない。
     */
    private static FixedValue[] common(FixedValue a, FixedValue b, Arithmetic arithmetic) {
        if (arithmetic.options().ans() && ((a.binary && a.scale != 0) || (b.binary && b.scale != 0))) {
            throw new PliRuntime.PliExecutionException(
                    "scaled FIXED BINARY is not allowed under RULES(ANS)");
        }
        if (a.binary == b.binary) {
            return new FixedValue[] {a, b};
        }
        FixedValue decimal = a.binary ? b : a;
        if (arithmetic.options().ans() && decimal.scale != 0) {
            return new FixedValue[] {a.toDecimal(), b.toDecimal()};
        }
        return new FixedValue[] {a.toBinary(), b.toBinary()};
    }

    private FixedValue toBinary() {
        if (binary) {
            return this;
        }
        return new FixedValue(value, true, 1 + ceilTimes332(precision),
                Integer.signum(scale) * ceilTimes332(Math.abs(scale)));
    }

    private FixedValue toDecimal() {
        if (!binary) {
            return this;
        }
        return new FixedValue(value, false, ceilDividedBy332(precision), 0);
    }

    private FixedValue result(BigDecimal result, int p, int q, FixedValue other,
                              Arithmetic arithmetic) {
        int limit = binary ? arithmetic.binaryLimit(precision, other.precision)
                : arithmetic.decimalLimit(precision, other.precision);
        return new FixedValue(result, binary, Math.min(p, limit), q);
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
