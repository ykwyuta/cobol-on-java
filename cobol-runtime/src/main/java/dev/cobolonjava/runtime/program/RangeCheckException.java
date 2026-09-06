package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.abend.AbendCause;
import dev.cobolonjava.runtime.abend.AbendCode;

/**
 * 添字または部分参照が項目の外を指した (要件 FR-024, FR-026)。
 *
 * <p>{@code SSRANGE} を指定したときにだけ投げられる。指定がなければ検査そのものを行わず、
 * <b>記憶域の別の場所を読み書きする</b>。参照実装の既定もそうである。
 *
 * <p>参照実装は範囲外を検出すると異常終了する。ここでも捕まえずに抜ける例外とし、
 * 黙って続けることはしない。範囲外の参照は、そのあとの結果を信用できなくするためである。
 */
public final class RangeCheckException extends RuntimeException implements AbendCause {

    private static final long serialVersionUID = 1L;

    /** 参照実装も範囲外を検出すると言語環境の条件として終わる。 */
    @Override
    public AbendCode abendCode() {
        return AbendCode.U4038;
    }

    public RangeCheckException(String message) {
        super(message);
    }
}
