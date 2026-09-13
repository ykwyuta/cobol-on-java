package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramNotFoundException;

/** 正規化済みプログラム名を実行対象へ解決する。 */
@FunctionalInterface
public interface ProgramResolver {

    CobolProgram resolve(ProgramId id, ClassLoader loader);

    /**
     * programの解決可能性を確認する。
     * 互換defaultは{@link #resolve}を呼ぶため、side effectなしに判定できるresolverは
     * このmethodをoverrideすること。
     */
    default boolean isResolvable(ProgramId id, ClassLoader loader) {
        try {
            resolve(id, loader);
            return true;
        } catch (ProgramNotFoundException missing) {
            return false;
        }
    }

    /** 署名移行中は、未提供ならnullを返す互換入口。 */
    default ProgramSignature signature(ProgramId id) {
        return null;
    }
}
