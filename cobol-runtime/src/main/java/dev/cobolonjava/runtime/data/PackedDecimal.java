package dev.cobolonjava.runtime.data;

import dev.cobolonjava.runtime.decimal.DataException;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.math.BigInteger;

/**
 * パック10進数 ({@code USAGE COMPUTATIONAL-3} / {@code PACKED-DECIMAL}) の符号化と復号 (要件 FR-031)。
 *
 * <p>1 バイトに 2 桁を格納し、最終バイトの下位ニブルを符号とする。したがってバイト長は
 * {@code 桁数 / 2 + 1} であり、桁数が偶数のときは先頭に未使用のニブルが 1 個生じる。
 * この未使用ニブルはホストでは 0 で埋められ、集団項目としてバイト比較した際に差が出るため、
 * L3 互換の対象である。
 */
public final class PackedDecimal {

    private PackedDecimal() {
    }

    /** 指定桁数のパック10進項目が占めるバイト長。 */
    public static int byteLength(int digits) {
        if (digits < 1) {
            throw new IllegalArgumentException("digits must be positive: " + digits);
        }
        return digits / 2 + 1;
    }

    /**
     * 値をパック10進のバイト列へ符号化する。
     *
     * <p>指定桁数に収まらない上位桁は切り捨てられる。これは {@code ON SIZE ERROR} を
     * 指定しない場合にホストで実際に起きる挙動である。呼び出し側は事前に
     * {@link Decimal#fitsIn(int, int)} で判定して SIZE ERROR 条件を立てられる (要件 FR-043)。
     *
     * @param value    格納する値
     * @param digits   PICTURE の総桁数
     * @param scale    小数部の桁数
     * @param signed   PICTURE に {@code S} があるか。ない場合は絶対値が格納され符号ニブルは {@code F} になる
     */
    public static byte[] encode(Decimal value, int digits, int scale, boolean signed) {
        int length = byteLength(digits);
        int digitNibbles = length * 2 - 1;
        String s = value.storedDigits(digits, scale);
        if (s.length() < digitNibbles) {
            s = "0".repeat(digitNibbles - s.length()) + s;
        }

        byte[] out = new byte[length];
        int nibbleIndex = 0;
        for (int i = 0; i < digitNibbles; i++, nibbleIndex++) {
            int digit = s.charAt(i) - '0';
            int byteIndex = nibbleIndex / 2;
            if (nibbleIndex % 2 == 0) {
                out[byteIndex] |= (byte) (digit << 4);
            } else {
                out[byteIndex] |= (byte) digit;
            }
        }

        int signNibble;
        if (!signed) {
            signNibble = SignNibble.UNSIGNED_PLUS;
        } else {
            signNibble = value.sign() < 0 ? SignNibble.PREFERRED_MINUS : SignNibble.PREFERRED_PLUS;
        }
        out[length - 1] |= (byte) signNibble;
        return out;
    }

    /**
     * パック10進のバイト列を値へ復号する。
     *
     * <p>符号が負のゼロ ({@code 0x000D} など) であった場合、符号は保持される。
     * これは {@code MOVE} でバイト列が変化しないために必要である ({@link Decimal} の設計参照)。
     *
     * @param numProc 数字ニブルの妥当性を検査するかどうかを決める (要件 FR-033)
     * @throws DataException 不正な数字ニブルまたは符号ニブルを検出した場合 (ホストの {@code S0C7} に相当)
     */
    public static Decimal decode(byte[] bytes, int scale, NumProcMode numProc) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("empty packed decimal field");
        }
        boolean check = numProc != NumProcMode.PFD;

        int signNibble = bytes[bytes.length - 1] & 0x0F;
        if (check && !SignNibble.isValid(signNibble)) {
            throw new DataException(
                    String.format("invalid sign nibble 0x%X in packed decimal field", signNibble));
        }
        int sign = SignNibble.isValid(signNibble) ? SignNibble.toSign(signNibble) : 1;

        StringBuilder digits = new StringBuilder(bytes.length * 2);
        int totalNibbles = bytes.length * 2 - 1;
        for (int i = 0; i < totalNibbles; i++) {
            int b = bytes[i / 2] & 0xFF;
            int nibble = (i % 2 == 0) ? (b >>> 4) : (b & 0x0F);
            if (nibble > 9) {
                if (check) {
                    throw new DataException(
                            String.format("invalid digit nibble 0x%X at position %d in packed decimal field",
                                    nibble, i));
                }
                // PFD では検査しない。ホストは数字として扱わないニブルをそのまま演算に持ち込むが、
                // 本処理系は下位 4 ビットを 10 で割った余りとして解釈する暫定対応を取る (provisional.md P-003)。
                nibble = nibble % 10;
            }
            digits.append((char) ('0' + nibble));
        }

        BigInteger magnitude = new BigInteger(digits.toString());
        return Decimal.of(magnitude, scale, sign);
    }
}
