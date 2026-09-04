package cobol.generated;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/**
 * {@code SYSIN} を読んで {@code REPORT} へ書く。
 *
 * <p>埋め込みデータとスプールの両方を通す。行順編成なので、読んだ行がそのまま出る。
 */
public final class WRITELOG implements CobolProgram {

    private static final int LENGTH = 30;

    @Override
    public byte[] initialStorage() {
        return new byte[LENGTH];
    }

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        // 行順編成 (RecordFormat.LINE = 2) で開く
        Ops.checkFile(Ops.open(context, "SYSIN", "SYSIN", 0, 1, 2, LENGTH, false),
                context.codePage(), "SYSIN", false, false);
        Ops.checkFile(Ops.open(context, "REPORT", "REPORT", 1, 1, 2, LENGTH, false),
                context.codePage(), "REPORT", false, false);
        while (true) {
            byte[] status = Ops.read(context, "SYSIN", "SYSIN", storage, 0, LENGTH);
            if (!Ops.fileSucceeded(status, context.codePage())) {
                break;
            }
            Ops.write(context, "REPORT", "REPORT", storage, 0, LENGTH, LENGTH, LENGTH);
        }
        Ops.close(context, "SYSIN", "SYSIN");
        Ops.close(context, "REPORT", "REPORT");
    }
}
