package dev.cobolonjava.runtime.data;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.DataException;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.math.BigInteger;

/**
 * ゾーン10進数 ({@code USAGE DISPLAY} の数値項目) の符号化と復号 (要件 FR-031)。
 *
 * <p>1 桁 1 バイトで、上位ニブルがゾーン、下位ニブルが数字である。符号は
 * {@link SignPosition} が示す位置のゾーンニブル、または符号専用バイトに置かれる。
 *
 * <p>復号時に符号位置以外のゾーンニブルを検査しないのは、ホストの {@code PACK} 命令が
 * 最右端バイト以外のゾーンを無視するためである。ゾーンが {@code F} でないバイト列
 * (例: 英数字項目から再定義された数字) も、ホストと同じく数字として読める。
 */
public final class ZonedDecimal {

    private ZonedDecimal() {
    }

    /** 指定桁数・符号位置のゾーン10進項目が占めるバイト長。 */
    public static int byteLength(int digits, SignPosition signPosition) {
        if (digits < 1) {
            throw new IllegalArgumentException("digits must be positive: " + digits);
        }
        return digits + (signPosition.isSeparate() ? 1 : 0);
    }

    /**
     * 値をゾーン10進のバイト列へ符号化する。
     *
     * <p>{@link SignPosition#UNSIGNED} の項目へ負の値を格納した場合は絶対値が格納される。
     * これは COBOL の規則どおりの挙動である。
     */
    public static byte[] encode(Decimal value, int digits, int scale,
                                SignPosition signPosition, CodePage codePage) {
        String s = value.storedDigits(digits, scale);
        int length = byteLength(digits, signPosition);
        byte[] out = new byte[length];

        int digitStart = (signPosition == SignPosition.LEADING_SEPARATE) ? 1 : 0;
        for (int i = 0; i < digits; i++) {
            out[digitStart + i] = codePage.digit(s.charAt(i) - '0');
        }

        boolean negative = signPosition.isSigned() && value.sign() < 0;
        switch (signPosition) {
            case UNSIGNED -> {
                // ゾーンはすべて既定 (EBCDIC では F) のまま
            }
            case TRAILING -> out[digits - 1] = applySignNibble(out[digits - 1], negative);
            case LEADING -> out[0] = applySignNibble(out[0], negative);
            case TRAILING_SEPARATE -> out[length - 1] = codePage.ch(negative ? '-' : '+');
            case LEADING_SEPARATE -> out[0] = codePage.ch(negative ? '-' : '+');
        }
        return out;
    }

    /**
     * ゾーン10進のバイト列を値へ復号する。
     *
     * @throws DataException 数字ニブルが 0〜9 でない場合、または符号バイトが不正な場合
     *         (ホストの {@code S0C7} に相当)
     */
    public static Decimal decode(byte[] bytes, int scale, SignPosition signPosition,
                                 CodePage codePage, NumProcMode numProc) {
        boolean check = numProc != NumProcMode.PFD;
        int length = bytes.length;
        if (length == 0) {
            throw new IllegalArgumentException("empty zoned decimal field");
        }

        int digitStart = (signPosition == SignPosition.LEADING_SEPARATE) ? 1 : 0;
        int digitCount = length - (signPosition.isSeparate() ? 1 : 0);

        int sign = 1;
        switch (signPosition) {
            case UNSIGNED -> sign = 1;
            case TRAILING -> sign = signFromZone(bytes[digitStart + digitCount - 1], check);
            case LEADING -> sign = signFromZone(bytes[digitStart], check);
            case TRAILING_SEPARATE -> sign = signFromSeparateByte(bytes[length - 1], codePage, check);
            case LEADING_SEPARATE -> sign = signFromSeparateByte(bytes[0], codePage, check);
        }

        StringBuilder digits = new StringBuilder(digitCount);
        for (int i = 0; i < digitCount; i++) {
            int nibble = bytes[digitStart + i] & 0x0F;
            if (nibble > 9) {
                if (check) {
                    throw new DataException(String.format(
                            "invalid digit nibble 0x%X at position %d in zoned decimal field", nibble, i));
                }
                nibble = nibble % 10;
            }
            digits.append((char) ('0' + nibble));
        }

        return Decimal.of(new BigInteger(digits.toString()), scale, sign);
    }

    private static byte applySignNibble(byte digitByte, boolean negative) {
        int nibble = negative ? SignNibble.PREFERRED_MINUS : SignNibble.PREFERRED_PLUS;
        return (byte) ((nibble << 4) | (digitByte & 0x0F));
    }

    private static int signFromZone(byte b, boolean check) {
        int zone = (b & 0xFF) >>> 4;
        if (!SignNibble.isValid(zone)) {
            if (check) {
                throw new DataException(
                        String.format("invalid sign zone 0x%X in zoned decimal field", zone));
            }
            return 1;
        }
        return SignNibble.toSign(zone);
    }

    private static int signFromSeparateByte(byte b, CodePage codePage, boolean check) {
        if (b == codePage.ch('-')) {
            return -1;
        }
        if (b == codePage.ch('+')) {
            return 1;
        }
        if (check) {
            throw new DataException(
                    String.format("invalid separate sign byte 0x%02X in zoned decimal field", b));
        }
        return 1;
    }
}
