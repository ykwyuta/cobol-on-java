package dev.cobolonjava.hlasm;

import java.math.BigInteger;

/**
 * 浮動小数点の 3 つの基数 (16 進 HFP、2 進 BFP、10 進 DFP) の符号化と、そのあいだの変換。
 *
 * <p>値は<b>整数の比</b> ({@link Ratio}) で持ち、丸めの判断はすべて整数で行う。ホストの
 * {@code double} を通すと、HFP の桁落ちや DFP の量子 (quantum) が黙って別の値になるからである。
 *
 * <p>形式と丸めは z/Architecture Principles of Operation (SA22-7832) の 9 章 (浮動小数点の概観)、
 * 18 章 (HFP)、19 章 (BFP)、20 章 (DFP) に従う。構成と手順は Zeus (同じ権利者の処理系) の
 * {@code hlasm/float.rs} と {@code hlasm/execute/floating/} から移した。
 * <b>実機とは突き合わせていない。</b>Zeus は HFP・BFP・DFP の基本の演算を Hercules と
 * 突き合わせているが、PFPO は Hercules が持たないので仕様との一致だけである。
 *
 * <p>割り込みを許した (FPC のマスクを立てた) ときの振る舞いと、NaN の演算は持たない。
 * そこへ来たら断る。近い値を返すよりよい。
 */
final class FloatingPoint {

    private FloatingPoint() {
    }

    /** IEEE の例外の旗。FPC の旗の並びと同じ位置である。 */
    static final int INVALID = 0x80;
    static final int DIVIDE_BY_ZERO = 0x40;
    static final int OVERFLOW = 0x20;
    static final int UNDERFLOW = 0x10;
    static final int INEXACT = 0x08;
    /** 丸めた結果の絶対値が元より大きい。 */
    static final int INCREMENTED = 0x04;

    enum Radix { HEX, BINARY, DECIMAL }

    enum Rounding {
        /** 最近接、半分は 0 から遠いほうへ。 */
        NEAREST_AWAY,
        /** 最近接、半分は偶数へ。 */
        NEAREST_EVEN,
        ZERO,
        POSITIVE,
        NEGATIVE,
        /** 最近接、半分は 0 のほうへ。 */
        NEAREST_ZERO,
        AWAY,
        /** より短い精度への丸めに備える (round to prepare for shorter precision)。 */
        PREPARE
    }

    /** 丸めた商と、端数を捨てたか。 */
    record Rounded(BigInteger q, boolean inexact) {
    }

    /** 符号化した結果と例外の旗。 */
    record Encoded(BigInteger raw, int flags) {
    }

    sealed interface Value permits Finite, Infinity, NotANumber {
    }

    record Finite(Ratio value) implements Value {
    }

    record Infinity(boolean negative) implements Value {
    }

    record NotANumber(boolean negative, boolean signaling) implements Value {
    }

    /**
     * 有限の値。符号を別に持つのは、符号つきの 0 を保つためである。
     */
    record Ratio(boolean negative, BigInteger n, BigInteger d) {

        static Ratio binary(boolean negative, BigInteger significand, int exponent) {
            return exponent >= 0
                    ? new Ratio(negative, significand.shiftLeft(exponent), BigInteger.ONE)
                    : new Ratio(negative, significand, BigInteger.ONE.shiftLeft(-exponent));
        }

        static Ratio decimal(boolean negative, BigInteger coefficient, int exponent) {
            return exponent >= 0
                    ? new Ratio(negative, coefficient.multiply(pow10(exponent)), BigInteger.ONE)
                    : new Ratio(negative, coefficient, pow10(-exponent));
        }

        boolean isZero() {
            return n.signum() == 0;
        }

        Ratio negate() {
            return new Ratio(!negative, n, d);
        }

        /** @param zeroNegative 打ち消し合って 0 になったときの符号 (負方向への丸めなら負) */
        Ratio add(Ratio other, boolean zeroNegative) {
            BigInteger a = n.multiply(other.d);
            BigInteger b = other.n.multiply(d);
            BigInteger denominator = d.multiply(other.d);
            if (negative == other.negative) {
                return new Ratio(negative, a.add(b), denominator);
            }
            int order = a.compareTo(b);
            if (order > 0) {
                return new Ratio(negative, a.subtract(b), denominator);
            }
            if (order < 0) {
                return new Ratio(other.negative, b.subtract(a), denominator);
            }
            return new Ratio(zeroNegative, BigInteger.ZERO, denominator);
        }

        Ratio multiply(Ratio other) {
            return new Ratio(negative ^ other.negative, n.multiply(other.n), d.multiply(other.d));
        }

        Ratio divide(Ratio other) {
            return new Ratio(negative ^ other.negative, n.multiply(other.d), d.multiply(other.n));
        }

        /** 絶対値どうしを比べる。 */
        int compareMagnitude(Ratio other) {
            return n.multiply(other.d).compareTo(other.n.multiply(d));
        }

        /** 0 でない値の、2 を底とする対数の床。 */
        int floorLog2() {
            int e = n.bitLength() - d.bitLength();
            int order = e >= 0 ? n.compareTo(d.shiftLeft(e)) : n.shiftLeft(-e).compareTo(d);
            return order < 0 ? e - 1 : e;
        }

        /** 0 でない値の、10 を底とする対数の床。 */
        int floorLog10() {
            int e = n.toString().length() - d.toString().length();
            int order = e >= 0 ? n.compareTo(d.multiply(pow10(e)))
                    : n.multiply(pow10(-e)).compareTo(d);
            return order < 0 ? e - 1 : e;
        }

        /** 2 の {@code exponent} 乗を単位にして整数へ丸める。 */
        Rounded quantize(int exponent, Rounding mode) {
            return exponent >= 0
                    ? roundQuotient(n, d.shiftLeft(exponent), negative, mode, 2)
                    : roundQuotient(n.shiftLeft(-exponent), d, negative, mode, 2);
        }

        /** 10 の {@code exponent} 乗を単位にして整数へ丸める。 */
        Rounded quantize10(int exponent, Rounding mode) {
            return exponent >= 0
                    ? roundQuotient(n, d.multiply(pow10(exponent)), negative, mode, 10)
                    : roundQuotient(n.multiply(pow10(-exponent)), d, negative, mode, 10);
        }

        /**
         * HFP の加算の桁合わせ。保護桁 (guard digit) は 16 進 1 桁であり、それより下の
         * ビットは引き算の借りに加わらない (Principles of Operation "Addition and Subtraction")。
         */
        Ratio addWithHexGuard(Ratio other, int exponent, int precision) {
            int quantum = 4 * exponent - precision - 4;
            Ratio a = binary(negative, quantize(quantum, Rounding.ZERO).q(), quantum);
            Ratio b = binary(other.negative, other.quantize(quantum, Rounding.ZERO).q(), quantum);
            return a.add(b, false);
        }
    }

    static BigInteger pow10(int exponent) {
        return BigInteger.TEN.pow(exponent);
    }

    static Rounded roundQuotient(BigInteger n, BigInteger d, boolean negative, Rounding mode,
                                 int radix) {
        BigInteger[] parts = n.divideAndRemainder(d);
        BigInteger q = parts[0];
        boolean inexact = parts[1].signum() != 0;
        int half = parts[1].shiftLeft(1).compareTo(d);
        boolean increase = inexact && switch (mode) {
            case NEAREST_AWAY -> half >= 0;
            case NEAREST_EVEN -> half > 0 || (half == 0 && q.testBit(0));
            case NEAREST_ZERO -> half > 0;
            case ZERO -> false;
            case POSITIVE -> !negative;
            case NEGATIVE -> negative;
            case AWAY -> true;
            case PREPARE -> {
                int last = q.mod(BigInteger.valueOf(radix)).intValue();
                yield last == 0 || (radix == 10 && last == 5);
            }
        };
        return new Rounded(increase ? q.add(BigInteger.ONE) : q, inexact);
    }

    private static BigInteger bit(int position) {
        return BigInteger.ONE.shiftLeft(position);
    }

    private static BigInteger mask(int bits) {
        return bit(bits).subtract(BigInteger.ONE);
    }

    private static BigInteger sign(boolean negative, int width) {
        return negative ? bit(width * 8 - 1) : BigInteger.ZERO;
    }

    // --- BFP (IEEE 754 の 2 進) ---

    static int binaryFraction(int width) {
        return width == 4 ? 23 : width == 8 ? 52 : 112;
    }

    static int binaryBias(int width) {
        return width == 4 ? 127 : width == 8 ? 1023 : 16383;
    }

    static Value decodeBinary(BigInteger raw, int width) {
        int fraction = binaryFraction(width);
        int bias = binaryBias(width);
        boolean negative = raw.testBit(width * 8 - 1);
        int exponent = raw.shiftRight(fraction).intValue() & (2 * bias + 1);
        BigInteger significand = raw.and(mask(fraction));
        if (exponent == 2 * bias + 1) {
            return significand.signum() == 0 ? new Infinity(negative)
                    : new NotANumber(negative, !significand.testBit(fraction - 1));
        }
        return exponent == 0
                ? new Finite(Ratio.binary(negative, significand, 1 - bias - fraction))
                : new Finite(Ratio.binary(negative, significand.or(bit(fraction)),
                        exponent - bias - fraction));
    }

    static BigInteger binaryInfinity(boolean negative, int width) {
        return sign(negative, width).or(
                BigInteger.valueOf(2L * binaryBias(width) + 1).shiftLeft(binaryFraction(width)));
    }

    static Encoded encodeBinary(Ratio v, int width, Rounding mode) {
        int precision = binaryFraction(width);
        int bias = binaryBias(width);
        BigInteger sign = sign(v.negative(), width);
        if (v.isZero()) {
            return new Encoded(sign, 0);
        }
        int quantum = Math.max(v.floorLog2(), 1 - bias) - precision;
        Rounded rounded = v.quantize(quantum, mode);
        BigInteger q = rounded.q();
        if (q.bitLength() > precision + 1) {
            q = q.shiftRight(1);
            quantum++;
        }
        int actual = quantum + precision;
        if (actual > bias) {
            boolean infinity = switch (mode) {
                case ZERO, PREPARE -> false;
                case POSITIVE -> !v.negative();
                case NEGATIVE -> v.negative();
                default -> true;
            };
            BigInteger all = BigInteger.valueOf(2L * bias + 1);
            BigInteger magnitude = infinity ? all.shiftLeft(precision)
                    : all.subtract(BigInteger.ONE).shiftLeft(precision).or(mask(precision));
            return new Encoded(sign.or(magnitude), OVERFLOW | INEXACT);
        }
        boolean normalized = q.testBit(precision);
        BigInteger exponent = normalized ? BigInteger.valueOf(actual + bias) : BigInteger.ZERO;
        int flags = (rounded.inexact() ? INEXACT : 0)
                | (rounded.inexact() && !normalized ? UNDERFLOW : 0);
        return new Encoded(sign.or(exponent.shiftLeft(precision)).or(q.and(mask(precision))),
                flags);
    }

    /**
     * 平方根を、求める精度の単位まで整数で開いてから丸める。
     *
     * <p>平方根はちょうど半分の位置には来ない (来るなら元の値が精度を超えている) ので、
     * 最近接の 2 つの規則はここでは同じ結果になる。
     */
    static Encoded squareRootBinary(Ratio v, int width, Rounding mode) {
        if (v.isZero()) {
            return new Encoded(sign(v.negative(), width), 0);
        }
        int precision = binaryFraction(width);
        int quantum = Math.floorDiv(v.floorLog2(), 2) - precision;
        BigInteger n = quantum < 0 ? v.n().shiftLeft(-2 * quantum) : v.n();
        BigInteger d = quantum < 0 ? v.d() : v.d().shiftLeft(2 * quantum);
        BigInteger q = n.divide(d).sqrt();
        boolean exact = q.multiply(q).multiply(d).equals(n);
        if (!exact) {
            BigInteger twice = q.shiftLeft(1).add(BigInteger.ONE);
            boolean aboveHalf = n.shiftLeft(2).compareTo(twice.multiply(twice).multiply(d)) > 0;
            boolean increase = switch (mode) {
                case NEAREST_EVEN, NEAREST_AWAY, NEAREST_ZERO -> aboveHalf;
                case POSITIVE, AWAY -> true;
                case PREPARE -> !q.testBit(0);
                default -> false;
            };
            if (increase) {
                q = q.add(BigInteger.ONE);
            }
        }
        Encoded encoded = encodeBinary(Ratio.binary(false, q, quantum), width, Rounding.ZERO);
        return new Encoded(encoded.raw(), exact ? 0 : INEXACT);
    }

    // --- HFP (16 進) ---

    static int hexPrecision(int width) {
        return width == 16 ? 112 : width * 8 - 8;
    }

    /** 特性から 64 を引いた指数。 */
    static int hexExponent(BigInteger raw, int width) {
        return (raw.shiftRight(width * 8 - 8).intValue() & 127) - 64;
    }

    static Ratio decodeHex(BigInteger raw, int width) {
        int precision = hexPrecision(width);
        boolean negative = raw.testBit(width * 8 - 1);
        // 拡張形式は 2 つの長形式である。下の符号と特性は値に加わらない
        BigInteger fraction = width == 16
                ? raw.shiftRight(64).and(mask(56)).shiftLeft(56).or(raw.and(mask(56)))
                : raw.and(mask(precision));
        return Ratio.binary(negative, fraction, 4 * hexExponent(raw, width) - precision);
    }

    static BigInteger hexRaw(boolean negative, BigInteger q, int exponent, int width) {
        BigInteger characteristic = BigInteger.valueOf((exponent + 64) & 127);
        if (width != 16) {
            return sign(negative, width).or(characteristic.shiftLeft(width * 8 - 8)).or(q);
        }
        // 下の特性は上より 14 小さい (Principles of Operation "Extended HFP")
        BigInteger low = BigInteger.valueOf((exponent + 64 + 114) & 127);
        BigInteger half = negative ? bit(63) : BigInteger.ZERO;
        return half.or(characteristic.shiftLeft(56)).or(q.shiftRight(56)).shiftLeft(64)
                .or(half).or(low.shiftLeft(56)).or(q.and(mask(56)));
    }

    static BigInteger hexZero(boolean negative, int width) {
        BigInteger sign = sign(negative, width);
        return width == 16 && negative ? sign.or(bit(63)) : sign;
    }

    static BigInteger hexMaximum(boolean negative, int width) {
        return hexRaw(negative, mask(hexPrecision(width)), 63, width);
    }

    /** HFP の演算の結果。{@code exception} は指数のあふれ・下位あふれの名前、無ければ {@code null}。 */
    record HexResult(BigInteger raw, String exception) {
    }

    /**
     * 正規化して詰める。HFP の演算は切り捨てである。
     *
     * @param forced       正規化しない加算が使う指数。無ければ {@code null}
     * @param underflowMask プログラムマスクの指数下位あふれのビット。0 なら真の 0 にする
     */
    static HexResult packHex(Ratio value, int width, Integer forced, Rounding rounding,
                             boolean underflowMask) {
        if (value.isZero()) {
            return new HexResult(BigInteger.ZERO, null);
        }
        int precision = hexPrecision(width);
        int e = forced != null ? forced : Math.floorDiv(value.floorLog2(), 4) + 1;
        BigInteger q = value.quantize(4 * e - precision, rounding).q();
        if (q.bitLength() > precision) {
            q = q.shiftRight(4);
            e++;
        }
        if (e < -64 && !underflowMask) {
            return new HexResult(BigInteger.ZERO, null);
        }
        return new HexResult(hexRaw(value.negative(), q, e, width),
                e > 63 ? "HFP exponent overflow" : e < -64 ? "HFP exponent underflow" : null);
    }

    /** HFP の平方根。結果は最近接へ丸める (Principles of Operation "SQUARE ROOT")。 */
    static Ratio squareRootHex(Ratio v, int width) {
        int precision = hexPrecision(width);
        int exponent = Math.floorDiv(Math.floorDiv(v.floorLog2(), 2), 4) + 1;
        int quantum = 4 * exponent - precision;
        BigInteger n = quantum < 0 ? v.n().shiftLeft(-2 * quantum) : v.n();
        BigInteger d = quantum < 0 ? v.d() : v.d().shiftLeft(2 * quantum);
        BigInteger q = n.divide(d).sqrt();
        BigInteger twice = q.shiftLeft(1).add(BigInteger.ONE);
        if (n.shiftLeft(2).compareTo(twice.multiply(twice).multiply(d)) >= 0) {
            q = q.add(BigInteger.ONE);
        }
        return Ratio.binary(false, q, quantum);
    }

    // --- DFP (IEEE 754 の 10 進、DPD 符号化) ---

    static int decimalPrecision(int width) {
        return width == 4 ? 7 : width == 8 ? 16 : 34;
    }

    static int decimalBias(int width) {
        return width == 4 ? 101 : width == 8 ? 398 : 6176;
    }

    static int decimalExponentBits(int width) {
        return width == 4 ? 6 : width == 8 ? 8 : 12;
    }

    static int decimalMaximumExponent(int width) {
        return (3 << decimalExponentBits(width)) - 1 - decimalBias(width);
    }

    /** DPD の 10 ビットを 10 進 3 桁へ戻す。冗長な 24 通りは使わない上位ビットを無視する。 */
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

    /** DFP の値と、その指数 (量子)。 */
    record DecimalValue(Value value, int exponent) {
    }

    static DecimalValue decodeDecimal(BigInteger raw, int width) {
        int precision = decimalPrecision(width);
        int expbits = decimalExponentBits(width);
        int trailing = (precision - 1) / 3 * 10;
        int combination = raw.shiftRight(width * 8 - 6).intValue() & 31;
        boolean negative = raw.testBit(width * 8 - 1);
        if (combination == 30) {
            return new DecimalValue(new Infinity(negative), 0);
        }
        if (combination == 31) {
            return new DecimalValue(new NotANumber(negative, raw.testBit(width * 8 - 7)), 0);
        }
        int high = combination < 24 ? combination >> 3 : (combination >> 1) & 3;
        int leading = combination < 24 ? combination & 7 : 8 | (combination & 1);
        int exponent = ((high << expbits)
                | (raw.shiftRight(trailing).intValue() & ((1 << expbits) - 1)))
                - decimalBias(width);
        BigInteger coefficient = BigInteger.valueOf(leading);
        BigInteger thousand = BigInteger.valueOf(1000);
        for (int at = trailing / 10 - 1; at >= 0; at--) {
            coefficient = coefficient.multiply(thousand).add(BigInteger.valueOf(
                    undeclet(raw.shiftRight(at * 10).intValue() & 1023)));
        }
        return new DecimalValue(new Finite(Ratio.decimal(negative, coefficient, exponent)),
                exponent);
    }

    static BigInteger decimalInfinity(boolean negative, int width) {
        return sign(negative, width).or(BigInteger.valueOf(30).shiftLeft(width * 8 - 6));
    }

    static BigInteger packDecimal(boolean negative, BigInteger coefficient, int exponent,
                                  int width) {
        int precision = decimalPrecision(width);
        int expbits = decimalExponentBits(width);
        String digits = coefficient.toString();
        digits = "0".repeat(precision - digits.length()) + digits;
        int msd = digits.charAt(0) - '0';
        int biased = exponent + decimalBias(width);
        int high = biased >> expbits;
        int combination = msd < 8 ? (high << 3) | msd : 24 | (high << 1) | (msd & 1);
        BigInteger continuation = BigInteger.ZERO;
        for (int at = 1; at < precision; at += 3) {
            continuation = continuation.shiftLeft(10).or(BigInteger.valueOf(
                    FloatingConstants.declet(digits.charAt(at) - '0', digits.charAt(at + 1) - '0',
                            digits.charAt(at + 2) - '0')));
        }
        int trailing = (precision - 1) / 3 * 10;
        return sign(negative, width)
                .or(BigInteger.valueOf(combination).shiftLeft(width * 8 - 6))
                .or(BigInteger.valueOf(biased & ((1 << expbits) - 1)).shiftLeft(trailing))
                .or(continuation);
    }

    /** 係数と指数を、形式の桁数と指数の範囲へ収める。 */
    private static BigInteger fitDecimal(boolean negative, BigInteger n, int exponent, int width,
                                         Rounding mode) {
        int precision = decimalPrecision(width);
        int bias = decimalBias(width);
        int maximum = decimalMaximumExponent(width);
        int significant = n.toString().length();
        int shift = Math.max(Math.max(significant - precision, -bias - exponent), 0);
        if (shift > 0) {
            n = roundQuotient(n, pow10(Math.min(shift, significant + 1)), negative, mode, 10).q();
            exponent += shift;
        }
        if (n.toString().length() > precision) {
            n = n.divide(BigInteger.TEN);
            exponent++;
        }
        if (exponent > maximum && n.signum() != 0) {
            // 指数が大きすぎても、係数に空きがあれば 0 を足して収まる (clamping)
            int space = precision - n.toString().length();
            int clamp = Math.min(exponent - maximum, space);
            n = n.multiply(pow10(clamp));
            exponent -= clamp;
            if (exponent > maximum) {
                boolean finite = mode == Rounding.ZERO || mode == Rounding.PREPARE
                        || (mode == Rounding.POSITIVE && negative)
                        || (mode == Rounding.NEGATIVE && !negative);
                return finite
                        ? packDecimal(negative, pow10(precision).subtract(BigInteger.ONE), maximum,
                                width)
                        : decimalInfinity(negative, width);
            }
        }
        return packDecimal(negative, n, Math.max(-bias, Math.min(maximum, exponent)), width);
    }

    /**
     * 有限の値を DFP にする。
     *
     * <p>結果が正確に表せるときは、係数の後ろの 0 を落として指数を {@code preferred}
     * (望ましい量子) へ近づける。{@code 1.20 × 2.0} が {@code 2.400} のままになるのは
     * このためである (Principles of Operation "Preferred Quantum")。
     */
    static Encoded encodeDecimal(Ratio v, int preferred, int width, Rounding mode) {
        int precision = decimalPrecision(width);
        int bias = decimalBias(width);
        int maximum = decimalMaximumExponent(width);
        if (v.isZero()) {
            int e = Math.max(-bias, Math.min(maximum, preferred));
            return new Encoded(packDecimal(v.negative(), BigInteger.ZERO, e, width), 0);
        }
        int order = v.floorLog10();
        boolean tiny = order < -bias + precision - 1;
        int e = Math.max(order - (precision - 1), -bias);
        Rounded rounded = v.quantize10(e, mode);
        BigInteger q = rounded.q();
        if (q.toString().length() > precision) {
            q = q.divide(BigInteger.TEN);
            e++;
        }
        boolean overflow = e > maximum;
        int flags = (rounded.inexact() || overflow ? INEXACT : 0) | (overflow ? OVERFLOW : 0)
                | (tiny && rounded.inexact() ? UNDERFLOW : 0);
        if (!rounded.inexact()) {
            while (e < preferred && e < maximum && q.signum() != 0
                    && q.mod(BigInteger.TEN).signum() == 0) {
                q = q.divide(BigInteger.TEN);
                e++;
            }
        }
        BigInteger raw = fitDecimal(v.negative(), q, e, width, mode);
        if ((flags & INEXACT) != 0) {
            Value delivered = decodeDecimal(raw, width).value();
            boolean increased = delivered instanceof Infinity
                    || (delivered instanceof Finite f && f.value().compareMagnitude(v) > 0);
            flags |= increased ? INCREMENTED : 0;
        }
        return new Encoded(raw, flags);
    }

    // --- PFPO (PERFORM FLOATING-POINT OPERATION) ---

    /** 形式の番号 (PFPO の演算項の型) と幅。 */
    record Shape(Radix radix, int width) {

        static Shape of(int code) {
            return switch (code) {
                case 0 -> new Shape(Radix.HEX, 4);
                case 1 -> new Shape(Radix.HEX, 8);
                case 2 -> new Shape(Radix.HEX, 16);
                case 5 -> new Shape(Radix.BINARY, 4);
                case 6 -> new Shape(Radix.BINARY, 8);
                case 7 -> new Shape(Radix.BINARY, 16);
                case 8 -> new Shape(Radix.DECIMAL, 4);
                case 9 -> new Shape(Radix.DECIMAL, 8);
                case 10 -> new Shape(Radix.DECIMAL, 16);
                default -> null;
            };
        }

        Value decode(BigInteger raw) {
            return switch (radix) {
                case HEX -> new Finite(decodeHex(raw, width));
                case BINARY -> decodeBinary(raw, width);
                case DECIMAL -> decodeDecimal(raw, width).value();
            };
        }
    }

    /**
     * 基数をまたぐ変換の結果。
     *
     * @param quantum DFP の結果の量子が望ましいものと違う
     */
    record Converted(BigInteger raw, int flags, boolean quantum) {
    }

    /**
     * 有限の値をほかの基数へ変換する。
     *
     * @param controls PFPO の関数コードの下位 8 ビット
     */
    static Converted convert(Ratio source, Shape target, Rounding rounding, int controls) {
        int width = target.width();
        switch (target.radix()) {
            case HEX -> {
                if (source.isZero()) {
                    return new Converted(hexZero(source.negative(), width), 0, false);
                }
                int precision = hexPrecision(width);
                int e = Math.floorDiv(source.floorLog2(), 4) + 1;
                int quantum = 4 * e - precision;
                BigInteger q = source.quantize(quantum, rounding).q();
                boolean inexact = Ratio.binary(source.negative(), q, quantum)
                        .compareMagnitude(source) != 0;
                if (q.bitLength() > precision) {
                    q = q.shiftRight(4);
                    e++;
                }
                if (e > 63) {
                    // HFP のあふれは、あふれの制御が立っていれば最大値と旗、無ければ無効演算
                    return new Converted(hexMaximum(source.negative(), width),
                            (controls & 0x20) != 0 ? OVERFLOW | INEXACT : INVALID, false);
                }
                if (e < -64) {
                    return new Converted(hexZero(source.negative(), width),
                            INEXACT | ((controls & 0x10) != 0 ? UNDERFLOW : 0), false);
                }
                return new Converted(hexRaw(source.negative(), q, e, width),
                        inexact ? INEXACT : 0, false);
            }
            case BINARY -> {
                Encoded encoded = encodeBinary(source, width, rounding);
                int flags = encoded.flags();
                // PFPO は丸める前に小ささを見る。丸めて最小の正規化数になる値も含む
                if (!source.isZero() && (flags & INEXACT) != 0
                        && source.floorLog2() < 1 - binaryBias(width)) {
                    flags |= UNDERFLOW;
                }
                return new Converted(encoded.raw(), flags, false);
            }
            default -> {
                int preferred = (controls & 0x10) != 0 ? 0 : decimalMaximumExponent(width);
                Encoded encoded = encodeDecimal(source, preferred, width, rounding);
                int flags = encoded.flags() & 0xF8;
                boolean quantum = (flags & INEXACT) != 0
                        || (decodeDecimal(encoded.raw(), width).value() instanceof Finite
                                && decodeDecimal(encoded.raw(), width).exponent() > 0);
                return new Converted(encoded.raw(), flags, quantum);
            }
        }
    }

    /** 無限大をほかの基数へ変換する。HFP は無限大を持たないので、最大値と無効演算になる。 */
    static Converted convertInfinity(boolean negative, Shape target) {
        return switch (target.radix()) {
            case HEX -> new Converted(hexMaximum(negative, target.width()), INVALID, false);
            case BINARY -> new Converted(binaryInfinity(negative, target.width()), 0, false);
            case DECIMAL -> new Converted(decimalInfinity(negative, target.width()), 0, false);
        };
    }

    /** DFP の丸めの指定。0 は FPC の DFP 丸めモードを使う。 */
    static Rounding decimalRounding(int mask, int control) {
        int mode = mask == 0 || mask == 2 ? 8 + ((control >> 4) & 7) : mask;
        return switch (mode) {
            case 1, 12 -> Rounding.NEAREST_AWAY;
            case 3, 15 -> Rounding.PREPARE;
            case 4, 8 -> Rounding.NEAREST_EVEN;
            case 5, 9 -> Rounding.ZERO;
            case 6, 10 -> Rounding.POSITIVE;
            case 7, 11 -> Rounding.NEGATIVE;
            case 13 -> Rounding.NEAREST_ZERO;
            default -> Rounding.AWAY;
        };
    }

    /** FPC の BFP 丸めモード。 */
    static Rounding binaryRounding(int control) {
        return switch (control & 7) {
            case 0 -> Rounding.NEAREST_EVEN;
            case 1 -> Rounding.ZERO;
            case 2 -> Rounding.POSITIVE;
            case 3 -> Rounding.NEGATIVE;
            case 7 -> Rounding.PREPARE;
            default -> throw new MachineException(MachineException.SPECIFICATION,
                    "the BFP rounding mode in the FPC is reserved");
        };
    }
}
