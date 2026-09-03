package dev.cobolonjava.runtime.data;

/** コンパイラオプション {@code NUMPROC} の 3 モード (要件 FR-033)。 */
public enum NumProcMode {

    /**
     * 符号と数字が正しいことを前提とし、検査を行わない。
     * 不正なデータが渡された場合の結果は参照実装でも保証されないため、
     * 本処理系での扱いは {@link dev.cobolonjava.runtime.config.UndefinedBehavior} に従う (要件 FR-205)。
     */
    PFD,

    /** 符号を正規化し、数字の妥当性を検査する。 */
    NOPFD,

    /**
     * 旧世代の処理系との移行用モード。
     *
     * <p><b>暫定対応</b>: 現時点では {@link #NOPFD} と同じ扱いとしている。
     * 参照実装における {@code MIG} 固有の差異は未調査であり、provisional.md の P-004 に記録している。
     */
    MIG
}
