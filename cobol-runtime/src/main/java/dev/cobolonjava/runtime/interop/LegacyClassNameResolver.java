package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramNotFoundException;
import dev.cobolonjava.runtime.program.ProgramSupport;

/** 現行の生成クラス名規則を使う、段階移行専用の互換リゾルバ。 */
public final class LegacyClassNameResolver implements ProgramResolver {

    public static final LegacyClassNameResolver INSTANCE = new LegacyClassNameResolver();

    private LegacyClassNameResolver() {
    }

    @Override
    public CobolProgram resolve(ProgramId id, ClassLoader loader) {
        String className = ProgramSupport.classNameOf(id.value());
        try {
            Class<?> type = Class.forName(className, true, loader);
            return (CobolProgram) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new ProgramNotFoundException(id.value(), e);
        }
    }
}
