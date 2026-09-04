package dev.cobolonjava.runtime.sort;

import dev.cobolonjava.runtime.item.NumericItem;

/**
 * 整列の鍵 1 個 (要件 FR-120)。
 *
 * <p>鍵は<b>レコードの中の場所</b>である。どこからどれだけかと、昇順か降順か。
 *
 * <p>比べ方は項目の種類で変わる。数値項目なら<b>値として</b>比べる。{@code 010} と
 * {@code 9} はバイトで比べれば {@code 010} が小さいが、値としては {@code 9} が小さい。
 * 英数字項目ならバイトで比べる。EBCDIC の照合順序はバイトの値そのものである。
 *
 * @param numeric 数値として比べるときの記述子。英数字なら {@code null}
 */
public record SortKey(int offset, int length, boolean ascending, NumericItem numeric) {

    /** 英数字の鍵。 */
    public static SortKey alphanumeric(int offset, int length, boolean ascending) {
        return new SortKey(offset, length, ascending, null);
    }
}
