package dev.cobolonjava.pli;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * FLOAT の IEEE の記憶域の表現 (LRM "HEXADEC and IEEE attributes"、翻訳オプション
 * {@code FLOAT(DFP)})。
 *
 * <p>2 進の IEEE は Java の float / double と同じ形である。10 進の IEEE (DFP) は
 * IEEE 754-2008 の DPD 符号化で、短形式 7 桁・長形式 16 桁・拡張形式 34 桁を持つ。
 * DPD の 10 ビットの組み立ては hlasm-assembler の {@code FloatingConstants.declet} と
 * {@code FloatingPoint.undeclet} と同じ規則であり、どちらも Zeus から移したものである。
 * 実機とは突き合わせていない。
 */
final class PliFloat {

    /** 値の種類。ISZERO などの組み込み関数が見る。 */
    enum Kind { ZERO, NORMAL, SUBNORMAL, INFINITE, NAN }

    private PliFloat() {
    }

    // --- 2 進の IEEE ---

    static byte[] encodeBinary(BigDecimal value, int width) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(width);
        if (width == 4) {
            buffer.putFloat(value.floatValue());
        } else if (width == 8) {
            buffer.putDouble(value.doubleValue());
        } else {
            throw new PliRuntime.PliExecutionException("extended IEEE binary FLOAT is not supported yet");
        }
        return buffer.array();
    }

    static BigDecimal decodeBinary(byte[] bytes) {
        double value = bytes.length == 4 ? java.nio.ByteBuffer.wrap(bytes).getFloat()
                : java.nio.ByteBuffer.wrap(bytes).getDouble();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new PliRuntime.PliExecutionException("an IEEE infinity or NaN cannot be used as a value");
        }
        return new BigDecimal(value);
    }

    static Kind classifyBinary(byte[] bytes) {
        double value = bytes.length == 4 ? java.nio.ByteBuffer.wrap(bytes).getFloat()
                : java.nio.ByteBuffer.wrap(bytes).getDouble();
        if (Double.isNaN(value)) return Kind.NAN;
        if (Double.isInfinite(value)) return Kind.INFINITE;
        if (value == 0) return Kind.ZERO;
        double minimum = bytes.length == 4 ? Float.MIN_NORMAL : Double.MIN_NORMAL;
        return Math.abs(value) < minimum ? Kind.SUBNORMAL : Kind.NORMAL;
    }

    // --- 10 進の IEEE (DFP) ---

    static int decimalPrecision(int width) {
        return width == 4 ? 7 : width == 8 ? 16 : 34;
    }

    private static int bias(int width) {
        return width == 4 ? 101 : width == 8 ? 398 : 6176;
    }

    private static int exponentBits(int width) {
        return width == 4 ? 6 : width == 8 ? 8 : 12;
    }

    /** DFP の記憶域の長さ。短形式は 7 桁、長形式は 16 桁、それを超えれば拡張形式。 */
    static int decimalLength(int precision) {
        return precision <= 7 ? 4 : precision <= 16 ? 8 : 16;
    }

    /**
     * 値を DFP にする。係数と指数は値の書き方 (BigDecimal の位取り) をそのまま使い、桁が
     * 多ければ最も近い値へ (同じなら偶数へ) 丸める。
     */
    static byte[] encodeDecimal(BigDecimal value, int width) {
        int precision = decimalPrecision(width);
        BigDecimal rounded = value.round(new MathContext(precision, RoundingMode.HALF_EVEN));
        BigInteger coefficient = rounded.unscaledValue().abs();
        int exponent = -rounded.scale();
        int maximum = (3 << exponentBits(width)) - 1 - bias(width);
        while (exponent < -bias(width)) {
            coefficient = coefficient.divide(BigInteger.TEN);
            exponent++;
        }
        while (exponent > maximum && coefficient.toString().length() < precision) {
            coefficient = coefficient.multiply(BigInteger.TEN);
            exponent--;
        }
        if (exponent > maximum) {
            throw new PliRuntime.Condition("OVERFLOW", null, 300, null);
        }
        String digits = coefficient.toString();
        digits = "0".repeat(precision - digits.length()) + digits;
        int msd = digits.charAt(0) - '0';
        int biased = exponent + bias(width);
        int expbits = exponentBits(width);
        int high = biased >> expbits;
        int combination = msd < 8 ? (high << 3) | msd : 24 | (high << 1) | (msd & 1);
        BigInteger continuation = BigInteger.ZERO;
        for (int at = 1; at < precision; at += 3) {
            continuation = continuation.shiftLeft(10).or(BigInteger.valueOf(declet(
                    digits.charAt(at) - '0', digits.charAt(at + 1) - '0', digits.charAt(at + 2) - '0')));
        }
        int trailing = (precision - 1) / 3 * 10;
        BigInteger raw = BigInteger.valueOf(combination).shiftLeft(width * 8 - 6)
                .or(BigInteger.valueOf(biased & ((1 << expbits) - 1)).shiftLeft(trailing))
                .or(continuation);
        if (rounded.signum() < 0) {
            raw = raw.setBit(width * 8 - 1);
        }
        byte[] bytes = new byte[width];
        byte[] magnitude = raw.toByteArray();
        for (int i = 0; i < Math.min(width, magnitude.length); i++) {
            bytes[width - 1 - i] = magnitude[magnitude.length - 1 - i];
        }
        return bytes;
    }

    static BigDecimal decodeDecimal(byte[] bytes) {
        Kind kind = classifyDecimal(bytes);
        if (kind == Kind.INFINITE || kind == Kind.NAN) {
            throw new PliRuntime.PliExecutionException("a DFP infinity or NaN cannot be used as a value");
        }
        int width = bytes.length;
        BigInteger raw = new BigInteger(1, bytes);
        int combination = raw.shiftRight(width * 8 - 6).intValue() & 31;
        int precision = decimalPrecision(width);
        int expbits = exponentBits(width);
        int trailing = (precision - 1) / 3 * 10;
        int high = combination < 24 ? combination >> 3 : (combination >> 1) & 3;
        int leading = combination < 24 ? combination & 7 : 8 | (combination & 1);
        int exponent = ((high << expbits) | (raw.shiftRight(trailing).intValue() & ((1 << expbits) - 1)))
                - bias(width);
        BigInteger coefficient = BigInteger.valueOf(leading);
        BigInteger thousand = BigInteger.valueOf(1000);
        for (int at = trailing / 10 - 1; at >= 0; at--) {
            coefficient = coefficient.multiply(thousand)
                    .add(BigInteger.valueOf(undeclet(raw.shiftRight(at * 10).intValue() & 1023)));
        }
        BigDecimal value = new BigDecimal(coefficient, -exponent);
        return raw.testBit(width * 8 - 1) ? value.negate() : value;
    }

    static Kind classifyDecimal(byte[] bytes) {
        int width = bytes.length;
        int combination = (bytes[0] >> 2) & 31;
        if (combination == 30) return Kind.INFINITE;
        if (combination == 31) return Kind.NAN;
        BigDecimal value = decodeFinite(bytes);
        if (value.signum() == 0) return Kind.ZERO;
        int precision = decimalPrecision(width);
        // 正規の数は、最上位の桁の位が最小の指数 + 桁数 - 1 以上のもの
        int order = value.precision() - value.scale() - 1;
        return order < -bias(width) + precision - 1 ? Kind.SUBNORMAL : Kind.NORMAL;
    }

    private static BigDecimal decodeFinite(byte[] bytes) {
        byte[] copy = bytes.clone();
        int combination = (copy[0] >> 2) & 31;
        if (combination >= 30) {
            return BigDecimal.ZERO;
        }
        return decodeDecimalUnchecked(copy);
    }

    private static BigDecimal decodeDecimalUnchecked(byte[] bytes) {
        int width = bytes.length;
        BigInteger raw = new BigInteger(1, bytes);
        int combination = raw.shiftRight(width * 8 - 6).intValue() & 31;
        int precision = decimalPrecision(width);
        int expbits = exponentBits(width);
        int trailing = (precision - 1) / 3 * 10;
        int high = combination < 24 ? combination >> 3 : (combination >> 1) & 3;
        int leading = combination < 24 ? combination & 7 : 8 | (combination & 1);
        int exponent = ((high << expbits) | (raw.shiftRight(trailing).intValue() & ((1 << expbits) - 1)))
                - bias(width);
        BigInteger coefficient = BigInteger.valueOf(leading);
        BigInteger thousand = BigInteger.valueOf(1000);
        for (int at = trailing / 10 - 1; at >= 0; at--) {
            coefficient = coefficient.multiply(thousand)
                    .add(BigInteger.valueOf(undeclet(raw.shiftRight(at * 10).intValue() & 1023)));
        }
        return new BigDecimal(coefficient, -exponent);
    }

    /** 10 進 3 桁を DPD の 10 ビットにする。 */
    static int declet(int a, int b, int c) {
        int a3 = a & 1;
        int b3 = b & 1;
        int c3 = c & 1;
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

    /** DPD の 10 ビットを 10 進 3 桁へ戻す。 */
    static int undeclet(int code) {
        int p = (code >> 9) & 1;
        int q = (code >> 8) & 1;
        int r = (code >> 7) & 1;
        int s = (code >> 6) & 1;
        int t = (code >> 5) & 1;
        int u = (code >> 4) & 1;
        int v = (code >> 3) & 1;
        int w = (code >> 2) & 1;
        int x = (code >> 1) & 1;
        int y = code & 1;
        int pqr = (p << 2) | (q << 1) | r;
        int stu = (s << 2) | (t << 1) | u;
        int a;
        int d;
        int e;
        if (v == 0) {
            a = pqr;
            d = stu;
            e = (w << 2) | (x << 1) | y;
        } else if (w == 0 && x == 0) {
            a = pqr;
            d = stu;
            e = 8 | y;
        } else if (w == 0) {
            a = pqr;
            d = 8 | u;
            e = (s << 2) | (t << 1) | y;
        } else if (x == 0) {
            a = 8 | r;
            d = stu;
            e = (p << 2) | (q << 1) | y;
        } else if (s == 1 && t == 0) {
            a = pqr;
            d = 8 | u;
            e = 8 | y;
        } else if (s == 0 && t == 1) {
            a = 8 | r;
            d = (p << 2) | (q << 1) | u;
            e = 8 | y;
        } else if (s == 0) {
            a = 8 | r;
            d = 8 | u;
            e = (p << 2) | (q << 1) | y;
        } else {
            a = 8 | r;
            d = 8 | u;
            e = 8 | y;
        }
        return a * 100 + d * 10 + e;
    }
}
