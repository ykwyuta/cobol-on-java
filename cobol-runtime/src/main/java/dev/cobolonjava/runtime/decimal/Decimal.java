package dev.cobolonjava.runtime.decimal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * 固定小数点 10 進数の値。要件 FR-040 に従い、2 進浮動小数を経由しない。
 *
 * <p>符号を絶対値とは独立したフィールドとして保持しているのは、<b>負のゼロを表現するため</b>である。
 * パック10進数およびゾーン10進数は符号ニブルを値とは別に持つため、{@code -0} (数字部がすべて 0 で
 * 符号ニブルが {@code D}) というバイト列が実在する。{@code BigDecimal} はこれを表現できず、
 * 符号を落とすと {@code MOVE} でバイト列が変化してしまい要件 FR-020 の L3 互換が崩れる。
 *
 * <p>不変オブジェクトである。
 */
public final class Decimal {

    /** 絶対値の非スケール値。常に 0 以上。 */
    private final BigInteger magnitude;
    /** 小数点以下の桁数。 */
    private final int scale;
    /** {@code +1} または {@code -1}。値が 0 でも {@code -1} を取りうる (負のゼロ)。 */
    private final int sign;

    private Decimal(BigInteger magnitude, int scale, int sign) {
        this.magnitude = magnitude;
        this.scale = scale;
        this.sign = sign;
    }

    /** 符号付きの非スケール値とスケールから作る。値が 0 の場合の符号は正となる。 */
    public static Decimal of(BigInteger unscaled, int scale) {
        Objects.requireNonNull(unscaled, "unscaled");
        int sign = unscaled.signum() < 0 ? -1 : 1;
        return new Decimal(unscaled.abs(), scale, sign);
    }

    /** 絶対値・スケール・符号を明示して作る。負のゼロを作るにはこれを用いる。 */
    public static Decimal of(BigInteger magnitude, int scale, int sign) {
        Objects.requireNonNull(magnitude, "magnitude");
        if (magnitude.signum() < 0) {
            throw new IllegalArgumentException("magnitude must not be negative: " + magnitude);
        }
        if (sign != 1 && sign != -1) {
            throw new IllegalArgumentException("sign must be +1 or -1: " + sign);
        }
        return new Decimal(magnitude, scale, sign);
    }

    public static Decimal of(long unscaled, int scale) {
        return of(BigInteger.valueOf(unscaled), scale);
    }

    /** {@code "-12.34"} のような 10 進表記から作る。 */
    public static Decimal parse(String text) {
        Objects.requireNonNull(text, "text");
        boolean negative = text.startsWith("-");
        BigDecimal bd = new BigDecimal(text);
        return new Decimal(bd.unscaledValue().abs(), bd.scale(), negative ? -1 : 1);
    }

    public static Decimal zero(int scale) {
        return new Decimal(BigInteger.ZERO, scale, 1);
    }

    public BigInteger magnitude() {
        return magnitude;
    }

    public int scale() {
        return scale;
    }

    /** 保持している符号。値が 0 のときも {@code -1} を返しうる。 */
    public int sign() {
        return sign;
    }

    public boolean isZero() {
        return magnitude.signum() == 0;
    }

    /** 数値としての符号。0 のときは 0 を返す。比較や条件判定に用いる。 */
    public int signum() {
        return isZero() ? 0 : sign;
    }

    public BigInteger signedUnscaled() {
        return sign < 0 ? magnitude.negate() : magnitude;
    }

    public BigDecimal toBigDecimal() {
        return new BigDecimal(signedUnscaled(), scale);
    }

    /** 小数点より左の桁数。値が 0 のときは 0 を返す。 */
    public int integerDigits() {
        int precision = magnitude.signum() == 0 ? 0 : magnitude.toString().length();
        return Math.max(0, precision - scale);
    }

    /** 符号だけを差し替えた値を返す。 */
    public Decimal withSign(int newSign) {
        return of(magnitude, scale, newSign);
    }

    public Decimal negate() {
        return new Decimal(magnitude, scale, -sign);
    }

    public Decimal abs() {
        return new Decimal(magnitude, scale, 1);
    }

    public Decimal add(Decimal other) {
        return fromBigDecimal(toBigDecimal().add(other.toBigDecimal()));
    }

    public Decimal subtract(Decimal other) {
        return fromBigDecimal(toBigDecimal().subtract(other.toBigDecimal()));
    }

    public Decimal multiply(Decimal other) {
        return fromBigDecimal(toBigDecimal().multiply(other.toBigDecimal()));
    }

    /**
     * 除算。結果のスケールと丸めを明示する。
     *
     * @throws DecimalDivideException 除数が 0 の場合 (ホストの {@code S0CB} に相当)
     */
    public Decimal divide(Decimal divisor, int resultScale, CobolRounding rounding) {
        if (divisor.isZero()) {
            throw new DecimalDivideException("division by zero");
        }
        BigDecimal q = toBigDecimal().divide(divisor.toBigDecimal(), resultScale, rounding.javaMode());
        return fromBigDecimal(q);
    }

    /** {@code DIVIDE ... REMAINDER} の剰余。商を切り捨てたうえでの残りを返す。 */
    public Decimal remainder(Decimal divisor, int quotientScale) {
        if (divisor.isZero()) {
            throw new DecimalDivideException("division by zero");
        }
        BigDecimal q = toBigDecimal().divide(divisor.toBigDecimal(), quotientScale, CobolRounding.TRUNCATION.javaMode());
        return fromBigDecimal(toBigDecimal().subtract(q.multiply(divisor.toBigDecimal())));
    }

    /**
     * スケールを変更する。桁が落ちる場合は指定の丸めモードで丸める。
     *
     * @throws ArithmeticException {@link CobolRounding#PROHIBITED} を指定していて丸めが必要になった場合
     */
    public Decimal rescale(int newScale, CobolRounding rounding) {
        if (newScale == scale) {
            return this;
        }
        BigDecimal scaled = toBigDecimal().setScale(newScale, rounding.javaMode());
        // 丸めた結果が 0 になっても、元の値の符号を保つ (例: -0.4 を整数へ切り捨てると -0)
        int resultSign = scaled.signum() == 0 ? sign : (scaled.signum() < 0 ? -1 : 1);
        return new Decimal(scaled.unscaledValue().abs(), newScale, resultSign);
    }

    /** 数値としての比較。負のゼロと正のゼロは等しいとみなす。 */
    public int compareTo(Decimal other) {
        return toBigDecimal().compareTo(other.toBigDecimal());
    }

    /**
     * 指定した整数部桁数・小数部桁数に整形した数字列を返す。上位桁は切り捨て、
     * 不足する桁は 0 で埋める。符号は含まない。
     *
     * <p>上位桁の切り捨ては、{@code ON SIZE ERROR} を指定しない場合にホストで実際に起きる挙動である。
     * 呼び出し側は {@link #fitsIn(int, int)} で事前に判定して SIZE ERROR 条件を立てられる (要件 FR-043)。
     */
    public String digitString(int integerDigits, int fractionDigits) {
        return storedDigits(integerDigits + fractionDigits, fractionDigits);
    }

    /**
     * この値を {@code 値 x 10^scale} へ変換したうえで、下位 {@code totalDigits} 桁の数字列を返す。
     * 不足する桁は 0 で埋め、あふれた上位桁は切り捨てる。符号は含まない。
     *
     * <p>{@code scale} に負値を許すのは、PICTURE の {@code P} (桁位置指定) によって
     * 格納されない桁が生じる場合を同じ式で扱うためである。
     * 例えば {@code PIC 999PPP} は {@code scale = -3} であり、値 5000 は数字列 {@code "005"} として格納される。
     */
    public String storedDigits(int totalDigits, int scale) {
        Decimal r = rescale(Math.max(scale, 0), CobolRounding.TRUNCATION);
        java.math.BigInteger m = r.magnitude;
        if (scale < 0) {
            m = m.divide(BigInteger.TEN.pow(-scale));
        }
        String all = m.toString();
        if (all.length() < totalDigits) {
            all = "0".repeat(totalDigits - all.length()) + all;
        } else if (all.length() > totalDigits) {
            all = all.substring(all.length() - totalDigits);
        }
        return all;
    }

    /** {@code 値 x 10^scale} が {@code totalDigits} 桁に収まるかどうか。 */
    public boolean fitsInDigits(int totalDigits, int scale) {
        BigDecimal shifted = toBigDecimal().movePointRight(scale)
                .setScale(0, java.math.RoundingMode.DOWN);
        return shifted.abs().toBigInteger().compareTo(BigInteger.TEN.pow(totalDigits)) < 0;
    }

    /** 指定した整数部桁数・小数部桁数に、桁落ちなく収まるかどうか。 */
    public boolean fitsIn(int integerDigits, int fractionDigits) {
        BigDecimal bd = toBigDecimal();
        if (bd.scale() > fractionDigits) {
            // 小数部の切り捨ては SIZE ERROR ではない (要件 FR-043 の対象は上位桁のあふれ)
            bd = bd.setScale(fractionDigits, java.math.RoundingMode.DOWN);
        }
        BigInteger intPart = bd.toBigInteger().abs();
        return intPart.compareTo(BigInteger.TEN.pow(integerDigits)) < 0;
    }

    private static Decimal fromBigDecimal(BigDecimal bd) {
        // 演算結果がゼロの場合の符号は正とする。
        // これはホストの 10 進演算命令の観測挙動に基づく暫定の規則であり、
        // Hercules による検証待ちである (provisional.md の P-001)。
        int sign = bd.signum() < 0 ? -1 : 1;
        return new Decimal(bd.unscaledValue().abs(), bd.scale(), sign);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Decimal other)) {
            return false;
        }
        return scale == other.scale && sign == other.sign && magnitude.equals(other.magnitude);
    }

    @Override
    public int hashCode() {
        return (magnitude.hashCode() * 31 + scale) * 31 + sign;
    }

    @Override
    public String toString() {
        String s = toBigDecimal().toPlainString();
        return (sign < 0 && isZero()) ? "-" + s : s;
    }
}
