package cobol.generated;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/**
 * 連絡節の項目を触るが、ジョブが {@code PARM} を渡していなければ何も渡されていない。
 *
 * <p>翻訳したプログラムの連絡節参照と同じ入口を通る。
 */
public final class NOARG implements CobolProgram {

    @Override
    public byte[] initialStorage() {
        return new byte[0];
    }

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        Ops.linkage(arguments, 0, "LK-PARM");
    }
}
