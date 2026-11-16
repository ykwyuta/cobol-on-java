package dev.cobolonjava.runtime.function;

import dev.cobolonjava.runtime.decimal.Decimal;
import java.util.Arrays;

/** COBOL の Unicode 関数。UTF-8 と国字 UTF-16BE の位置は元のバイト列で数える。 */
public final class UnicodeFunctions {
    private UnicodeFunctions() {
    }

    public static Decimal measure(byte[] bytes, boolean national, int position, String operation) {
        int count = 0;
        int result = 0;
        for (int at = 0; at < bytes.length;) {
            int width = width(bytes, at, national);
            if (width == 0) {
                if (operation.equals("UVALID")) {
                    return Decimal.of(at / (national ? 2 : 1) + 1, 0);
                }
                throw invalid(at);
            }
            count++;
            if (operation.equals("USUPPLEMENTARY") && result == 0 && width == 4) {
                result = at / (national ? 2 : 1) + 1;
            } else if (position == count) {
                result = switch (operation) {
                    case "UPOS" -> at + 1;
                    case "UWIDTH" -> width;
                    default -> result;
                };
            }
            at += width;
        }
        return Decimal.of(operation.equals("ULENGTH") ? count : result, 0);
    }

    public static byte[] substring(byte[] bytes, boolean national, int start, int length) {
        long end = (long) start + length;
        if (start < 1 || length < 0 || end > (long) bytes.length + 1) {
            throw new IllegalArgumentException("USUBSTR range is outside the argument");
        }
        int firstByte = -1;
        int endByte = -1;
        int position = 1;
        for (int at = 0; ; position++) {
            if (position == start) {
                firstByte = at;
            }
            if (position == end) {
                endByte = at;
            }
            if (at == bytes.length) {
                break;
            }
            int width = width(bytes, at, national);
            if (width == 0) {
                throw invalid(at);
            }
            at += width;
        }
        if (endByte < 0) {
            throw new IllegalArgumentException("USUBSTR range is outside the argument");
        }
        return Arrays.copyOfRange(bytes, firstByte, endByte);
    }

    private static IllegalArgumentException invalid(int at) {
        return new IllegalArgumentException("invalid Unicode encoding at byte " + (at + 1));
    }

    // Zeus vm/func.rs と同じく、不正な系列の先頭位置を返せるよう変換前のバイトを走査する。
    private static int width(byte[] bytes, int at, boolean national) {
        if (national) {
            if (bytes.length - at < 2) {
                return 0;
            }
            int word = (bytes[at] & 0xFF) << 8 | (bytes[at + 1] & 0xFF);
            if (Character.isHighSurrogate((char) word)) {
                return bytes.length - at >= 4 && (bytes[at + 2] & 0xFC) == 0xDC ? 4 : 0;
            }
            return Character.isLowSurrogate((char) word) ? 0 : 2;
        }
        int head = bytes[at] & 0xFF;
        if (head < 0x80) {
            return 1;
        }
        int length = head >= 0xC2 && head <= 0xDF ? 2
                : head >= 0xE0 && head <= 0xEF ? 3
                : head >= 0xF0 && head <= 0xF4 ? 4 : 0;
        if (length == 0 || bytes.length - at < length) {
            return 0;
        }
        int second = bytes[at + 1] & 0xFF;
        if ((head == 0xE0 && second < 0xA0) || (head == 0xED && second >= 0xA0)
                || (head == 0xF0 && second < 0x90) || (head == 0xF4 && second >= 0x90)) {
            return 0;
        }
        for (int i = 1; i < length; i++) {
            if ((bytes[at + i] & 0xC0) != 0x80) {
                return 0;
            }
        }
        return length;
    }
}
