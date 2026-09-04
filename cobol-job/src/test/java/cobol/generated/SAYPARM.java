package cobol.generated;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/** 渡された {@code PARM=} を表示する。引数の渡し方の試験で使う。 */
public final class SAYPARM implements CobolProgram {

    @Override
    public byte[] initialStorage() {
        return new byte[0];
    }

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        if (arguments.length == 0) {
            context.display(context.codePage().encode("NO PARM"), true, false);
            return;
        }
        byte[] bytes = arguments[0].toByteArray();
        int length = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
        byte[] text = new byte[length];
        System.arraycopy(bytes, 2, text, 0, length);
        context.display(context.codePage().encode("PARM(" + length + ")="), false, false);
        context.display(text, true, false);
    }
}
