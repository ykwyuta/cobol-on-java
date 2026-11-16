package dev.cobolonjava.runtime.data;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** COBOL UTF-8 項目の記憶域、文字境界、照合。 */
public final class Utf8 {
    private static final byte SPACE = 0x20;
    private static final byte[] HIGH = {(byte) 0xF4, (byte) 0x8F, (byte) 0xBF, (byte) 0xBF};

    private Utf8() {
    }

    public static byte[] fromDisplay(byte[] bytes, CodePage page) {
        return page.decode(bytes).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] toDisplay(byte[] bytes, CodePage page) {
        return page.encode(new String(bytes, StandardCharsets.UTF_8));
    }

    public static byte[] fromNational(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_16BE).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] toNational(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_16BE);
    }

    public static Decimal length(byte[] bytes) {
        int count = 0;
        for (int offset = 0; offset < bytes.length; count++) offset += width(bytes[offset]);
        return Decimal.of(count, 0);
    }

    public static int compare(byte[] left, byte[] right) {
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = i < left.length ? left[i] & 0xFF : SPACE;
            int b = i < right.length ? right[i] & 0xFF : SPACE;
            if (a != b) return Integer.compare(a, b);
        }
        return 0;
    }

    public static void move(byte[] source, Storage storage, int offset, int size, int characters) {
        storage.view(offset, size).setBytes(fit(source, size, characters, false));
    }

    /** 文字数の指定がなければ characters は 0。 */
    public static byte[] fit(byte[] source, int size, int characters, boolean repeat) {
        byte[] result = new byte[size];
        Arrays.fill(result, SPACE);
        for (int to = 0, from = 0, count = 0;
                from < source.length && (characters == 0 || count < characters); count++) {
            int width = width(source[from]);
            if (width > size - to || width > source.length - from) break;
            System.arraycopy(source, from, result, to, width);
            to += width;
            from += width;
            if (repeat && from == source.length) from = 0;
        }
        return result;
    }

    public static byte[] high(int size, int characters) {
        byte[] result = fit(HIGH, size, characters, true);
        if (characters == 0) {
            switch (size % 4) {
                case 1 -> result[size - 1] = 0x7F;
                case 2 -> {
                    result[size - 2] = (byte) 0xDF;
                    result[size - 1] = (byte) 0xBF;
                }
                case 3 -> {
                    result[size - 3] = (byte) 0xEF;
                    result[size - 2] = (byte) 0xBF;
                    result[size - 1] = (byte) 0xBF;
                }
                default -> { }
            }
        }
        return result;
    }

    public static int prefixLength(byte[] source, int limit) {
        int end = Math.min(source.length, limit);
        while (end > 0 && end < source.length && (source[end] & 0xC0) == 0x80) end--;
        return end;
    }

    private static int width(byte lead) {
        int code = lead & 0xFF;
        if (code >= 0xC0 && code <= 0xDF) return 2;
        if (code >= 0xE0 && code <= 0xEF) return 3;
        if (code >= 0xF0 && code <= 0xF7) return 4;
        return 1;
    }
}
