package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.item.Usage;
import dev.cobolonjava.runtime.sort.SortKey;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;

/**
 * 整列の道具が指す「レコードの中の 1 か所」(要件 FR-137)。
 *
 * <p>{@code SORT} も {@code ICETOOL} も、位置と長さと形で場所を言う。<b>読み方が 2 か所に
 * あれば必ず食い違う</b>ので、形の解釈と値の取り出しはここ 1 つに置く。
 *
 * <h2>位置は 0 から数える</h2>
 * <p>制御文では 1 から数えるが、ここへ来るときには引き算が済んでいる。可変長では先頭の
 * 4 バイト (RDW) もレコードの一部なので、制御文の {@code 5} がデータの先頭にあたる。
 *
 * @param ascending 鍵として使うときの向き。鍵でなければ意味を持たない
 */
record SortField(int offset, int length, Format format, boolean ascending) {

    /** 鍵とふるい分けで使えるデータの形。 */
    enum Format {
        /** 文字。バイトの並びで比べる。 */
        CH,
        /** 符号なし 2 進数。バイトの並びで比べれば値の順になる。 */
        BI,
        /** ゾーン 10 進数。 */
        ZD,
        /** パック 10 進数。 */
        PD,
        /** 符号付き固定 2 進数。 */
        FI
    }

    /** 昇順の場所。鍵以外では順は使われない。 */
    static SortField at(int offset, int length, Format format) {
        return new SortField(offset, length, format, true);
    }

    /** 整列の鍵にする。 */
    SortKey key() {
        return new SortKey(offset, length, ascending, item());
    }

    /**
     * この形を数として比べるときの記述子。
     *
     * <p>{@code CH} と {@code BI} は {@code null} である。<b>バイトの並びで比べれば値の順に
     * なる</b>からで、符号なし 2 進数についてはこれが厳密に正しい。
     */
    NumericItem item() {
        return switch (format) {
            case CH, BI -> null;
            case ZD -> NumericItem.of("S9(" + length + ")", Usage.DISPLAY);
            case PD -> NumericItem.of("S9(" + (2 * length - 1) + ")", Usage.COMP_3);
            case FI -> switch (length) {
                case 2 -> NumericItem.of("S9(4)", Usage.COMP);
                case 4 -> NumericItem.of("S9(9)", Usage.COMP);
                case 8 -> NumericItem.of("S9(18)", Usage.COMP);
                default -> null;
            };
        };
    }

    /** この場所を扱えるか。{@code FI} は 2 / 4 / 8 バイトだけである。 */
    boolean supported() {
        return format != Format.FI || item() != null;
    }

    /** レコードから切り出す。足りなければ埋める。 */
    byte[] slice(byte[] record, CodePage codePage) {
        return slice(record, offset, length, format == Format.CH ? codePage.space() : (byte) 0);
    }

    /** 値として読む。数として読めないバイトは 0 とみなす。 */
    Decimal number(byte[] record, CodePage codePage) {
        byte[] bytes = slice(record, codePage);
        if (format == Format.BI || format == Format.CH) {
            return Decimal.of(new BigInteger(1, bytes.length == 0 ? new byte[] {0} : bytes), 0);
        }
        try {
            return item().decode(bytes);
        } catch (RuntimeException e) {
            // ふるい分けを止めない。読めないこと自体は呼ぶ側が扱う
            return Decimal.zero(0);
        }
    }

    /**
     * 値を書き戻すバイト列。
     *
     * @return 書き戻せない形なら {@code null}
     */
    byte[] encode(Decimal value) {
        if (format == Format.BI) {
            byte[] out = new byte[length];
            byte[] bytes = value.magnitude().toByteArray();
            int from = Math.max(0, bytes.length - length);
            int to = out.length - (bytes.length - from);
            System.arraycopy(bytes, from, out, Math.max(to, 0), bytes.length - from);
            return out;
        }
        NumericItem item = item();
        return item == null ? null : item.encode(value);
    }

    // ---- バイト列の扱い ----

    /** レコードの一部を切り出す。範囲の外は埋める。 */
    static byte[] slice(byte[] record, int offset, int length, byte filler) {
        byte[] out = new byte[Math.max(length, 0)];
        Arrays.fill(out, filler);
        for (int i = 0; i < out.length && offset + i < record.length; i++) {
            if (offset + i >= 0) {
                out[i] = record[offset + i];
            }
        }
        return out;
    }

    /** 決まった長さへ揃える。足りなければ埋め、あふれれば切り捨てる。 */
    static byte[] padded(byte[] bytes, int length, byte filler) {
        if (bytes.length == length) {
            return bytes;
        }
        byte[] out = new byte[length];
        Arrays.fill(out, filler);
        System.arraycopy(bytes, 0, out, 0, Math.min(bytes.length, length));
        return out;
    }

    /** バイトの並びで比べる。EBCDIC の照合順序はバイトの値そのものである。 */
    static int compareBytes(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int order = Integer.compare(left[i] & 0xFF, right[i] & 0xFF);
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    /**
     * 綴りから形を読む。
     *
     * @return 知らない綴りは {@code null}
     */
    static Format formatOf(String text) {
        return switch (text.toUpperCase(Locale.ROOT)) {
            case "CH" -> Format.CH;
            case "BI" -> Format.BI;
            case "ZD" -> Format.ZD;
            case "PD" -> Format.PD;
            case "FI" -> Format.FI;
            default -> null;
        };
    }
}
