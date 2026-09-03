package dev.cobolonjava.runtime.verb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 1 つの {@code INSPECT} 文に書かれた複数の句を、単一の走査で処理する (要件 FR-065)。
 *
 * <h2>句は「位置ごとに順に試す」</h2>
 * <p>COBOL の {@code INSPECT} は、項目を左から右へ 1 度だけ走査する。各位置で
 * <b>書かれた順に句を試し、最初に当たった句だけが適用される</b>。当たった長さだけ
 * 位置が進む。どの句も当たらなければ 1 バイト進む。
 *
 * <p>句を独立に適用するのとは結果が違う。{@code INSPECT X TALLYING C1 FOR ALL "AB"
 * C2 FOR ALL "B"} を {@code "AB"} に適用すると、単一走査では C1 が 1、C2 が 0 になる。
 * 独立に適用すると C2 も 1 になってしまう。
 *
 * <h2>LEADING は連なりが切れたら終わる</h2>
 * <p>{@code LEADING} は範囲の先頭から<b>続いているあいだ</b>だけ当たる。
 * ほかの句がその位置を取ったときも連なりは切れる。1 つの走査で句が競合するためである。
 */
public final class InspectScan {

    private InspectScan() {
    }

    /** 句の種別。 */
    public enum Kind {
        /** {@code CHARACTERS}。範囲内のどの 1 バイトにも当たる。 */
        CHARACTERS,
        /** {@code ALL}。範囲内のすべての一致に当たる。 */
        ALL,
        /** {@code LEADING}。範囲の先頭から続く一致にだけ当たる。 */
        LEADING,
        /** {@code FIRST}。最初の一致だけに当たる。{@code REPLACING} でのみ書ける。 */
        FIRST
    }

    /**
     * 句 1 個。
     *
     * @param kind    種別
     * @param pattern 照合する並び。{@code CHARACTERS} では使わない
     * @param to      置き換える並び。数えるだけの句では {@code null}
     * @param region  検査する範囲
     */
    public record Clause(Kind kind, byte[] pattern, byte[] to, Region region) {

        /** {@code TALLYING ... FOR CHARACTERS}。 */
        public static Clause characters(Region region) {
            return new Clause(Kind.CHARACTERS, null, null, region);
        }

        /** {@code TALLYING ... FOR ALL 定数}。 */
        public static Clause all(byte[] pattern, Region region) {
            return new Clause(Kind.ALL, pattern, null, region);
        }

        /** {@code TALLYING ... FOR LEADING 定数}。 */
        public static Clause leading(byte[] pattern, Region region) {
            return new Clause(Kind.LEADING, pattern, null, region);
        }

        /** {@code REPLACING CHARACTERS BY 定数}。 */
        public static Clause replaceCharacters(byte[] to, Region region) {
            return new Clause(Kind.CHARACTERS, null, to, region);
        }

        /** {@code REPLACING ALL 定数 BY 定数}。 */
        public static Clause replaceAll(byte[] pattern, byte[] to, Region region) {
            return new Clause(Kind.ALL, pattern, to, region);
        }

        /** {@code REPLACING LEADING 定数 BY 定数}。 */
        public static Clause replaceLeading(byte[] pattern, byte[] to, Region region) {
            return new Clause(Kind.LEADING, pattern, to, region);
        }

        /** {@code REPLACING FIRST 定数 BY 定数}。 */
        public static Clause replaceFirst(byte[] pattern, byte[] to, Region region) {
            return new Clause(Kind.FIRST, pattern, to, region);
        }

        /** この句が 1 度に消費する長さ。 */
        int width() {
            return kind == Kind.CHARACTERS ? 1 : pattern.length;
        }
    }

    /**
     * 数えるだけの走査。
     *
     * @return 句ごとの計数。並びは渡した句と同じ
     */
    public static int[] tally(byte[] data, Clause... clauses) {
        int[] counts = new int[clauses.length];
        scan(data, clauses, counts, null);
        return counts;
    }

    /**
     * 置き換える走査。
     *
     * @return 置き換えたあとのバイト列。長さは変わらない
     */
    public static byte[] replace(byte[] data, Clause... clauses) {
        byte[] out = data.clone();
        scan(data, clauses, new int[clauses.length], out);
        return out;
    }

    /**
     * 走査の本体。
     *
     * <p>照合は<b>元のバイト列に対して</b>行い、書き込みだけを別の配列へ行う。
     * 書き換えた結果を照合すると、置き換えた並びがもう一度当たることがある。
     *
     * @param out 置き換え先。数えるだけなら {@code null}
     */
    private static void scan(byte[] data, Clause[] clauses, int[] counts, byte[] out) {
        int[] ranges = new int[clauses.length * 2];
        boolean[] leading = new boolean[clauses.length];
        boolean[] spent = new boolean[clauses.length];
        for (int c = 0; c < clauses.length; c++) {
            Optional<int[]> range = clauses[c].region().resolve(data);
            // 範囲が成立しない句は、どの位置にも当たらない
            ranges[c * 2] = range.map(r -> r[0]).orElse(0);
            ranges[c * 2 + 1] = range.map(r -> r[1]).orElse(0);
            leading[c] = true;
        }

        int i = 0;
        while (i < data.length) {
            int matched = -1;
            for (int c = 0; c < clauses.length && matched < 0; c++) {
                if (matches(data, i, clauses[c], ranges[c * 2], ranges[c * 2 + 1],
                        leading[c], spent[c])) {
                    matched = c;
                }
            }
            for (int c = 0; c < clauses.length; c++) {
                // 連なりは、その位置を自分が取らなかった時点で切れる
                if (clauses[c].kind() == Kind.LEADING && c != matched
                        && i >= ranges[c * 2] && i < ranges[c * 2 + 1]) {
                    leading[c] = false;
                }
            }
            if (matched < 0) {
                i++;
                continue;
            }
            counts[matched]++;
            spent[matched] = true;
            int width = clauses[matched].width();
            if (out != null && clauses[matched].to() != null) {
                write(out, i, width, clauses[matched].to());
            }
            i += width;
        }
    }

    private static boolean matches(byte[] data, int at, Clause clause, int from, int to,
                                   boolean leading, boolean spent) {
        if (at < from || at >= to) {
            return false;
        }
        if (clause.kind() == Kind.CHARACTERS) {
            return true;
        }
        if (clause.kind() == Kind.LEADING && !leading) {
            return false;
        }
        if (clause.kind() == Kind.FIRST && spent) {
            return false;
        }
        int width = clause.pattern().length;
        if (width == 0 || at + width > to) {
            return false;
        }
        return Arrays.equals(data, at, at + width, clause.pattern(), 0, width);
    }

    /**
     * 置き換えを書き込む。
     *
     * <p>{@code CHARACTERS BY} は 1 バイトずつ置き換えるため、置き換える並びの
     * 先頭 1 バイトだけを使う。
     */
    private static void write(byte[] out, int at, int width, byte[] to) {
        for (int k = 0; k < width; k++) {
            out[at + k] = to[Math.min(k, to.length - 1)];
        }
    }

    /** 句の並びを配列にする。生成コード以外から使うときの便宜のためである。 */
    public static Clause[] clauses(List<Clause> list) {
        return new ArrayList<>(list).toArray(new Clause[0]);
    }
}
