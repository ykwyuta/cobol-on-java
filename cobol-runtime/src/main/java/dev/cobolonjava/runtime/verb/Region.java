package dev.cobolonjava.runtime.verb;

import java.util.Arrays;
import java.util.Optional;

/**
 * {@code INSPECT} の {@code BEFORE INITIAL} / {@code AFTER INITIAL} が定める検査範囲 (要件 FR-065)。
 *
 * <p>範囲は次のように決まる。
 *
 * <ul>
 *   <li>{@code AFTER INITIAL a} — 最初に現れる {@code a} の<b>直後</b>から始まる。
 *       {@code a} が見つからない場合、その句による検査は一切行われない (範囲が空になる)</li>
 *   <li>{@code BEFORE INITIAL b} — 開始位置以降で最初に現れる {@code b} の<b>直前</b>で終わる。
 *       見つからない場合は項目の末尾まで</li>
 * </ul>
 *
 * <p>{@code AFTER} の区切り文字が見つからないときに「検査しない」のであって
 * 「全体を検査する」のではない点が重要である。取り違えると、区切り文字を含まないデータで
 * 意図しない置換が起きる。
 */
public final class Region {

    private final byte[] afterInitial;
    private final byte[] beforeInitial;

    private Region(byte[] afterInitial, byte[] beforeInitial) {
        this.afterInitial = afterInitial;
        this.beforeInitial = beforeInitial;
    }

    /** 項目全体を検査する。 */
    public static Region whole() {
        return new Region(null, null);
    }

    public static Region after(byte[] delimiter) {
        return new Region(delimiter.clone(), null);
    }

    public static Region before(byte[] delimiter) {
        return new Region(null, delimiter.clone());
    }

    public static Region between(byte[] afterDelimiter, byte[] beforeDelimiter) {
        return new Region(afterDelimiter.clone(), beforeDelimiter.clone());
    }

    /**
     * この範囲を実データ上の区間へ解決する。
     *
     * @return 開始位置 (含む) と終了位置 (含まない) の組。範囲が成立しない場合は空
     */
    public Optional<int[]> resolve(byte[] data) {
        int start = 0;
        if (afterInitial != null) {
            int at = indexOf(data, afterInitial, 0);
            if (at < 0) {
                return Optional.empty();
            }
            start = at + afterInitial.length;
        }
        int end = data.length;
        if (beforeInitial != null) {
            int at = indexOf(data, beforeInitial, start);
            if (at >= 0) {
                end = at;
            }
        }
        return start >= end ? Optional.empty() : Optional.of(new int[] {start, end});
    }

    static int indexOf(byte[] data, byte[] pattern, int from) {
        if (pattern.length == 0 || pattern.length > data.length) {
            return -1;
        }
        for (int i = Math.max(0, from); i + pattern.length <= data.length; i++) {
            if (matchesAt(data, pattern, i)) {
                return i;
            }
        }
        return -1;
    }

    static boolean matchesAt(byte[] data, byte[] pattern, int at) {
        if (at + pattern.length > data.length) {
            return false;
        }
        return Arrays.equals(data, at, at + pattern.length, pattern, 0, pattern.length);
    }
}
