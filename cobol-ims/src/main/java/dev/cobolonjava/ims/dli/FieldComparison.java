package dev.cobolonjava.ims.dli;

import dev.cobolonjava.ims.dbd.FieldType;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * SSA の値とフィールドの値を、DBD の {@code TYPE=} で比べる。
 *
 * <p>{@code C} / {@code X} はバイトの並び (符号なし) で、数の型は数で比べる。{@code F} の -5 は
 * バイトの並びなら 200 より大きいが、数なら小さい。
 */
final class FieldComparison {

    private FieldComparison() {
    }

    static int compare(FieldType type, byte[] actual, byte[] expected) {
        return switch (type) {
            case C, X -> Arrays.compareUnsigned(actual, expected);
            case F, H -> Long.compare(signed(actual), signed(expected));
            case P -> packed(actual).compareTo(packed(expected));
            case Z -> zoned(actual).compareTo(zoned(expected));
        };
    }

    private static long signed(byte[] bytes) {
        long value = bytes[0];
        for (int i = 1; i < bytes.length; i++) {
            value = (value << 8) | (bytes[i] & 0xFF);
        }
        return value;
    }

    private static BigInteger packed(byte[] bytes) {
        BigInteger value = BigInteger.ZERO;
        int last = bytes.length - 1;
        for (int i = 0; i <= last; i++) {
            int high = (bytes[i] >> 4) & 0x0F;
            int low = bytes[i] & 0x0F;
            value = value.multiply(BigInteger.TEN).add(BigInteger.valueOf(digit(high, "packed decimal")));
            if (i < last) {
                value = value.multiply(BigInteger.TEN).add(BigInteger.valueOf(digit(low, "packed decimal")));
            } else {
                value = signed(value, low, "packed decimal");
            }
        }
        return value;
    }

    private static BigInteger zoned(byte[] bytes) {
        BigInteger value = BigInteger.ZERO;
        int last = bytes.length - 1;
        for (int i = 0; i <= last; i++) {
            value = value.multiply(BigInteger.TEN)
                    .add(BigInteger.valueOf(digit(bytes[i] & 0x0F, "zoned decimal")));
        }
        return signed(value, (bytes[last] >> 4) & 0x0F, "zoned decimal");
    }

    private static int digit(int nibble, String what) {
        if (nibble > 9) {
            throw new DliCallException("invalid " + what + " data in an SSA qualification");
        }
        return nibble;
    }

    private static BigInteger signed(BigInteger magnitude, int sign, String what) {
        return switch (sign) {
            case 0x0B, 0x0D -> magnitude.negate();
            case 0x0A, 0x0C, 0x0E, 0x0F -> magnitude;
            default -> throw new DliCallException("invalid " + what + " sign in an SSA qualification");
        };
    }
}
