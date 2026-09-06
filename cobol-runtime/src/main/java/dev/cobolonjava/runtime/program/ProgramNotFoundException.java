package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.abend.AbendCause;
import dev.cobolonjava.runtime.abend.AbendCode;

/**
 * {@code CALL} の呼び先が見つからない (要件 FR-080, FR-082)。
 *
 * <p>{@code ON EXCEPTION} を書いていれば条件として受け止められる。書いていなければ
 * 実行時の異常終了になる。参照実装でも未解決の動的 {@code CALL} は異常終了する。
 */
public final class ProgramNotFoundException extends RuntimeException implements AbendCause {

    private static final long serialVersionUID = 1L;

    @Override
    public AbendCode abendCode() {
        return AbendCode.S806;
    }

    public ProgramNotFoundException(String name, Throwable cause) {
        super("cannot find the called program: " + name, cause);
    }
}
