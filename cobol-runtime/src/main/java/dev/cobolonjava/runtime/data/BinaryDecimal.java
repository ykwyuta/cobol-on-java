package dev.cobolonjava.runtime.data;

import dev.cobolonjava.runtime.decimal.Decimal;
import java.math.BigInteger;

/**
 * 2 進項目 ({@code USAGE COMPUTATIONAL} / {@code BINARY} / {@code COMPUTATIONAL-4} /
 * {@code COMPUTATIONAL-5}) の符号化と復号 (要件 FR-031, FR-045)。
 *
 * <p>バイト順は<b>ビッグエンディアン</b>、負数は 2 の補数で表現する。
 * 記憶域の幅は PICTURE の桁数から決まり、値域の制限は {@link TruncMode} に従う。
 */
public final class BinaryDecimal {

    private BinaryDecimal() {
    }

    /**
     * 指定桁数の 2 進項目が占めるバイト長。
     *
     * <p><b>暫定対応</b>: 19〜31 桁 ({@code ARITH(EXTEND)} 時の 16 バイト項目) は未対応である。
     * provisional.md の P-005 に記録している。
     */
    public static int byteLength(int digits) {
        if (digits >= 1 && digits <= 4) {
            return 2;
        }
        if (digits <= 9) {
            return 4;
        }
        if (digits <= 18) {
            return 8;
        }
        throw new UnsupportedOperationException(
                "binary items of " + digits + " digits (16-byte storage) are not implemented yet");
    }

    /**
     * 値を 2 進のバイト列へ符号化する。
     *
     * @param truncMode {@link TruncMode#OPT} は事前に
     *                  {@link TruncMode#resolve} で解決しておかなければならない
     */
    public static byte[] encode(Decimal value, int digits, int scale, TruncMode truncMode) {
        if (truncMode == TruncMode.OPT) {
            throw new IllegalArgumentException(
                    "TRUNC(OPT) must be resolved via TruncMode.resolve(UndefinedBehavior) before encoding");
        }
        int length = byteLength(digits);
        BigInteger unscaled = value.rescale(scale,
                dev.cobolonjava.runtime.decimal.CobolRounding.TRUNCATION).signedUnscaled();

        if (truncMode == TruncMode.STD) {
            // PICTURE の桁数へ 10 進的に切り捨てる。符号は保つ。
            BigInteger limit = BigInteger.TEN.pow(digits);
            BigInteger magnitude = unscaled.abs().mod(limit);
            unscaled = unscaled.signum() < 0 ? magnitude.negate() : magnitude;
        }

        // 記憶域の幅へ 2 の補数で丸め込む
        BigInteger modulus = BigInteger.ONE.shiftLeft(length * 8);
        BigInteger wrapped = unscaled.mod(modulus);
        byte[] out = new byte[length];
        for (int i = length - 1; i >= 0; i--) {
            out[i] = wrapped.and(BigInteger.valueOf(0xFF)).byteValue();
            wrapped = wrapped.shiftRight(8);
        }
        return out;
    }

    /** 2 進のバイト列を値へ復号する。 */
    public static Decimal decode(byte[] bytes, int scale) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("empty binary field");
        }
        return Decimal.of(new BigInteger(bytes), scale);
    }
}
