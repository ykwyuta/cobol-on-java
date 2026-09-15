package dev.cobolonjava.cics;

import java.util.List;

/**
 * 一時データ (transient data) のキュー (暫定判断 P-137)。region の構成が持ち、task どうしで分け合う。
 *
 * <p>扱うのは region の中の区画内 (intrapartition) のキューだけである。区画外 (extrapartition) のデータセット、
 * trigger level による自動の task の開始 (ATI)、回復は持たない。定義の無いキューは QIDERR になる。
 *
 * <p>返す条件の RESP2 は、WRITEQ TD / READQ TD / DELETEQ TD の頁が値を示さないので 0 とする。
 */
public interface CicsTransientDataPort {

    record Result(int response, int response2) {
    }

    /** 読んだ結果。NORMAL でなければ data は null。 */
    record Read(int response, int response2, byte[] data) {
    }

    /** record を末尾に足す。 */
    Result write(String queue, byte[] data);

    /** 先頭の record を取り出す。読んだ record はキューから消える。 */
    Read read(String queue);

    /** キューの record をすべて消す。 */
    Result delete(String queue);

    /** キューを 1 つも定義していない region。どの名前も QIDERR になる。 */
    static CicsTransientDataPort none() {
        return inMemory(List.of());
    }

    /** 定義したキューを 1 つの JVM の中で持つ。 */
    static CicsTransientDataPort inMemory(List<CicsTransientDataQueueDefinition> queues) {
        return new InMemoryCicsTransientData(queues);
    }
}
