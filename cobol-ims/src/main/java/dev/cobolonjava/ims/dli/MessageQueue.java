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
}
