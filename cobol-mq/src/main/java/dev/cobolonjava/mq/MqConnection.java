package dev.cobolonjava.mq;

import dev.cobolonjava.runtime.program.RecoverableResource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 実行単位の待ち行列管理者への接続: 開いた handle と、作業単位 (同期点の下で置いた・取った電文)。
 *
 * <p>どの操作も待ち行列管理者の錠を持って呼ぶ。
 */
final class MqConnection {

    /** 開いた対象。{@code options} は MQOPEN の選択。 */
    record Handle(int hobj, LocalQueue queue, int options) {

        boolean has(int option) {
            return (options & option) != 0;
        }

        boolean input() {
            return has(MqConstants.MQOO_INPUT_AS_Q_DEF | MqConstants.MQOO_INPUT_SHARED
                    | MqConstants.MQOO_INPUT_EXCLUSIVE);
        }
    }

    private record Staged(LocalQueue queue, LocalQueue.Entry entry) {
    }

    private final MqQueueManager manager;
    private final int handle;
    /** MQCONN で明示に繋いだか。2 度目の MQCONN が MQRC_ALREADY_CONNECTED を返すのに使う。 */
    boolean explicit;
    private final Map<Integer, Handle> handles = new HashMap<>();
    private int nextObject = 1;
    private final List<Staged> puts = new ArrayList<>();
    private final List<Staged> gets = new ArrayList<>();

    /**
     * 実行単位の作業単位に加える口 (設計 88 §4.3)。CICS の SYNCPOINT がここで確約・取り消しし、実行単位の終わりで
     * 接続を終える。runtime は待ち行列管理者の錠を知らないので、ここで取る。
     */
    final RecoverableResource resource = new RecoverableResource() {
        @Override
        public void commit() {
            manager.lock.lock();
            try {
                MqConnection.this.commit();
            } finally {
                manager.lock.unlock();
            }
        }

        @Override
        public void backout() {
            manager.lock.lock();
            try {
                MqConnection.this.backout();
            } finally {
                manager.lock.unlock();
            }
        }

        @Override
        public void end(boolean commit) {
            manager.lock.lock();
            try {
                MqConnection.this.end(commit);
                manager.forget(MqConnection.this);
            } finally {
                manager.lock.unlock();
            }
        }
    };

    MqConnection(MqQueueManager manager, int handle) {
        this.manager = manager;
        this.handle = handle;
    }

    int handle() {
        return handle;
    }

    Handle open(LocalQueue queue, int options) {
        Handle h = new Handle(nextObject++, queue, options);
        handles.put(h.hobj(), h);
        if (h.input()) {
            queue.openInput++;
            if (h.has(MqConstants.MQOO_INPUT_EXCLUSIVE)) {
                queue.exclusive = true;
            }
        }
        if (h.has(MqConstants.MQOO_OUTPUT)) {
            queue.openOutput++;
        }
        return h;
    }

    Handle handle(int hobj) {
        return handles.get(hobj);
    }

    void close(Handle h) {
        handles.remove(h.hobj());
        if (h.input()) {
            h.queue().openInput--;
            if (h.has(MqConstants.MQOO_INPUT_EXCLUSIVE)) {
                h.queue().exclusive = false;
            }
        }
        if (h.has(MqConstants.MQOO_OUTPUT)) {
            h.queue().openOutput--;
        }
    }

    /** 電文を置く。同期点の下なら確約まで見えない。 */
    void put(LocalQueue queue, LocalQueue.Entry entry, boolean syncpoint) {
        if (syncpoint) {
            queue.uncommitted++;
            puts.add(new Staged(queue, entry));
        } else {
            queue.insert(entry);
            manager.arrived.signalAll();
        }
    }

    /** 渡す電文を待ち行列から外す。同期点の下なら、取り消しで戻せるように覚えておく。 */
    void take(LocalQueue queue, LocalQueue.Entry entry, boolean syncpoint) {
        queue.visible.remove(entry);
        if (syncpoint) {
            queue.uncommitted++;
            gets.add(new Staged(queue, entry));
        }
    }

    /** MQCMIT。置いた電文が見えるようになり、取った電文は消える。 */
    void commit() {
        for (Staged s : puts) {
            s.queue().uncommitted--;
            s.queue().insert(s.entry());
        }
        for (Staged s : gets) {
            s.queue().uncommitted--;
        }
        boolean any = !puts.isEmpty();
        puts.clear();
        gets.clear();
        if (any) {
            manager.arrived.signalAll();
        }
    }

    /**
     * MQBACK。置いた電文は捨て、取った電文は元の位置 (同じ通し番号) へ戻す。戻した電文の BackoutCount を 1 増やす。
     * 公開文書の MQMD の BackoutCount の説明のとおりである。
     */
    void backout() {
        for (Staged s : puts) {
            s.queue().uncommitted--;
        }
        for (Staged s : gets) {
            s.queue().uncommitted--;
            LocalQueue.Entry e = s.entry();
            MqMessage backedOut = e.message().withInteger(Structure.MD_BACKOUT_COUNT, e.message().backoutCount() + 1);
            s.queue().insert(new LocalQueue.Entry(backedOut, e.sequence(), e.priority(), e.expiresAt()));
        }
        boolean any = !gets.isEmpty();
        puts.clear();
        gets.clear();
        if (any) {
            manager.arrived.signalAll();
        }
    }

    /** 接続を終える: 作業単位を確約するか取り消し、開いた handle を閉じる。 */
    void end(boolean commit) {
        if (commit) {
            commit();
        } else {
            backout();
        }
        for (Handle h : new ArrayList<>(handles.values())) {
            close(h);
        }
    }
}
