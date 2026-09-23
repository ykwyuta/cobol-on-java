package dev.cobolonjava.hlasm;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/**
 * 浮動小数点の定数 ({@code E} / {@code D} / {@code L} と、型の拡張 {@code H} / {@code B} / {@code D})。
 *
 * <p>拡張を書かなければ 16 進 (HFP)、{@code B} なら 2 進 (BFP、IEEE 754)、{@code D} なら 10 進
 * (DFP、IEEE 754 の DPD 符号化) である。長さは {@code E} が 4、{@code D} が 8、{@code L} が 16。
 *
 * <p>丸めは HLASM Language Reference の既定に従う。HFP は「失われる最初のビットの位置に 1 を
 * 足す」(丸めの方式 1、半分は 0 から遠いほうへ)、BFP と DFP は最近接偶数である。このため
 * {@code E'0.1'} は {@code 4019999A} になる。COBOL の {@code COMP-1} の {@code VALUE} は切り捨てて
 * {@code 40199999} にしており (暫定判断 P-018)、<b>2 つは別の規則である</b>。翻訳系が違うので、
 * 片方に揃えない。どちらも実機と突き合わせていない (z/OS probe の {@code ASMDC2})。
 *
 * <p>値の後ろの丸めの指定 ({@code R1} など)、{@code (INF)} や {@code (MAX)} などの特別な値、
 * 長さの修飾子は断る。受け取って既定の規則で組み立てると、書いた人の求めた値と違うバイト列に
 * なるからである。表せる範囲を超えた値と、0 でないのに 0 へ丸まる値も断る。
 */
final class FloatingConstants {

    private static final Pattern NUMBER =
            Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

    private FloatingConstants() {
    }

    /** 型の文字 ({@code E} / {@code D} / {@code L}) の既定の長さ。 */
    static int lengthOf(char type) {
        return switch (type) {
            case 'E' -> 4;
            case 'D' -> 8;
            default -> 16;
        };
    }

    /** 境界。{@code L} は 16 バイトだが、境界は 8 である。 */
    static int alignmentOf(char type) {
        return type == 'E' ? 4 : 8;
    }

    /**
     * 1 つの値を組み立てる。
     *
     * @param extension {@code 'H'} / {@code 'B'} / {@code 'D'}、書かれていなければ {@code 'H'}
     */
    static byte[] encode(char type, char extension, String text, int line) {
        String trimmed = text.trim();
        if (!NUMBER.matcher(trimmed).matches()) {
            throw new AssemblyException(line, "this floating-point value is not supported "
                    + "(rounding suffixes and special values are not supported): " + text);
        }
        boolean negative = trimmed.startsWith("-");
        BigDecimal magnitude = new BigDecimal(trimmed).abs();
        int length = lengthOf(type);
        return switch (extension) {
            case 'B' -> binary(magnitude, negative, length, line);
            case 'D' -> decimal(magnitude, negative, length, line);
            default -> hexadecimal(magnitude, negative, length, line);
        };
    }

    // --- HFP ---

    /**
     * 16 進の浮動小数点。符号 1 ビット、特性 7 ビット (指数 + 64)、正規化した 16 進の小数部。
     *
     * <p>拡張形式 (16 バイト) は 2 つの長形式に分かれ、下の特性は上の特性より 14 小さい
     * (Principles of Operation)。符号は両方に置く。
     */
    private static byte[] hexadecimal(BigDecimal magnitude, boolean negative, int length, int line) {
        int fractionBits = length == 4 ? 24 : length == 8 ? 56 : 112;
        byte[] out = new byte[length];
        if (magnitude.signum() == 0) {
            if (negative) {
                out[0] = (byte) 0x80;
                if (length == 16) {
                    out[8] = (byte) 0x80;
                }
            }
            return out;
        }
        BigInteger numerator = numerator(magnitude);
        BigInteger denominator = denominator(magnitude);
        // 16^(e-1) <= 値 < 16^e となる e を探す
        int exponent = Math.floorDiv(numerator.bitLength() - denominator.bitLength(), 4) + 1;
        while (compareScaled(numerator, denominator, 4 * exponent) >= 0) {
            exponent++;
        }
        while (compareScaled(numerator, denominator, 4 * (exponent - 1)) < 0) {
            exponent--;
        }
        // 小数部 = 値 * 2^bits / 16^e。失われる最初のビットに 1 を足して切り捨てる
        int shift = fractionBits - 4 * exponent;
        BigInteger scaledNumerator = shift >= 0 ? numerator.shiftLeft(shift) : numerator;
        BigInteger scaledDenominator = shift >= 0 ? denominator : denominator.shiftLeft(-shift);
        BigInteger[] split = scaledNumerator.divideAndRemainder(scaledDenominator);
        BigInteger fraction = split[0];
        if (split[1].shiftLeft(1).compareTo(scaledDenominator) >= 0) {
            fraction = fraction.add(BigInteger.ONE);
        }
        if (fraction.bitLength() > fractionBits) {
            fraction = fraction.shiftRight(4);
            exponent++;
        }
        int characteristic = exponent + 64;
        if (characteristic > 127) {
            throw new AssemblyException(line, "the value is too large for a hexadecimal "
                    + "floating-point constant");
        }
        if (characteristic < 0) {
            throw new AssemblyException(line, "the value is too small for a hexadecimal "
                    + "floating-point constant");
        }
        int sign = negative ? 0x80 : 0;
        if (length == 16) {
            BigInteger low = fraction.and(BigInteger.ONE.shiftLeft(56).subtract(BigInteger.ONE));
            BigInteger high = fraction.shiftRight(56);
            put(out, 0, 8, BigInteger.valueOf(sign | characteristic).shiftLeft(56).or(high));
            put(out, 8, 8, BigInteger.valueOf(sign | ((characteristic - 14) & 0x7F))
                    .shiftLeft(56).or(low));
        } else {
            put(out, 0, length, BigInteger.valueOf(sign | characteristic)
                    .shiftLeft(fractionBits).or(fraction));
        }
        return out;
    }

    // --- BFP ---

    /** 2 進の浮動小数点 (IEEE 754 の binary32 / binary64 / binary128)。最近接偶数に丸める。 */
    private static byte[] binary(BigDecimal magnitude, boolean negative, int length, int line) {
        int precision = length == 4 ? 24 : length == 8 ? 53 : 113;
        int exponentBits = length == 4 ? 8 : length == 8 ? 11 : 15;
        int bias = (1 << (exponentBits - 1)) - 1;
        int minimum = 1 - bias;
        BigInteger bits = BigInteger.ZERO;
        if (magnitude.signum() != 0) {
            BigInteger numerator = numerator(magnitude);
            BigInteger denominator = denominator(magnitude);
            // 2^e <= 値 < 2^(e+1)
            int exponent = numerator.bitLength() - denominator.bitLength();
            if (compareScaled(numerator, denominator, exponent) < 0) {
                exponent--;
            }
            if (exponent < minimum) {
                exponent = minimum; // 非正規化数
            }
            int shift = precision - 1 - exponent;
            BigInteger significand = roundHalfEven(
                    shift >= 0 ? numerator.shiftLeft(shift) : numerator,
                    shift >= 0 ? denominator : denominator.shiftLeft(-shift));
            if (significand.bitLength() > precision) {
                significand = significand.shiftRight(1);
                exponent++;
            }
            if (exponent > bias) {
                throw new AssemblyException(line, "the value is too large for a binary "
                        + "floating-point constant");
            }
            if (significand.signum() == 0) {
                throw new AssemblyException(line, "the value is too small for a binary "
                        + "floating-point constant");
            }
            if (significand.bitLength() < precision) {
                bits = significand; // 非正規化数は指数の欄が 0
            } else {
                bits = BigInteger.valueOf(exponent + bias).shiftLeft(precision - 1)
                        .or(significand.clearBit(precision - 1));
            }
        }
        if (negative) {
            bits = bits.setBit(length * 8 - 1);
        }
        byte[] out = new byte[length];
        put(out, 0, length, bits);
        return out;
    }

    // --- DFP ---

    /**
     * 10 進の浮動小数点 (IEEE 754 の decimal32 / decimal64 / decimal128、DPD 符号化)。
     *
     * <p>書いた値の量子 (小数点以下の桁数) を保つ。{@code DD'1.50'} は係数 150、指数 -2 である。
     * 精度を超える桁は最近接偶数に丸める。
     */
    private static byte[] decimal(BigDecimal magnitude, boolean negative, int length, int line) {
        int precision = length == 4 ? 7 : length == 8 ? 16 : 34;
        int continuationBits = length == 4 ? 6 : length == 8 ? 8 : 12;
        int emax = length == 4 ? 96 : length == 8 ? 384 : 6144;
        int bias = emax + precision - 2;
        int minimumQuantum = -bias;
        int maximumQuantum = emax - precision + 1;

        BigInteger coefficient = magnitude.unscaledValue();
        int quantum = -magnitude.scale();
        if (coefficient.toString().length() > precision) {
            BigDecimal rounded = magnitude.round(new java.math.MathContext(precision,
                    RoundingMode.HALF_EVEN));
            coefficient = rounded.unscaledValue();
            quantum = -rounded.scale();
        }
        if (quantum < minimumQuantum) {
            BigDecimal rounded = new BigDecimal(coefficient, -quantum)
                    .setScale(-minimumQuantum, RoundingMode.HALF_EVEN);
            if (rounded.signum() == 0 && coefficient.signum() != 0) {
                throw new AssemblyException(line, "the value is too small for a decimal "
                        + "floating-point constant");
            }
            coefficient = rounded.unscaledValue();
            quantum = minimumQuantum;
        }
        if (quantum > maximumQuantum) {
            // 係数の右に 0 を足して指数を下げる (表せる桁があれば)
            coefficient = coefficient.multiply(BigInteger.TEN.pow(quantum - maximumQuantum));
            quantum = maximumQuantum;
        }
        String digits = coefficient.toString();
        if (digits.length() > precision) {
            throw new AssemblyException(line, "the value is too large for a decimal "
                    + "floating-point constant");
        }
        digits = "0".repeat(precision - digits.length()) + digits;
        int biased = quantum + bias;
        int lead = digits.charAt(0) - '0';
        int top = biased >> continuationBits;
        int combination = lead < 8 ? (top << 3) | lead : 0x18 | (top << 1) | (lead & 1);
        BigInteger bits = BigInteger.valueOf(negative ? 1 : 0);
        bits = bits.shiftLeft(5).or(BigInteger.valueOf(combination));
        bits = bits.shiftLeft(continuationBits)
                .or(BigInteger.valueOf(biased & ((1 << continuationBits) - 1)));
        for (int k = 1; k < precision; k += 3) {
            bits = bits.shiftLeft(10).or(BigInteger.valueOf(declet(
                    digits.charAt(k) - '0', digits.charAt(k + 1) - '0', digits.charAt(k + 2) - '0')));
        }
        byte[] out = new byte[length];
        put(out, 0, length, bits);
        return out;
    }

    /** 10 進 3 桁を 10 ビットの DPD (densely packed decimal) にする。 */
    static int declet(int a, int b, int c) {
        int a3 = a & 1;
        int b3 = b & 1;
        int c3 = c & 1;
        int a12 = (a >> 1) & 3;
        int b12 = (b >> 1) & 3;
        int c12 = (c >> 1) & 3;
        int large = (a >= 8 ? 4 : 0) | (b >= 8 ? 2 : 0) | (c >= 8 ? 1 : 0);
        return switch (large) {
            case 0 -> (a << 7) | (b << 4) | c;
            case 1 -> (a << 7) | (b << 4) | 0b1000 | c3;
            case 2 -> (a << 7) | (c12 << 5) | (b3 << 4) | 0b1010 | c3;
            case 4 -> (c12 << 8) | (a3 << 7) | (b << 4) | 0b1100 | c3;
            case 6 -> (c12 << 8) | (a3 << 7) | (b3 << 4) | 0b1110 | c3;
            case 5 -> (b12 << 8) | (a3 << 7) | (0b01 << 5) | (b3 << 4) | 0b1110 | c3;
            case 3 -> (a << 7) | (0b10 << 5) | (b3 << 4) | 0b1110 | c3;
            default -> (a3 << 7) | (0b11 << 5) | (b3 << 4) | 0b1110 | c3;
        };
    }

    // --- 共通 ---

    private static BigInteger numerator(BigDecimal value) {
        return value.scale() <= 0
                ? value.unscaledValue().multiply(BigInteger.TEN.pow(-value.scale()))
                : value.unscaledValue();
    }

    private static BigInteger denominator(BigDecimal value) {
        return value.scale() <= 0 ? BigInteger.ONE : BigInteger.TEN.pow(value.scale());
    }

    /** numerator / denominator と 2^power を比べる。 */
    private static int compareScaled(BigInteger numerator, BigInteger denominator, int power) {
        return power >= 0
                ? numerator.compareTo(denominator.shiftLeft(power))
                : numerator.shiftLeft(-power).compareTo(denominator);
    }

    private static BigInteger roundHalfEven(BigInteger numerator, BigInteger denominator) {
        BigInteger[] split = numerator.divideAndRemainder(denominator);
        int half = split[1].shiftLeft(1).compareTo(denominator);
        if (half > 0 || (half == 0 && split[0].testBit(0))) {
            return split[0].add(BigInteger.ONE);
        }
        return split[0];
    }

    private static void put(byte[] out, int offset, int length, BigInteger bits) {
        for (int k = length - 1; k >= 0; k--) {
            out[offset + k] = (byte) bits.intValue();
            bits = bits.shiftRight(8);
        }
    }
}
