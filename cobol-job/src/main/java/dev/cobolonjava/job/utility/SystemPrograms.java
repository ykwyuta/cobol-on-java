package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.interop.SystemProgramProvider;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.util.ServiceLoader;

/**
 * 別のモジュールが差し込んだプログラム ({@link SystemProgramProvider})。
 *
 * <p>ユーティリティの次に、翻訳したクラスより先に引く。IMS の {@code DFSRRC00} のように、ホストでも
 * システムのライブラリにあるものだからである。
 */
public final class SystemPrograms {

    private SystemPrograms() {
    }

    /**
     * 名前で引く。
     *
     * @return 差し込んだどれも知らない名前なら {@code null}
     */
    public static CobolProgram find(String name, ClassLoader loader) {
        for (SystemProgramProvider provider : ServiceLoader.load(SystemProgramProvider.class, loader)) {
            CobolProgram program = provider.find(name, loader);
            if (program != null) {
                return program;
            }
        }
        return null;
    }
}
