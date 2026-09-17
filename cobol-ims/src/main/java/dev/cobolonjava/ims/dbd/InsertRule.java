package dev.cobolonjava.ims.dbd;

/**
 * {@code RULES=} の挿入位置。キーを持たないか、キーが重なってよいセグメントの兄弟の並びを決める。
 *
 * <p>既定は {@code LAST} である。
 */
public enum InsertRule {
    /** 同じキーの兄弟 (キーが無ければ兄弟すべて) の前。 */
    FIRST,
    /** 同じキーの兄弟の後。 */
    LAST,
    /** 現在位置の前。位置の決まり方を実機と突き合わせていないので、LAST と同じに置く (暫定判断 P-153)。 */
    HERE
}
