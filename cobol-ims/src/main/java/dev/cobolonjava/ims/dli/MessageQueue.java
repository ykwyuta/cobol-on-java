package dev.cobolonjava.ims.dli;

/**
 * 1 つの取引コードのメッセージキューの中立の口 (設計 78 §4.1)。
 *
 * <p>設計 78 の {@code ImsQueuePort} のうち、MPP が 1 本のプログラムで電文を受けて応答する分だけを持つ。
 * 待ち合わせ、コミット、SPA、CHNG による宛先の差し替えはまだ無い (P-156)。
 */
public interface MessageQueue {

    /**
     * 次の入力の電文を取り出す。
     *
     * @return キューが空なら {@code null}
     */
    InputMessage next();

    /** 応答の電文を送る。 */
    void send(OutputMessage message);

    /**
     * 同期点。ここまでに取り出した電文を確定し (ACK し)、送った応答を出す。
     *
     * <p>データベースの置き場を確定したあとに呼ばれる。ADR-0014 の「JDBC のコミットが成功してから ACK する」である。
     * メモリのキューのように確定する必要が無ければ、何もしなくてよい。
     */
    default void commit() {
    }

    /** 最後の同期点まで戻す。取り出した電文は戻され、送っていない応答は捨てられる。 */
    default void rollback() {
    }

    /**
     * 入力のキューへ電文を積む。端末の代わりに測定と試験が電文を入れるための口である (P-165)。
     *
     * <p>実機では端末や OTMA がキューへ入れるので、業務のプログラムはこれを呼ばない。
     *
     * @return 積めなければ {@code false} (積む手立てを持たない実装)
     */
    default boolean enqueue(InputMessage message) {
        return false;
    }
}
