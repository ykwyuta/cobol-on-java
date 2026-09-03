package dev.cobolonjava.runtime.verb;

import java.util.Optional;

/**
 * {@code INSPECT} 文の意味論 (要件 FR-065)。
 *
 * <p>すべての操作は「バイト列 in - バイト列 out」の純関数である (要件 ARC-7)。
 * 照合はバイト単位であり、コードページには依存しない。
 *
 * <p>一致は<b>左から右へ、重なりなく</b>行われる。一致した位置の直後から走査を続けるため、
 * 例えば {@code "AAA"} から {@code "AA"} を数えると 2 ではなく 1 になる。
 *
 * <p><b>未実装</b>: 1 つの {@code INSPECT} 文に複数の句を書いた場合の、
 * 単一走査で句を順に試す意味論は実装していない。provisional.md の P-017 に記録している。
 */
public final class Inspect {

    private Inspect() {
    }

    /** {@code TALLYING ... FOR CHARACTERS}。範囲内の文字数を数える。 */
    public static int tallyCharacters(byte[] data, Region region) {
        Optional<int[]> range = region.resolve(data);
        return range.map(r -> r[1] - r[0]).orElse(0);
    }

    /** {@code TALLYING ... FOR ALL 定数}。重なりのない一致の数を数える。 */
    public static int tallyAll(byte[] data, byte[] pattern, Region region) {
        Optional<int[]> range = region.resolve(data);
        if (range.isEmpty() || pattern.length == 0) {
            return 0;
        }
        int[] r = range.get();
        int count = 0;
        int i = r[0];
        while (i + pattern.length <= r[1]) {
            if (Region.matchesAt(data, pattern, i)) {
                count++;
                i += pattern.length;
            } else {
                i++;
            }
        }
        return count;
    }

    /** {@code TALLYING ... FOR LEADING 定数}。範囲の先頭から連続する一致の数を数える。 */
    public static int tallyLeading(byte[] data, byte[] pattern, Region region) {
        Optional<int[]> range = region.resolve(data);
        if (range.isEmpty() || pattern.length == 0) {
            return 0;
        }
        int[] r = range.get();
        int count = 0;
        int i = r[0];
        while (i + pattern.length <= r[1] && Region.matchesAt(data, pattern, i)) {
            count++;
            i += pattern.length;
        }
        return count;
    }

    /** {@code REPLACING ALL 定数 BY 定数}。 */
    public static byte[] replaceAll(byte[] data, byte[] from, byte[] to, Region region) {
        return replace(data, from, to, region, Mode.ALL);
    }

    /** {@code REPLACING LEADING 定数 BY 定数}。 */
    public static byte[] replaceLeading(byte[] data, byte[] from, byte[] to, Region region) {
        return replace(data, from, to, region, Mode.LEADING);
    }

    /** {@code REPLACING FIRST 定数 BY 定数}。 */
    public static byte[] replaceFirst(byte[] data, byte[] from, byte[] to, Region region) {
        return replace(data, from, to, region, Mode.FIRST);
    }

    /**
     * {@code REPLACING CHARACTERS BY 定数}。範囲内のすべての文字位置を置き換える。
     *
     * @throws IllegalArgumentException 置換文字が 1 バイトでない場合
     */
    public static byte[] replaceCharacters(byte[] data, byte[] to, Region region) {
        if (to.length != 1) {
            throw new IllegalArgumentException(
                    "REPLACING CHARACTERS BY requires a single character: " + to.length + " bytes");
        }
        byte[] out = data.clone();
        region.resolve(data).ifPresent(r -> {
            for (int i = r[0]; i < r[1]; i++) {
                out[i] = to[0];
            }
        });
        return out;
    }

    /**
     * {@code CONVERTING 定数 TO 定数}。文字単位の 1 対 1 変換を行う。
     *
     * <p>ホストの {@code TR} 命令に対応する。同じ文字が {@code from} に複数回現れる場合は
     * <b>最初の対応が使われる</b>。
     *
     * @throws IllegalArgumentException 変換元と変換先の長さが異なる場合
     */
    public static byte[] convert(byte[] data, byte[] from, byte[] to, Region region) {
        if (from.length != to.length) {
            throw new IllegalArgumentException(
                    "CONVERTING requires equal lengths: " + from.length + " and " + to.length);
        }
        byte[] table = translationTable(from, to);
        byte[] out = data.clone();
        region.resolve(data).ifPresent(r -> {
            for (int i = r[0]; i < r[1]; i++) {
                out[i] = table[out[i] & 0xFF];
            }
        });
        return out;
    }

    /**
     * {@code CONVERTING} に対応する 256 バイトの変換表を作る。
     * ホストの {@code TR} 命令が使う表と同じものである。
     */
    public static byte[] translationTable(byte[] from, byte[] to) {
        if (from.length != to.length) {
            throw new IllegalArgumentException(
                    "CONVERTING requires equal lengths: " + from.length + " and " + to.length);
        }
        byte[] table = new byte[256];
        for (int i = 0; i < 256; i++) {
            table[i] = (byte) i;
        }
        // 同じ文字が複数回現れた場合は最初の対応を使うため、後ろから前へ書く
        for (int i = from.length - 1; i >= 0; i--) {
            table[from[i] & 0xFF] = to[i];
        }
        return table;
    }

    private enum Mode {
        ALL, LEADING, FIRST
    }

    private static byte[] replace(byte[] data, byte[] from, byte[] to, Region region, Mode mode) {
        if (from.length != to.length) {
            throw new IllegalArgumentException(
                    "REPLACING requires equal lengths: " + from.length + " and " + to.length);
        }
        Optional<int[]> range = region.resolve(data);
        byte[] out = data.clone();
        if (range.isEmpty() || from.length == 0) {
            return out;
        }
        int[] r = range.get();
        int i = r[0];
        while (i + from.length <= r[1]) {
            if (Region.matchesAt(data, from, i)) {
                System.arraycopy(to, 0, out, i, to.length);
                i += from.length;
                if (mode == Mode.FIRST) {
                    break;
                }
            } else {
                if (mode == Mode.LEADING) {
                    break;
                }
                i++;
            }
        }
        return out;
    }
}
