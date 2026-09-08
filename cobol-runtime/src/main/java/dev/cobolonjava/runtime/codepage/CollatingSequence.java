package dev.cobolonjava.runtime.codepage;

/**
 * 照合順序 (要件 FR-046, FR-053, FR-054)。
 *
 * <p>英数字の比較で<b>どちらが小さいか</b>を決める並びである。既定はコードページの
 * バイト値そのもの (EBCDIC の並び) だが、{@code SPECIAL-NAMES} の {@code ALPHABET} 句と
 * {@code PROGRAM COLLATING SEQUENCE} で差し替えられる。
 *
 * <p>持っているのは<b>バイト値から位置への表</b>である。比較はバイト値ではなく位置で
 * 行う。位置は 1 から数える。{@code ALSO} で並べた文字は<b>同じ位置</b>を持つので、
 * 比較すると等しくなる。
 *
 * <p>{@code FUNCTION CHAR} と {@code FUNCTION ORD} も、この並びの位置で答える。
 * 定義がそうなっているためであり、照合順序を差し替えれば両方の答えが変わる。
 */
public final class CollatingSequence {

    /** 表の大きさ。1 バイトの取りうる値の数である。 */
    public static final int SIZE = 256;

    private final byte[] ordinal;
    private final byte[] character;

    private CollatingSequence(byte[] ordinal) {
        this.ordinal = ordinal;
        this.character = inverseOf(ordinal);
    }

    /**
     * バイト値から位置への表から作る。
     *
     * @param ordinal 256 個の要素。{@code ordinal[b]} が値 {@code b} の位置 (0 から数える)
     */
    public static CollatingSequence of(byte[] ordinal) {
        if (ordinal.length != SIZE) {
            throw new IllegalArgumentException(
                    "a collating sequence needs " + SIZE + " entries, not " + ordinal.length);
        }
        return new CollatingSequence(ordinal.clone());
    }

    /** コードページのバイト値そのものを並びとする、既定の照合順序。 */
    public static CollatingSequence nativeOrder() {
        byte[] ordinal = new byte[SIZE];
        for (int i = 0; i < SIZE; i++) {
            ordinal[i] = (byte) i;
        }
        return new CollatingSequence(ordinal);
    }

    /**
     * 位置から文字への逆引き。
     *
     * <p>{@code ALSO} で同じ位置を持つ文字が並んでいたら、<b>先に来るバイト値</b>を
     * その位置の文字とする。位置に文字が 1 つも無ければ、その位置は使われない。
     */
    private static byte[] inverseOf(byte[] ordinal) {
        byte[] character = new byte[SIZE];
        boolean[] taken = new boolean[SIZE];
        for (int value = 0; value < SIZE; value++) {
            int position = ordinal[value] & 0xFF;
            if (!taken[position]) {
                taken[position] = true;
                character[position] = (byte) value;
            }
        }
        return character;
    }

    /** 値 {@code b} が並びの何番目か。1 から数える。 */
    public int positionOf(byte value) {
        return (ordinal[value & 0xFF] & 0xFF) + 1;
    }

    /**
     * 並びの {@code position} 番目の文字。1 から数える。
     *
     * @throws IllegalArgumentException 範囲の外
     */
    public byte characterAt(int position) {
        if (position < 1 || position > SIZE) {
            throw new IllegalArgumentException(
                    "a collating sequence position must be 1.." + SIZE + ", not " + position);
        }
        return character[position - 1];
    }

    /**
     * この並びによる比較。短いほうは空白で埋められたものとして比べる
     * (COBOL の英数字比較の規則)。
     */
    public int compare(byte[] a, byte[] b, byte space) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int d = positionOf(a[i]) - positionOf(b[i]);
            if (d != 0) {
                return d;
            }
        }
        int pad = positionOf(space);
        for (int i = n; i < a.length; i++) {
            int d = positionOf(a[i]) - pad;
            if (d != 0) {
                return d;
            }
        }
        for (int i = n; i < b.length; i++) {
            int d = pad - positionOf(b[i]);
            if (d != 0) {
                return d;
            }
        }
        return 0;
    }
}
