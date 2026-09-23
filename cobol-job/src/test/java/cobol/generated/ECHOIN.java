package cobol.generated;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/**
 * {@code ACCEPT} を 2 回して、読んだものを {@code DISPLAY} する。{@code ACCEPT} と
 * {@code DISPLAY} がステップの SYSIN / SYSOUT の DD へ結ばれることの試験で使う。
 * 入力が尽きていれば空を読む (P-083)。
 */
public final class ECHOIN implements CobolProgram {

    @Override
    public byte[] initialStorage() {
        return new byte[0];
    }

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        for (int i = 1; i <= 2; i++) {
            String line = context.readLine().stripTrailing();
            context.display(context.codePage().encode("IN" + i + "=[" + line + "]"), true, false);
        }
    }
}
