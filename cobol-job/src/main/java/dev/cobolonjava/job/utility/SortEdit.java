package dev.cobolonjava.job.utility;

import dev.cobolonjava.job.jcl.JclOperands;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.util.List;
import java.util.Locale;

/**
 * 数を書き出す形 (要件 FR-137)。
 *
 * <p>整列の道具は場所を写すだけでなく、<b>数を書き直す</b>ことができる。書き直し方は
 * 2 通りある。
 *
 * <ul>
 *   <li>{@code TO=ZD} — 形を変える。パック 10 進数をゾーン 10 進数へ、といった移し替えで
 *       あり、行き先は<b>人ではなく次のプログラム</b>である
 *   <li>{@code EDIT=(I,III,IIT)} — 人が読む形にする。桁区切りのコンマを入れ、頭の 0 を
 *       消す。行き先は<b>紙</b>である
 * </ul>
 *
 * <p>この 2 つを 1 か所に置いてあるのは、{@code OUTREC} でも {@code OUTFIL} の末尾でも
 * <b>同じ綴りで同じことが書ける</b>からである。書ける場所ごとに読み方を持つと、末尾の
 * 合計だけコンマが入らない、といった食い違いが出る。
 *
 * @param to 移し替える先の形。{@code EDIT=} なら {@code null}
 * @param length {@code TO=} で作るバイト数。書かれていなければ 0
 * @param pattern {@code EDIT=} の型。{@code TO=} なら {@code null}
 */
record SortEdit(SortField.Format to, int length, String pattern) {

    /** 型の中で桁を表す文字。{@code I} は頭の 0 を消し、{@code T} は消さない。 */
    private static final String DIGITS = "IT";

    /**
     * 続けて書かれた飾りを読む。
     *
     * <p>{@code p,l,形} のあとに {@code TO=} / {@code LENGTH=} / {@code EDIT=} が続く。
     *
     * @return 何も書かれていなければ {@code null}
     */
    static SortEdit read(List<String> items) {
        SortField.Format to = null;
        int length = 0;
        String pattern = null;
        for (String item : items) {
            String key = JclOperands.key(item).trim().toUpperCase(Locale.ROOT);
            String value = JclOperands.value(item).trim();
            switch (key) {
                case "TO" -> to = SortField.formatOf(value);
                case "LENGTH" -> length = digits(value);
                case "EDIT" -> pattern = JclOperands.unquote(JclOperands.unwrap(value));
                default -> {
                    return null;
                }
            }
        }
        if (to == null && pattern == null) {
            return null;
        }
        return new SortEdit(to, length, pattern);
    }

    /** 続けて書ける飾りの綴りか。ここに無いものは場所の書き方として読む。 */
    static boolean names(String item) {
        String key = JclOperands.key(item).trim().toUpperCase(Locale.ROOT);
        return key.equals("TO") || key.equals("LENGTH") || key.equals("EDIT");
    }

    /**
     * 書き直せる形か。
     *
     * <p>{@code TO=} の行き先は数の形でなければならない。文字で書いた数 ({@code UFF} など)
     * は<b>読む形であって書く形ではない</b>ので、行き先にはできない。
     */
    boolean sound() {
        if (pattern != null) {
            return to == null && length == 0 && !pattern.isEmpty() && slots() > 0;
        }
        return !to.free() && to != SortField.Format.CH
                && SortField.at(0, width(1), to).supported();
    }

    /**
     * 値を書き出す。
     *
     * @param source 読んだ場所。{@code TO=} の長さを書かなかったときの目安になる
     */
    byte[] write(Decimal value, SortField source, CodePage codePage) {
        if (pattern != null) {
            return codePage.encode(edited(value));
        }
        return SortField.at(0, width(capacity(source)), to).encode(value);
    }

    /** 作るバイト数。書かれていなければ、元の桁数が入る大きさにする。 */
    private int width(int capacity) {
        if (length > 0) {
            return length;
        }
        return switch (to) {
            case ZD -> Math.max(capacity, 1);
            case PD -> capacity / 2 + 1;
            case FI, BI -> capacity <= 4 ? 2 : capacity <= 9 ? 4 : 8;
            default -> Math.max(capacity, 1);
        };
    }

    /** 元の場所が持てる桁数。 */
    private static int capacity(SortField source) {
        return switch (source.format()) {
            case PD -> 2 * source.length() - 1;
            case FI, BI -> source.length() <= 2 ? 4 : source.length() <= 4 ? 9 : 18;
            default -> source.length();
        };
    }

    /** 型の中の桁の数。 */
    private int slots() {
        int count = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (DIGITS.indexOf(pattern.charAt(i)) >= 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * 型に当てはめて人が読む形にする。
     *
     * <p>桁は<b>右から</b>詰める。型より桁が多ければ上の桁が落ちる。{@code I} の桁は、
     * それより上に意味のある数字が出ていなければ空白になる。区切りの文字も同じで、
     * <b>数字が出るまでは空白</b>である。{@code 0,001,234} ではなく
     * {@code &nbsp;&nbsp;&nbsp;&nbsp;1,234} と出したいからである。
     *
     * <p>{@code S} は符号の場所である。負なら {@code -}、そうでなければ空白を置く。
     */
    private String edited(Decimal value) {
        String digits = value.magnitude().toString();
        int slots = slots();
        digits = digits.length() >= slots
                ? digits.substring(digits.length() - slots)
                : "0".repeat(slots - digits.length()) + digits;
        StringBuilder out = new StringBuilder();
        boolean started = false;
        int at = 0;
        for (int i = 0; i < pattern.length(); i++) {
            char shape = pattern.charAt(i);
            if (shape == 'S') {
                out.append(value.signum() < 0 ? '-' : ' ');
                continue;
            }
            if (DIGITS.indexOf(shape) < 0) {
                out.append(started ? shape : ' ');
                continue;
            }
            char digit = digits.charAt(at++);
            if (shape == 'T' || digit != '0') {
                started = true;
            }
            out.append(started ? digit : ' ');
        }
        return out.toString();
    }

    private static int digits(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
