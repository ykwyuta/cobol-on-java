package cobol.generated;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/**
 * {@code INDD} を読んで {@code OUTDD} へ書き写す。誤りを握り潰さない。
 *
 * <p>{@link COPYDD} との違いは<b>読み書きの結果を確かめる</b>ことだけである。
 * {@code FILE STATUS} も {@code USE} も書いていないプログラムがそうであるように、
 * ファイルの終わり以外の異常はそのまま異常終了になる。
 */
public final class IOFAIL implements CobolProgram {

    /** レコード領域 20 バイト。 */
    private static final int LENGTH = 20;

    @Override
    public byte[] initialStorage() {
        return new byte[LENGTH];
    }

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        Ops.checkFile(Ops.open(context, "IN", "INDD", 0, 0, 0, LENGTH, false),
                context.codePage(), "IN", false, false, context);
        Ops.checkFile(Ops.open(context, "OUT", "OUTDD", 1, 0, 0, LENGTH, false),
                context.codePage(), "OUT", false, false, context);
        while (true) {
            byte[] status = Ops.read(context, "IN", "INDD", storage, 0, LENGTH);
            // AT END は書いてある。それ以外の異常はここで止まる
            Ops.checkFile(status, context.codePage(), "IN", true, false, context);
            if (Ops.fileAtEnd(status, context.codePage())) {
                break;
            }
            Ops.checkFile(Ops.write(context, "OUT", "OUTDD", storage, 0, LENGTH, LENGTH, LENGTH),
                    context.codePage(), "OUT", false, false, context);
        }
        Ops.close(context, "IN", "INDD");
        Ops.close(context, "OUT", "OUTDD");
    }
}
