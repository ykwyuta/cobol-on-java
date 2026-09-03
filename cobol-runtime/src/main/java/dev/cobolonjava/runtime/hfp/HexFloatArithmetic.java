package dev.cobolonjava.runtime.hfp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * IBM 16 進浮動小数点 (HFP) の演算 (要件 FR-032)。
 *
 * <h2>加減算は「厳密に計算してから丸める」のでは<b>足りない</b></h2>
 *
 * <p>ハードウェアは加減算の前に指数を揃える。小さいほうのオペランドを 16 進桁単位で右へずらし、
 * <b>ガード桁 1 桁だけを残して、あふれた桁を捨てる</b>。したがって指数差が大きい場合、
 * 小さいほうは計算に入る前にゼロになる。
 *
 * <p>この違いは実測で確認された。長形式で {@code 1 - 1e-17} を計算すると、
 * 実機は {@code 4110000000000000} (ちょうど 1.0) を返す。揃えた時点で減数が消えるためである。
 * 一方「厳密に引いてから切り捨てる」と {@code 40FFFFFFFFFFFFFF} (1.0 のひとつ下) になる。
 * 1 ULP の差だが、繰り返し計算では蓄積する。
 *
 * <h2>乗除算は「厳密に計算してから切り捨てる」で一致する</h2>
 *
 * <p>乗算は桁を捨てる前処理がなく、積を求めてから正規化・切り捨てを行う。
 * 除算も商を求めてから切り捨てる。いずれも実測で一致を確認している。
 */
public final class HexFloatArithmetic {

    /** 除算の中間精度。切り捨ての位置に影響しないだけの桁数を取る。 */
    private static final MathContext DIVIDE_PRECISION = new MathContext(80, RoundingMode.DOWN);

    private static final BigInteger SIXTEEN = BigInteger.valueOf(16);

    private HexFloatArithmetic() {
    }

    /** 長形式の加算。ホストの {@code AD} 命令に対応する。 */
    public static byte[] addLong(byte[] a, byte[] b) {
        return addOrSubtract(a, b, false, HexFloat.LONG_BYTES, HexFloat.longFractionDigits());
    }

    /** 長形式の減算。ホストの {@code SD} 命令に対応する。 */
    public static byte[] subtractLong(byte[] a, byte[] b) {
        return addOrSubtract(a, b, true, HexFloat.LONG_BYTES, HexFloat.longFractionDigits());
    }

    /** 短形式の加算。ホストの {@code AE} 命令に対応する。 */
    public static byte[] addShort(byte[] a, byte[] b) {
        return addOrSubtract(a, b, false, HexFloat.SHORT_BYTES, HexFloat.shortFractionDigits());
    }

    /** 短形式の減算。ホストの {@code SE} 命令に対応する。 */
    public static byte[] subtractShort(byte[] a, byte[] b) {
        return addOrSubtract(a, b, true, HexFloat.SHORT_BYTES, HexFloat.shortFractionDigits());
    }

    /** 長形式の乗算。ホストの {@code MD} 命令に対応する。 */
    public static byte[] multiplyLong(byte[] a, byte[] b) {
        return HexFloat.encodeLong(HexFloat.decodeLong(a).multiply(HexFloat.decodeLong(b)));
    }

    /** 長形式の除算。ホストの {@code DD} 命令に対応する。 */
    public static byte[] divideLong(byte[] a, byte[] b) {
        BigDecimal divisor = HexFloat.decodeLong(b);
        if (divisor.signum() == 0) {
            throw new ArithmeticException("HFP divide by zero");
        }
        return HexFloat.encodeLong(HexFloat.decodeLong(a).divide(divisor, DIVIDE_PRECISION));
    }

    /** 短形式の乗算。ホストの {@code ME} 命令に対応する。 */
    public static byte[] multiplyShort(byte[] a, byte[] b) {
        return HexFloat.encodeShort(HexFloat.decodeShort(a).multiply(HexFloat.decodeShort(b)));
    }

    /** 短形式の除算。ホストの {@code DE} 命令に対応する。 */
    public static byte[] divideShort(byte[] a, byte[] b) {
        BigDecimal divisor = HexFloat.decodeShort(b);
        if (divisor.signum() == 0) {
            throw new ArithmeticException("HFP divide by zero");
        }
        return HexFloat.encodeShort(HexFloat.decodeShort(a).divide(divisor, DIVIDE_PRECISION));
    }

    /**
     * 加減算。ハードウェアの手順をそのままなぞる。
     *
     * <ol>
     *   <li>小数部をガード桁 1 桁ぶん左へ広げる</li>
     *   <li>特性の小さいほうを差の桁数だけ右へずらす。あふれた桁は捨てる</li>
     *   <li>符号を反映して加算する</li>
     *   <li>桁あふれがあれば右へ 1 桁ずらし、特性を 1 増やす</li>
     *   <li>先頭の 16 進桁が 0 でなくなるまで左へずらし、そのぶん特性を減らす</li>
     *   <li>ガード桁を落として小数部の桁数へ収める</li>
     * </ol>
     */
    private static byte[] addOrSubtract(byte[] a, byte[] b, boolean subtract,
                                        int byteLength, int fractionDigits) {
        HexFloat.Parts pa = parts(a, byteLength);
        HexFloat.Parts pb = parts(b, byteLength);

        boolean negativeB = subtract != pb.negative();

        // ゼロのオペランドは相手をそのまま返す。特性が意味を持たないため揃えられない
        if (pa.isZero() && pb.isZero()) {
            return new byte[byteLength];
        }
        if (pa.isZero()) {
            return fromParts(new HexFloat.Parts(negativeB, pb.characteristic(), pb.fraction()),
                    byteLength);
        }
        if (pb.isZero()) {
            return fromParts(pa, byteLength);
        }

        // ガード桁 1 桁ぶん広げる
        BigInteger ga = pa.fraction().multiply(SIXTEEN);
        BigInteger gb = pb.fraction().multiply(SIXTEEN);

        int characteristic = Math.max(pa.characteristic(), pb.characteristic());
        ga = shiftRightHexDigits(ga, characteristic - pa.characteristic());
        gb = shiftRightHexDigits(gb, characteristic - pb.characteristic());

        BigInteger sum = (pa.negative() ? ga.negate() : ga)
                .add(negativeB ? gb.negate() : gb);
        if (sum.signum() == 0) {
            return new byte[byteLength];
        }

        boolean negative = sum.signum() < 0;
        BigInteger magnitude = sum.abs();

        // 広げた小数部は fractionDigits + 1 桁である。この範囲へ正規化する
        BigInteger upper = SIXTEEN.pow(fractionDigits + 1);
        BigInteger lower = SIXTEEN.pow(fractionDigits);
        while (magnitude.compareTo(upper) >= 0) {
            magnitude = magnitude.divide(SIXTEEN);
            characteristic++;
        }
        while (magnitude.compareTo(lower) < 0) {
            magnitude = magnitude.multiply(SIXTEEN);
            characteristic--;
        }

        // ガード桁を落とす
        BigInteger fraction = magnitude.divide(SIXTEEN);
        if (fraction.signum() == 0) {
            return new byte[byteLength];
        }
        return fromParts(new HexFloat.Parts(negative, characteristic, fraction), byteLength);
    }

    /** 16 進桁単位の右シフト。あふれた桁は捨てる。 */
    private static BigInteger shiftRightHexDigits(BigInteger value, int digits) {
        if (digits <= 0) {
            return value;
        }
        if (digits > 32) {
            // これ以上ずらしても必ずゼロになる
            return BigInteger.ZERO;
        }
        return value.divide(SIXTEEN.pow(digits));
    }

    private static HexFloat.Parts parts(byte[] bytes, int byteLength) {
        return byteLength == HexFloat.LONG_BYTES
                ? HexFloat.partsLong(bytes) : HexFloat.partsShort(bytes);
    }

    private static byte[] fromParts(HexFloat.Parts parts, int byteLength) {
        return byteLength == HexFloat.LONG_BYTES
                ? HexFloat.fromPartsLong(parts) : HexFloat.fromPartsShort(parts);
    }
}
