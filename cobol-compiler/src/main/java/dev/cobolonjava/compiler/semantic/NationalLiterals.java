package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.data.National;

/**
 * 定数を国字のバイト列にする。{@code VALUE} と {@code INITIALIZE} と転記が同じ規則を使う。
 *
 * <p>国字の図形定数は英数字の図形定数をコードページで直したものではない。
 * {@code HIGH-VALUE} は {@code X'FFFF'} であり、IBM-1047 の {@code X'FF'} を直した
 * {@code U+009F} ではない。
 */
public final class NationalLiterals {

    private NationalLiterals() {
    }

    /**
     * 定数の国字のバイト列。図形定数と {@code ALL} は {@code length} バイトまで広げる。
     *
     * @throws IllegalArgumentException 国字にできない定数 (数字定数の小数、NULL など)
     */
    public static byte[] bytesOf(LiteralValue value, int length, CodePage codePage) {
        if (value instanceof LiteralValue.National national) {
            return national.bytes();
        }
        if (value instanceof LiteralValue.Figure figure) {
            char unit = switch (figure.constant()) {
                case SPACE -> 0x0020;
                case ZERO -> 0x0030;
                case QUOTE -> 0x0022;
                case HIGH_VALUE -> 0xFFFF;
                case LOW_VALUE -> 0x0000;
                case NULL -> throw new IllegalArgumentException(
                        "NULL cannot be used with a national item");
            };
            return National.repeat(unit, length);
        }
        if (value instanceof LiteralValue.Repeated repeated) {
            return National.repeat(
                    National.fromAlphanumeric(repeated.bytes(codePage), codePage), length);
        }
        if (value instanceof LiteralValue.Text text) {
            return National.fromAlphanumeric(text.bytes(codePage), codePage);
        }
        LiteralValue.Number number = (LiteralValue.Number) value;
        if (number.value().scale() > 0) {
            throw new IllegalArgumentException(
                    "a non-integer numeric literal cannot be used with a national item");
        }
        return National.fromAlphanumeric(codePage.encode(number.source()), codePage);
    }
}
