package cobol.generated;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/**
 * ジョブ実行の試験で動かすプログラム。
 *
 * <p>{@code cobol-job} は翻訳系に依存しない。したがって試験で動かすプログラムは、
 * 生成コードと同じ入口 ({@link CobolProgram}) を手で実装したものを、生成クラスと同じ
 * パッケージに置いて使う。ジョブ実行の側から見れば区別がない。
 */
public final class TestPrograms {

    private TestPrograms() {
    }
}
