package dev.cobolonjava.runtime.item;

/**
 * 数値項目の {@code USAGE} (要件 FR-031)。
 *
 * <p><b>暫定対応</b>: {@code COMPUTATIONAL-1} / {@code COMPUTATIONAL-2} (IBM 16 進浮動小数点)、
 * {@code INDEX}、{@code POINTER}、{@code NATIONAL}、{@code DISPLAY-1} は未実装である。
 * provisional.md の P-006 に記録している。
 */
public enum Usage {
    /** ゾーン10進数。1 桁 1 バイト。 */
    DISPLAY,
    /** パック10進数。1 バイト 2 桁 + 符号ニブル。 */
    COMP_3,
    /** 2 進。ビッグエンディアン。PICTURE の桁数で値域が制限される。 */
    COMP,
    /** ネイティブ 2 進。桁数による切り捨てを行わず、記憶域のフルレンジを用いる。 */
    COMP_5
}
