package cobol.generated;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/**
 * {@code INDD} を読んで {@code OUTDD} へ書き写す。
 *
 * <p>DD 割当が効いていることを、<b>ファイルに残ったバイト</b>で確かめるために使う。
 */
public final class COPYDD implements CobolProgram {

    /** レコード領域 20 バイト。 */
    private static final int LENGTH = 20;

    @Override
    public byte[] initialStorage() {
        return new byte[LENGTH];
    }

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        Ops.checkFile(Ops.open(context, "IN", "INDD", 0, 0, 0, LENGTH, false),
                context.codePage(), "IN", false, false);
        Ops.checkFile(Ops.open(context, "OUT", "OUTDD", 1, 0, 0, LENGTH, false),
                context.codePage(), "OUT", false, false);
        while (true) {
            byte[] status = Ops.read(context, "IN", "INDD", storage, 0, LENGTH);
            if (!Ops.fileSucceeded(status, context.codePage())) {
                break;
            }
            Ops.write(context, "OUT", "OUTDD", storage, 0, LENGTH, LENGTH, LENGTH);
        }
        Ops.close(context, "IN", "INDD");
        Ops.close(context, "OUT", "OUTDD");
    }
}
