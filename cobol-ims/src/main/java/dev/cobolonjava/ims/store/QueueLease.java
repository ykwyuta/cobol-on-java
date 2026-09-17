package dev.cobolonjava.ims.store;

/**
 * 取引コードのキューを読む領域を 1 つに限る借用 (暫定判断 P-167、P-105 の解消条件)。
 *
 * <p>同じ取引コードのキューを 2 つの領域が読むと、電文は交互に配られて<b>取引コードの中の順序が崩れる</b>
 * (2026-09-16 に実測)。JMS には消費者の数を問う口が無く、排他の消費者も移植できる形では書けない。そこで
 * 置き場の側に借用の行を置いて、2 つ目の領域を起こさずに断る。
 *
 * <p>借りられるのは、借用を持つ置き場 (RDB) と、取引コードを名乗るキュー (JMS) がそろっているときだけである。
 * そろわなければ借用は働かず、順序は運用の決めごとのままになる。
 */
public interface QueueLease {

    /**
     * 取引コードを借りる。
     *
     * @param transactionCode 読むキューの取引コード
     * @param owner           借り手を見分ける名前
     * @return 返すための持ち手。{@code close} で返す
     * @throws QueueLeaseException ほかの生きた領域が借りているとき
     */
    Held acquire(String transactionCode, String owner);

    /** 借りている間の持ち手。閉じると返す。 */
    interface Held extends AutoCloseable {

        @Override
        void close();
    }
}
