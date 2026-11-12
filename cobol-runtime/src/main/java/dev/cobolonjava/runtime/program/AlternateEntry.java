package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.abend.StorageMap;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;

/**
 * 副入口 ({@code ENTRY '名前'}) から入る本体のプログラム。
 *
 * <p>副入口は<b>別のプログラムではない</b>。本体と同じ作業場所を見て、手続き部の途中から
 * 始まるだけである。名前で引いたときにこれを返せば、{@code CALL} の道はふつうの
 * プログラムを呼ぶのと変わらない。
 */
final class AlternateEntry implements CobolProgram {

    private final CobolProgram owner;
    private final String entry;

    AlternateEntry(CobolProgram owner, String entry) {
        this.owner = owner;
        this.entry = entry;
    }

    @Override
    public byte[] initialStorage() {
        return owner.initialStorage();
    }

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        owner.runEntry(storage, context, arguments, entry);
    }

    /** 動いているのは本体である。覚え書きにも本体の名前で出す。 */
    @Override
    public String name() {
        return owner.name();
    }

    @Override
    public boolean initialProgram() {
        return owner.initialProgram();
    }

    @Override
    public StorageMap storageMap() {
        return owner.storageMap();
    }

    @Override
    public ExternalRegion[] externalRegions() {
        return owner.externalRegions();
    }

    @Override
    public void performGlobalRange(int from, int through, Storage storage,
                                   ProgramContext context) {
        owner.performGlobalRange(from, through, storage, context);
    }
}
