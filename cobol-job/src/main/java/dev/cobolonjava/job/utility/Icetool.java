package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/**
 * {@code ICETOOL} (要件 FR-137)。
 *
 * <p>{@code TOOLIN} に書いた操作を並べて実行する道具である。1 つ 1 つの操作は
 * {@link Dfsort} を呼び出す形になるが、{@code COUNT}、{@code DISPLAY}、{@code OCCUR} など
 * 整列とは別の働きも持つ。ここではまだ実装しておらず、<b>黙って何もしない代わりに</b>
 * 対応していないことを報告する (暫定判断 P-047)。
 */
public final class Icetool extends UtilityProgram {

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        print(context, "ICE000I ICETOOL IS NOT SUPPORTED YET - USE SORT INSTEAD");
        context.setReturnCode(16);
    }
}
