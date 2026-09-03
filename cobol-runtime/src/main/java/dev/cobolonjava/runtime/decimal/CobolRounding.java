package dev.cobolonjava.runtime.decimal;

import java.math.RoundingMode;

/**
 * {@code ROUNDED} 句の丸めモード (要件 FR-042)。
 *
 * <p>{@code ROUNDED} を指定しない場合の既定は {@link #TRUNCATION} である。
 * {@code ROUNDED} を丸めモードなしで指定した場合の既定は {@link #NEAREST_AWAY_FROM_ZERO}。
 */
public enum CobolRounding {

    /** 四捨五入。半端は 0 から遠ざかる。{@code ROUNDED} の既定。 */
    NEAREST_AWAY_FROM_ZERO(RoundingMode.HALF_UP),
    /** 銀行家の丸め。半端は偶数側へ。 */
    NEAREST_EVEN(RoundingMode.HALF_EVEN),
    /** 半端は 0 に近づく。 */
    NEAREST_TOWARD_ZERO(RoundingMode.HALF_DOWN),
    /** 常に大きい側 (正の無限大方向) へ。 */
    TOWARD_GREATER(RoundingMode.CEILING),
    /** 常に小さい側 (負の無限大方向) へ。 */
    TOWARD_LESSER(RoundingMode.FLOOR),
    /** 常に 0 から遠ざかる。 */
    AWAY_FROM_ZERO(RoundingMode.UP),
    /** 切り捨て。{@code ROUNDED} を指定しない場合の既定。 */
    TRUNCATION(RoundingMode.DOWN),
    /** 丸めが必要になった時点で誤りとする。 */
    PROHIBITED(RoundingMode.UNNECESSARY);

    private final RoundingMode javaMode;

    CobolRounding(RoundingMode javaMode) {
        this.javaMode = javaMode;
    }

    public RoundingMode javaMode() {
        return javaMode;
    }
}
