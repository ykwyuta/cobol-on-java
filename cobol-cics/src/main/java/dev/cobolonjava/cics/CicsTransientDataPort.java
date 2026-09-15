package dev.cobolonjava.cics;

import java.util.List;
import java.util.Optional;

/**
 * 一時データ (transient data) のキュー (暫定判断 P-137、P-144、P-148)。region の構成が持ち、task どうしで分け合う。
 *
 * <p>区画内 (intrapartition) のキューを持つ。区画外 (extrapartition) のデータセットは {@link #withExtrapartition} で足す。
 * 定義の無いキューは QIDERR になる。
 *
 * <h2>回復</h2>
 * <p>回復可能なキュー ({@link CicsTransientDataQueueDefinition.Recovery#LOGICAL}) への命令は、task の変更として
 * {@link #write(CicsTaskId, Optional, String, byte[])} などで渡る。task の UOW に入る置き場は、同期点の commit
 * ({@link #commitUnitOfWork}) で確定し、ROLLBACK と ABEND ({@link #rollbackUnitOfWork}) で取り消す (設計 85 §7.2)。
 * 回復不能のキューは直ちに確定する。
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

    /**
     * task の WRITEQ TD。connection は task の業務の UOW (STRICT の task 境界が置く)。既定は回復の区別を持たず、
     * {@link #write(String, byte[])} と同じに直ちに確定する。
     */
    default Result write(CicsTaskId task, Optional<CicsTaskConnection> connection, String queue, byte[] data) {
        return write(queue, data);
    }

    /** task の READQ TD。 */
    default Read read(CicsTaskId task, Optional<CicsTaskConnection> connection, String queue) {
        return read(queue);
    }

    /** task の DELETEQ TD。 */
    default Result delete(CicsTaskId task, Optional<CicsTaskConnection> connection, String queue) {
        return delete(queue);
    }

    /** 同期点の commit。task が貯めた回復可能なキューの変更を確定する。 */
    default void commitUnitOfWork(CicsTaskId task) {
    }

    /** ROLLBACK と ABEND。task が貯めた回復可能なキューの変更を取り消す。 */
    default void rollbackUnitOfWork(CicsTaskId task) {
    }

    /** 区画外のキューを足した port。区画外の名前が区画内の同じ名前より先に引かれる。 */
    default CicsTransientDataPort withExtrapartition(List<CicsExtrapartitionQueueDefinition> queues) {
        return new CicsExtrapartitionQueues(this, queues);
    }

    /** キューを 1 つも定義していない region。どの名前も QIDERR になる。 */
    static CicsTransientDataPort none() {
        return inMemory(List.of());
    }

    /** 定義したキューを 1 つの JVM の中で持つ。 */
    static CicsTransientDataPort inMemory(List<CicsTransientDataQueueDefinition> queues) {
        return new InMemoryCicsTransientData(queues);
    }
}
