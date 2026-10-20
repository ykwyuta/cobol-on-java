package dev.cobolonjava.runtime.program;

import dev.cobolonjava.runtime.storage.Storage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 実行単位の作業単位に加わった資源の表 (設計 88 §4.3)。
 *
 * <p>実行単位は {@link AddressSpace} と同じく特殊レジスタの置き場で見分ける。資源は加わった順に確定し、
 * 1 つが失敗しても残りを諦めない ({@link ProgramContext#closeFiles()} と同じ考え)。
 *
 * <p><b>2 相の確約ではない</b>。ある資源の確定が失敗しても、先に確定した資源は戻らない (暫定判断 P-254)。
 */
public final class RecoverableResources {

    private static final Map<Storage, RecoverableResources> BY_RUN_UNIT =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final List<RecoverableResource> resources = new ArrayList<>();

    private RecoverableResources() {
    }

    /** 文脈の実行単位の表。 */
    public static RecoverableResources of(ProgramContext context) {
        return BY_RUN_UNIT.computeIfAbsent(context.registers(), ignored -> new RecoverableResources());
    }

    /** 加える。同じもの ({@code ==}) を 2 度加えても 1 度である。 */
    public synchronized void enlist(RecoverableResource resource) {
        for (RecoverableResource r : resources) {
            if (r == resource) {
                return;
            }
        }
        resources.add(resource);
    }

    /** 外す (MQDISC のように、資源の側が先に終わったとき)。 */
    public synchronized void delist(RecoverableResource resource) {
        resources.removeIf(r -> r == resource);
    }

    /** 同期点。{@code commit} が偽なら取り消す。 */
    public void syncpoint(boolean commit) {
        RuntimeException first = null;
        for (RecoverableResource r : snapshot()) {
            try {
                if (commit) {
                    r.commit();
                } else {
                    r.backout();
                }
            } catch (RuntimeException failure) {
                // 同期点の失敗は呼んだ側 (SYNCPOINT) へ返す。ただし残りの資源は諦めない
                if (first == null) {
                    first = failure;
                } else {
                    first.addSuppressed(failure);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /**
     * 実行単位の終わり。どの資源も終え、表を空にする。誤りは伝えない: 実行単位はすでに異常終了しているかも
     * しれず、そこへ別の誤りをかぶせると最初に起きたことが分からなくなる。
     */
    public void end(boolean commit) {
        List<RecoverableResource> all;
        synchronized (this) {
            all = new ArrayList<>(resources);
            resources.clear();
        }
        for (RecoverableResource r : all) {
            try {
                r.end(commit);
            } catch (RuntimeException ignored) {
                // 終えられなかった資源のために、終えられる資源を諦めない
            }
        }
    }

    private synchronized List<RecoverableResource> snapshot() {
        return new ArrayList<>(resources);
    }
}
