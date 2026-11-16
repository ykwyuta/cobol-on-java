package dev.cobolonjava.pli;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/** GRAPHIC と SO / SI を含む混在文字列の変換。 */
record PliGraphic(byte[] bytes, CodePage page) {
    private static final byte SO = 0x0E;
    private static final byte SI = 0x0F;

    static PliGraphic fromMixed(byte[] input, Integer characters, CodePage page) {
        ByteArrayOutputStream graphic = new ByteArrayOutputStream();
        boolean shifted = false;
        for (int at = 0; at < input.length;) {
            byte value = input[at++];
            if (!shifted) {
                if (value == SO) {
                    shifted = true;
                } else if (value == SI) {
                    throw conversion(input, page);
                } else {
                    graphic.write(value == page.space() ? 0x40 : 0x42);
                    graphic.write(value == page.space() ? 0x40 : value);
                }
            } else if (value == SI) {
                shifted = false;
            } else {
                if (value == SO || at >= input.length || input[at] == SI || input[at] == SO) {
                    throw conversion(input, page);
                }
                graphic.write(value);
                graphic.write(input[at++]);
            }
        }
        if (shifted) throw conversion(input, page);
        PliGraphic result = new PliGraphic(graphic.toByteArray(), page);
        return characters == null ? result : new PliGraphic(result.fit(Math.multiplyExact(2, characters)), page);
    }

    private static PliRuntime.Condition conversion(byte[] input, CodePage page) {
        return new PliRuntime.Condition("CONVERSION", null, 600, page.decode(input));
    }

    byte[] fit(int bytesLength) {
        byte[] result = Arrays.copyOf(bytes, bytesLength);
        if (bytesLength > bytes.length) Arrays.fill(result, bytes.length, bytesLength, (byte) 0x40);
        return result;
    }

    String characters() {
        byte[] single = new byte[bytes.length / 2];
        for (int i = 0; i < single.length; i++) {
            int high = bytes[2 * i] & 0xFF;
            byte low = bytes[2 * i + 1];
            if (high == 0x40 && low == 0x40) {
                single[i] = page.space();
            } else if (high == 0x42) {
                single[i] = low;
            } else {
                throw conversion(bytes, page);
            }
        }
        return page.decode(single);
    }

    byte[] mixed(Integer length) {
        int size = length == null ? (bytes.length == 0 ? 0 : bytes.length + 2) : length;
        byte[] result = new byte[size];
        Arrays.fill(result, page.space());
        if (size >= 2 && bytes.length != 0) {
            int count = Math.min(bytes.length, (size - 2) & ~1);
            result[0] = SO;
            System.arraycopy(bytes, 0, result, 1, count);
            result[count + 1] = SI;
        }
        return result;
    }

    static byte[] mixedPrefix(byte[] original, boolean squeeze, int length, CodePage page) {
        byte[] input = original;
        if (squeeze) {
            ByteArrayOutputStream filtered = new ByteArrayOutputStream();
            for (int at = 0; at < original.length; at++) {
                if (original[at] == SO && at + 1 < original.length && original[at + 1] == SI) {
                    at++;
                } else {
                    filtered.write(original[at]);
                }
            }
            input = filtered.toByteArray();
        }
        byte[] output = new byte[length];
        int to = 0;
        if (input.length <= length) {
            System.arraycopy(input, 0, output, 0, input.length);
            to = input.length;
        } else {
            boolean shifted = false;
            for (int at = 0; at < input.length;) {
                byte value = input[at];
                if (shifted && value != SI) {
                    if (length - to < 3 || input.length - at < 2) break;
                    output[to++] = input[at++];
                    output[to++] = input[at++];
                } else {
                    if (length - to < (value == SO ? 2 : 1)) break;
                    shifted = value == SO;
                    output[to++] = input[at++];
                }
            }
            if (shifted) output[to++] = SI;
        }
        Arrays.fill(output, to, length, page.space());
        return output;
    }
}
