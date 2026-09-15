package dev.cobolonjava.ims.db;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 最後の同期点からの変更を取り消すための記録 (暫定判断 P-157)。
 *
 * <p>{@link HierarchicalDatabase} の ISRT / REPL / DLET が逆の操作を積む。同期点で捨て、巻き戻しでは積んだ逆順に
 * 戻す。逆順に戻すので、兄弟の並びの位置は積んだときのまま使える。
 */
public final class UndoLog {

    private final Deque<Runnable> undo = new ArrayDeque<>();

    void record(Runnable reverse) {
        undo.push(reverse);
    }

    /** 同期点。ここまでの変更を確定する。 */
    public void commit() {
        undo.clear();
    }

    /** 最後の同期点まで戻す。 */
    public void rollback() {
        while (!undo.isEmpty()) {
            undo.pop().run();
        }
    }

    /** 確定していない変更があるか。 */
    public boolean pending() {
        return !undo.isEmpty();
    }
}
