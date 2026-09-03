package dev.cobolonjava.runtime.data;

import dev.cobolonjava.runtime.config.UndefinedBehavior;

/** コンパイラオプション {@code TRUNC} の 3 モード (要件 FR-045)。 */
public enum TruncMode {

    /** PICTURE の桁数へ 10 進的に切り捨てる。 */
    STD,

    /**
     * 切り捨ての有無を最適化に委ねるモード。参照実装は「データが PICTURE に収まる前提で
     * 最も効率のよいコードを生成し、収まらない場合の結果は予測できない」と規定している。
     *
     * <p>したがって本処理系では単独では解決できず、{@link #resolve} で
     * {@link UndefinedBehavior} に応じて {@link #STD} か {@link #BIN} へ解決する (要件 FR-205)。
     */
    OPT,

    /** 10 進的な切り捨てを行わず、記憶域のフルレンジを用いる。 */
    BIN;

    /**
     * {@link #OPT} を実際の挙動へ解決する。
     *
     * <ul>
     *   <li>{@link UndefinedBehavior#SAFE} — {@link #STD} として扱う。定義された挙動を返す。</li>
     *   <li>{@link UndefinedBehavior#MIMIC} — {@link #BIN} として扱う。切り捨てを省く挙動を模倣する。</li>
     * </ul>
     */
    public TruncMode resolve(UndefinedBehavior undefinedBehavior) {
        if (this != OPT) {
            return this;
        }
        return undefinedBehavior == UndefinedBehavior.MIMIC ? BIN : STD;
    }
}
