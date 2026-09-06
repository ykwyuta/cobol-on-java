package cobol.generated;

import dev.cobolonjava.runtime.decimal.DataException;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/**
 * 数値項目の不正データで異常終了する。
 *
 * <p>翻訳したプログラムなら数値でないバイトを数として使ったときにここへ来る。
 * 試験ではその条件を直に立てる。ジョブ実行から見れば区別がない。
 */
public final class BADDATA implements CobolProgram {

    @Override
    public byte[] initialStorage() {
        return new byte[0];
    }

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        throw new DataException("invalid digit in a packed decimal field");
    }
}
