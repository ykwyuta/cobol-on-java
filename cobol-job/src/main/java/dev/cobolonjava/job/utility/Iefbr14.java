package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/**
 * {@code IEFBR14} (要件 FR-137)。
 *
 * <p><b>何もしない</b>プログラムである。名前のとおり、ホストでは「レジスタ 14 へ分岐する」
 * 命令 1 つでできている。使い道は DD 文の {@code DISP} を効かせることであり、
 * データセットを作る・消すためにこれを動かす。
 *
 * <p>ここを変えずに意味を持つのが要点である。何も書かなくても、割当てと処置は
 * ジョブ実行の側で起きる。データセットを作る・消す・目録へ載せる・外すは、すべて
 * {@code DISP} が決めることであってプログラムの仕事ではない。
 */
public final class Iefbr14 extends UtilityProgram {

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        context.setReturnCode(0);
    }
}
