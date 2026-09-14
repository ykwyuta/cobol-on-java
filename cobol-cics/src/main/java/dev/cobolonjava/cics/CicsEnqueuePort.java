package dev.cobolonjava.cics;

import java.time.Duration;

/**
 * {@code ENQ} / {@code DEQ} の資源の排他 (暫定判断 P-128)。
 *
 * <p>資源は名前の byte 列である。同じ region の task どうしで分け合うので、region の構成
 * ({@link CicsEnvironment}) が 1 つ持つ。既定は 1 つの JVM の中だけで効く {@link #inMemory()} である。
 * 複数の JVM で同じ資源を分け合うなら、共有の実装へ差し替える。
 */
public interface CicsEnqueuePort {

    /**
     * 資源を得る。自分の task が既に持っていれば数を増やす。
     *
     * @param taskScope {@code TASK} を書いたか。書かなければ UOW の間だけ持つ
     * @param wait      他の task が持っているときに待つか ({@code NOSUSPEND} を書けば待たない)
     * @param maxWait   待てる長さ。null なら限りなく待つ。越えたら task を失敗させる
     * @return 得たら true。待たない指定で他の task が持っていれば false
     */
    boolean enqueue(CicsTaskId owner, byte[] resource, boolean taskScope, boolean wait, Duration maxWait);

    /** 資源を 1 つ返す。持っていなければ何もしない。 */
    void dequeue(CicsTaskId owner, byte[] resource, boolean taskScope);

    /** SYNCPOINT で、UOW の間だけ持つ資源を返す。 */
    void releaseUnitOfWork(CicsTaskId owner);

    /** task の終わりに、持っている資源をすべて返す。 */
    void releaseTask(CicsTaskId owner);

    static CicsEnqueuePort inMemory() {
        return new InMemoryCicsEnqueues();
    }
}
