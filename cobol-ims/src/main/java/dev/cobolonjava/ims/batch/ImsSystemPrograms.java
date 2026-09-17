package dev.cobolonjava.ims.batch;

import dev.cobolonjava.runtime.interop.SystemProgramProvider;
import dev.cobolonjava.runtime.program.CobolProgram;

/** ジョブ実行へ {@code DFSRRC00} を差し込む (設計 78 §7.1)。 */
public final class ImsSystemPrograms implements SystemProgramProvider {

    @Override
    public CobolProgram find(String name, ClassLoader loader) {
        return "DFSRRC00".equalsIgnoreCase(name) ? new Dfsrrc00(loader) : null;
    }
}
