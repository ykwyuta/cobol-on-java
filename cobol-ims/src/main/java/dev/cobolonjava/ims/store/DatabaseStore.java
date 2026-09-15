package dev.cobolonjava.ims.store;

import dev.cobolonjava.ims.db.HierarchicalDatabase;
import dev.cobolonjava.ims.dbd.DatabaseDefinition;
import java.util.Collection;

/**
 * データベースの置き場の中立の口 (設計 78 §3、ADR-0013、暫定判断 P-160)。
 *
 * <p>領域は開いたデータベースをメモリの上で動かし、同期点ごとに {@link #commit} で前の同期点から変わった分を
 * 置き場へ確定する。異常終了や ROLB で戻した分は置き場に届かない。
 */
public interface DatabaseStore extends AutoCloseable {

    /** DBD のデータベースを読み込む。置き場に無ければ空のデータベースである。 */
    HierarchicalDatabase open(DatabaseDefinition dbd);

    /**
     * 同期点。{@link HierarchicalDatabase#changedRootKeys()} が示す根を置き場へ確定する。
     * 失敗すれば例外を投げ、呼ぶ側はメモリの変更を最後の同期点まで戻す。
     */
    void commit(Collection<HierarchicalDatabase> databases);

    @Override
    void close();
}
