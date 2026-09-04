package cobol.generated;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.program.SpecialRegisterArea;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/** {@code PARM=} に書かれた数を復帰コードにする。条件判定の試験で使う。 */
public final class SETRC implements CobolProgram {

    @Override
    public byte[] initialStorage() {
        return new byte[0];
    }

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        int code = arguments.length == 0 ? 0 : parmValue(context, arguments[0]);
        // RETURN-CODE は実行の全体で 1 つの置き場にある (要件 FR-084)
        context.registers().view(SpecialRegisterArea.RETURN_CODE_OFFSET, 2)
                .setBytes(new byte[] {(byte) (code >> 8), (byte) code});
    }

    /** 先頭 2 バイトが長さ、そのあとが中身である (要件 FR-134)。 */
    private static int parmValue(ProgramContext context, DataView parm) {
        byte[] bytes = parm.toByteArray();
        int length = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
        byte[] text = new byte[length];
        System.arraycopy(bytes, 2, text, 0, length);
        return Integer.parseInt(context.codePage().decode(text).trim());
    }
}
