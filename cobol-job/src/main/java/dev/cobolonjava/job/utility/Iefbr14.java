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
 * <p>いまは {@code DISP} が実行へ効いていない (暫定判断 P-045)。したがってこれを動かしても
 * 本当に何も起きない。効かせる段になれば、ここを変えずに意味を持つようになる。
 */
public final class Iefbr14 extends UtilityProgram {

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        context.setReturnCode(0);
    }
}
