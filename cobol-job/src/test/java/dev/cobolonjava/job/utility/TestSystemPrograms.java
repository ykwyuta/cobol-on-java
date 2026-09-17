package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.interop.SystemProgramProvider;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/** 試験のために差し込むシステムのプログラム。読み込み器を受け取ったら 7 を返す。 */
public final class TestSystemPrograms implements SystemProgramProvider {

    @Override
    public CobolProgram find(String name, ClassLoader loader) {
        if (!name.equalsIgnoreCase("TESTSYS1")) {
            return null;
        }
        return new CobolProgram() {
            @Override
            public byte[] initialStorage() {
                return new byte[0];
            }

            @Override
            public void run(Storage storage, ProgramContext context, DataView[] arguments) {
                context.setReturnCode(loader == null ? 8 : 7);
            }
        };
    }
}
