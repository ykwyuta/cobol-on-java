package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.List;
import java.util.Objects;

/** {@link JavaCallable} を既存の生成クラス ABI へ接続する内部アダプタ。 */
final class JavaProgramAdapter implements CobolProgram {

    private static final byte[] NO_STORAGE = new byte[0];

    private final ProgramId id;
    private final JavaCallable callable;
    private final ClassLoader loader;

    JavaProgramAdapter(ProgramId id, JavaCallable callable, ClassLoader loader) {
        this.id = Objects.requireNonNull(id, "id");
        this.callable = Objects.requireNonNull(callable, "callable");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    @Override
    public byte[] initialStorage() {
        return NO_STORAGE;
    }

    @Override
    public String name() {
        return id.value();
    }

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        try {
            callable.invoke(new JavaCallContext(id, context, loader), List.of(arguments));
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new JavaProgramException(id, e);
        }
    }
}
