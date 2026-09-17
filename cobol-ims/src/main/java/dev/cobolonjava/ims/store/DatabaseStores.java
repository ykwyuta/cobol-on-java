package dev.cobolonjava.ims.store;

import dev.cobolonjava.runtime.program.ProgramContext;
import java.util.ServiceLoader;

/** 差し込まれた置き場を引く。 */
public final class DatabaseStores {

    private DatabaseStores() {
    }

    /**
     * 構成されている差し込みの置き場を開く。
     *
     * @return どれも構成されていなければ {@code null} (呼ぶ側はデータセットの置き場を使う)
     */
    public static DatabaseStore open(ProgramContext context) {
        for (DatabaseStoreProvider provider
                : ServiceLoader.load(DatabaseStoreProvider.class, DatabaseStores.class.getClassLoader())) {
            DatabaseStore store = provider.open(context);
            if (store != null) {
                return store;
            }
        }
        return null;
    }
}
