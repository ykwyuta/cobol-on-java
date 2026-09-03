package dev.cobolonjava.oracle.run;

import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hercules のコンソール出力から記憶域の内容を取り出す (要件 FR-212 の採取モード)。
 *
 * <p>{@code r <addr>.<len>} コマンドの出力は次の形式である。
 *
 * <pre>
 * HHC02290I R:0000000000000400  00123C                               ...
 * HHC02290I R:0000000000000400                    00456C                     ..%
 * </pre>
 *
 * <p><b>重要</b>: 表示行は常に 16 バイト境界に揃えられ、行頭のアドレスはその境界のアドレスである。
 * 要求した範囲の外は空白で埋められる。したがって<b>バイトのアドレスは行頭アドレスではなく
 * 桁位置から決まる</b>。上の 2 行目は行頭が {@code 0x400} でありながら、実際のデータ
 * {@code 00456C} は {@code 0x408} の内容である。
 *
 * <p>16 進表示部は 4 バイトずつ 4 組、組の間は空白 1 個で区切られる (計 35 桁)。
 * 桁位置 {@code i} のバイトのアドレスは {@code 行頭 + (i / 9) * 4 + (i % 9) / 2} となる。
 */
public final class StorageDump {

    /** 16 進表示部の 1 組あたりの桁数 (8 桁 + 区切りの空白 1 個)。 */
    private static final int GROUP_STRIDE = 9;
    private static final int GROUPS = 4;
    private static final int BYTES_PER_GROUP = 4;
    private static final int HEX_AREA_WIDTH = GROUPS * GROUP_STRIDE - 1;

    private static final Pattern LINE = Pattern.compile("HHC0229[01]I\\s+[RVA]:([0-9A-Fa-f]+)\\s\\s");

    private final NavigableMap<Integer, Byte> bytes = new TreeMap<>();

    private StorageDump() {
    }

    /** コンソール出力の全文を解析する。 */
    public static StorageDump parse(String consoleOutput) {
        StorageDump dump = new StorageDump();
        for (String line : consoleOutput.split("\\R")) {
            Matcher m = LINE.matcher(line);
            if (!m.find()) {
                continue;
            }
            long lineAddress = Long.parseLong(m.group(1), 16);
            int hexStart = m.end();
            String hexArea = substringPadded(line, hexStart, HEX_AREA_WIDTH);
            for (int g = 0; g < GROUPS; g++) {
                for (int b = 0; b < BYTES_PER_GROUP; b++) {
                    int col = g * GROUP_STRIDE + b * 2;
                    String pair = hexArea.substring(col, col + 2);
                    if (!isHexPair(pair)) {
                        continue;
                    }
                    int address = (int) (lineAddress + (long) g * BYTES_PER_GROUP + b);
                    dump.bytes.put(address, (byte) Integer.parseInt(pair, 16));
                }
            }
        }
        return dump;
    }

    /** 指定範囲のバイト列を取り出す。読み出されていない位置があれば例外を投げる。 */
    public byte[] at(int address, int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            Byte b = bytes.get(address + i);
            if (b == null) {
                throw new IllegalStateException(String.format(
                        "storage at 0x%X was not displayed in the console output", address + i));
            }
            out[i] = b;
        }
        return out;
    }

    public boolean isEmpty() {
        return bytes.isEmpty();
    }

    private static String substringPadded(String line, int start, int width) {
        StringBuilder sb = new StringBuilder(width);
        for (int i = 0; i < width; i++) {
            int idx = start + i;
            sb.append(idx < line.length() ? line.charAt(idx) : ' ');
        }
        return sb.toString();
    }

    private static boolean isHexPair(String s) {
        return isHexDigit(s.charAt(0)) && isHexDigit(s.charAt(1));
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }
}
