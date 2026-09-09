package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.program.CobolProgram;

/** 正規化済みプログラム名を実行対象へ解決する。 */
@FunctionalInterface
public interface ProgramResolver {

    CobolProgram resolve(ProgramId id, ClassLoader loader);

    /** 署名移行中は、未提供ならnullを返す互換入口。 */
    default ProgramSignature signature(ProgramId id) {
        return null;
    }
}
