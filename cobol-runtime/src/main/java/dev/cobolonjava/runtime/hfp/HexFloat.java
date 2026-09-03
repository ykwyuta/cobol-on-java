package dev.cobolonjava.runtime.hfp;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * IBM 16 進浮動小数点数 (HFP) の表現 (要件 FR-032)。
 * {@code USAGE COMPUTATIONAL-1} / {@code COMPUTATIONAL-2} の内部表現である。
 *
 * <h2>形式</h2>
 * <pre>
 * 短形式 (COMP-1, 4 バイト): 符号 1 ビット | 特性 7 ビット | 小数部 24 ビット (16 進 6 桁)
 * 長形式 (COMP-2, 8 バイト): 符号 1 ビット | 特性 7 ビット | 小数部 56 ビット (16 進 14 桁)
 *
 * 値 = (-1)^符号 x 0.小数部(16 進) x 16^(特性 - 64)
 * </pre>
 *
 * <h2>IEEE 754 との違い</h2>
 * <ul>
 *   <li><b>指数の基数が 16</b> である。そのため正規化しても先頭の 16 進桁に最大 3 個の
 *       先行ゼロビットが残りうる。有効精度が 21〜24 ビットの間で揺れる ("wobbling precision")</li>
 *   <li>先頭の 1 ビットを暗黙に持たない。小数部はそのまま格納される</li>
 *   <li>無限大も NaN も存在しない</li>
 *   <li>丸めは<b>切り捨て</b>が基本である。IEEE の最近接丸めとは結果が変わる</li>
 * </ul>
 *
 * <p>HFP の値はすべて 2 進の有限小数 (dyadic rational) であるため、{@link BigDecimal} で
 * <b>厳密に</b>表現できる。{@code double} は長形式の 56 ビット小数部を保持できないため用いない。
 */
public final class HexFloat {

    /** 短形式のバイト長。 */
    public static final int SHORT_BYTES = 4;
    /** 長形式のバイト長。 */
    public static final int LONG_BYTES = 8;

    /** 短形式の小数部の 16 進桁数。 */
    private static final int SHORT_DIGITS = 6;
    /** 長形式の小数部の 16 進桁数。 */
    private static final int LONG_DIGITS = 14;

    /** 特性のバイアス。 */
    private static final int BIAS = 64;

    private static final BigDecimal SIXTEEN = BigDecimal.valueOf(16);
    /** 正規化された小数部の下限 (1/16)。 */
    private static final BigDecimal LOWER = new BigDecimal("0.0625");

    private HexFloat() {
    }

    /**
     * HFP のビット表現を構成要素へ分解したもの。
     *
     * @param negative       符号
     * @param characteristic 特性 (バイアス付き指数、0〜127)
     * @param fraction       小数部を整数として見た値。短形式は 16 進 6 桁、長形式は 14 桁
     */
    public record Parts(boolean negative, int characteristic, BigInteger fraction) {

        /** 値がゼロかどうか。小数部がゼロなら特性にかかわらずゼロである。 */
        public boolean isZero() {
            return fraction.signum() == 0;
        }
    }

    /** 短形式の小数部の 16 進桁数。 */
    public static int shortFractionDigits() {
        return SHORT_DIGITS;
    }

    /** 長形式の小数部の 16 進桁数。 */
    public static int longFractionDigits() {
        return LONG_DIGITS;
    }

    /** 特性のバイアス。 */
    public static int bias() {
        return BIAS;
    }

    /** 短形式のビット表現を構成要素へ分解する。 */
    public static Parts partsShort(byte[] bytes) {
        return parts(bytes, SHORT_BYTES);
    }

    /** 長形式のビット表現を構成要素へ分解する。 */
    public static Parts partsLong(byte[] bytes) {
        return parts(bytes, LONG_BYTES);
    }

    /** 構成要素から短形式のビット表現を組み立てる。 */
    public static byte[] fromPartsShort(Parts parts) {
        return fromParts(parts, SHORT_BYTES);
    }

    /** 構成要素から長形式のビット表現を組み立てる。 */
    public static byte[] fromPartsLong(Parts parts) {
        return fromParts(parts, LONG_BYTES);
    }

    private static Parts parts(byte[] bytes, int byteLength) {
        if (bytes.length != byteLength) {
            throw new IllegalArgumentException(
                    "expected " + byteLength + " bytes, got " + bytes.length);
        }
        BigInteger fraction = BigInteger.ZERO;
        for (int i = 1; i < byteLength; i++) {
            fraction = fraction.shiftLeft(8).or(BigInteger.valueOf(bytes[i] & 0xFF));
        }
        return new Parts((bytes[0] & 0x80) != 0, bytes[0] & 0x7F, fraction);
    }

    private static byte[] fromParts(Parts parts, int byteLength) {
        if (parts.isZero()) {
            // 真のゼロ。符号・特性・小数部のすべてがゼロ
            return new byte[byteLength];
        }
        if (parts.characteristic() < 0 || parts.characteristic() > 127) {
            throw new ArithmeticException(
                    "HFP characteristic out of range: " + parts.characteristic());
        }
        byte[] out = new byte[byteLength];
        BigInteger remaining = parts.fraction();
        for (int i = byteLength - 1; i >= 1; i--) {
            out[i] = remaining.and(BigInteger.valueOf(0xFF)).byteValue();
            remaining = remaining.shiftRight(8);
        }
        out[0] = (byte) ((parts.negative() ? 0x80 : 0x00) | (parts.characteristic() & 0x7F));
        return out;
    }

    /** 値を短形式 (COMP-1) へ符号化する。 */
    public static byte[] encodeShort(BigDecimal value) {
        return encode(value, SHORT_BYTES, SHORT_DIGITS);
    }

    /** 値を長形式 (COMP-2) へ符号化する。 */
    public static byte[] encodeLong(BigDecimal value) {
        return encode(value, LONG_BYTES, LONG_DIGITS);
    }

    /** 短形式 (COMP-1) を値へ復号する。 */
    public static BigDecimal decodeShort(byte[] bytes) {
        return decode(bytes, SHORT_BYTES, SHORT_DIGITS);
    }

    /** 長形式 (COMP-2) を値へ復号する。 */
    public static BigDecimal decodeLong(byte[] bytes) {
        return decode(bytes, LONG_BYTES, LONG_DIGITS);
    }

    /**
     * 符号化する。
     *
     * <p>小数部に収まらない桁は<b>切り捨てる</b>。HFP の演算が切り捨てを基本とすることに合わせている。
     * ただし 10 進表記から HFP への変換をコンパイラがどう行うか (切り捨てか最近接か) は
     * 未確認であり、provisional.md の P-018 に記録している。
     *
     * @throws ArithmeticException 指数が表現範囲を超える場合
     */
    private static byte[] encode(BigDecimal value, int byteLength, int fractionDigits) {
        if (value.signum() == 0) {
            // 真のゼロ。符号・特性・小数部のすべてがゼロ
            return new byte[byteLength];
        }
        boolean negative = value.signum() < 0;
        BigDecimal magnitude = value.abs();

        // 1/16 <= 小数部 < 1 となるよう指数を求める
        int exponent = 0;
        while (magnitude.compareTo(BigDecimal.ONE) >= 0) {
            magnitude = magnitude.divide(SIXTEEN);
            exponent++;
        }
        while (magnitude.compareTo(LOWER) < 0) {
            magnitude = magnitude.multiply(SIXTEEN);
            exponent--;
        }

        int characteristic = exponent + BIAS;
        if (characteristic > 127) {
            throw new ArithmeticException("HFP exponent overflow: characteristic " + characteristic);
        }
        if (characteristic < 0) {
            throw new ArithmeticException("HFP exponent underflow: characteristic " + characteristic);
        }

        BigInteger fraction = magnitude
                .multiply(new BigDecimal(BigInteger.valueOf(16).pow(fractionDigits)))
                .toBigInteger();

        byte[] out = new byte[byteLength];
        BigInteger remaining = fraction;
        for (int i = byteLength - 1; i >= 1; i--) {
            out[i] = remaining.and(BigInteger.valueOf(0xFF)).byteValue();
            remaining = remaining.shiftRight(8);
        }
        out[0] = (byte) ((negative ? 0x80 : 0x00) | (characteristic & 0x7F));
        return out;
    }

    private static BigDecimal decode(byte[] bytes, int byteLength, int fractionDigits) {
        if (bytes.length != byteLength) {
            throw new IllegalArgumentException(
                    "expected " + byteLength + " bytes, got " + bytes.length);
        }
        boolean negative = (bytes[0] & 0x80) != 0;
        int characteristic = bytes[0] & 0x7F;

        BigInteger fraction = BigInteger.ZERO;
        for (int i = 1; i < byteLength; i++) {
            fraction = fraction.shiftLeft(8).or(BigInteger.valueOf(bytes[i] & 0xFF));
        }
        if (fraction.signum() == 0) {
            // 小数部がゼロなら値はゼロである。特性の値にかかわらずゼロ
            return BigDecimal.ZERO;
        }

        int exponent = characteristic - BIAS - fractionDigits;
        BigDecimal result = new BigDecimal(fraction);
        if (exponent >= 0) {
            result = result.multiply(SIXTEEN.pow(exponent));
        } else {
            result = result.divide(SIXTEEN.pow(-exponent));
        }
        return negative ? result.negate() : result;
    }
}
