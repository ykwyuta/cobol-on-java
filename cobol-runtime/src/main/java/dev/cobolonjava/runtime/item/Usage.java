package dev.cobolonjava.runtime.item;

/**
 * 数値項目の {@code USAGE} (要件 FR-031)。
 *
 * <p><b>暫定対応</b>: {@code DISPLAY-1} は未実装である。{@code NATIONAL} は {@code PIC N} の国字項目
 * だけを持ち、国字の数字 ({@code PIC 9 USAGE NATIONAL}) は持たない。provisional.md の P-006 に
 * 記録している。
 */
public enum Usage {
    /** ゾーン10進数。1 桁 1 バイト。 */
    DISPLAY,
    /** パック10進数。1 バイト 2 桁 + 符号ニブル。 */
    COMP_3,
    /** 2 進。ビッグエンディアン。PICTURE の桁数で値域が制限される。 */
    COMP,
    /** ネイティブ 2 進。桁数による切り捨てを行わず、記憶域のフルレンジを用いる。 */
    COMP_5,
    /** 短形式の IBM 16 進浮動小数点。PICTURE を持たない。 */
    COMP_1,
    /** 長形式の IBM 16 進浮動小数点。PICTURE を持たない。 */
    COMP_2,
    /** 国字。UTF-16 (ビッグエンディアン) の 1 文字 2 バイト。{@code PIC N} の項目だけが持つ。 */
    NATIONAL;

    /** PICTURE を持たない浮動小数点の USAGE かどうか。 */
    public boolean isFloatingPoint() {
        return this == COMP_1 || this == COMP_2;
    }
}
