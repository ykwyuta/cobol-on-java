package cobol.generated;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/** かならず異常終了する。異常終了の伝わり方の試験で使う。 */
public final class BOOM implements CobolProgram {

    @Override
    public byte[] initialStorage() {
        return new byte[0];
    }

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        throw new IllegalStateException("data exception");
    }
}
