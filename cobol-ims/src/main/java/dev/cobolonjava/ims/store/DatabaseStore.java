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

    /**
     * 処理済みの電文を覚える口 (ADR-0014 の決定 2)。{@link #commit} と同じトランザクションで書く置き場だけが持つ。
     *
     * @return 持たなければ {@code null} (再配信された電文はもう一度処理される)
     */
    default MessageInbox inbox() {
        return null;
    }

    /**
     * 記号 CHKP が退避した域の置き場 (P-164)。{@link #commit} と同じ確定で書く置き場だけが持つ。
     *
     * @return 持たなければ {@code null} (記号 CHKP は断る)
     */
    default CheckpointStore checkpoints() {
        return null;
    }

    @Override
    void close();
}
