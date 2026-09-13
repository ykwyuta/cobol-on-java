package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.program.CobolProgram;

/** セッションで最初に解決したとき、呼び先を一つ生成する。 */
@FunctionalInterface
public interface ProgramFactory {

    CobolProgram create(ClassLoader loader);
}
