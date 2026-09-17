package dev.cobolonjava.ims.store;

/**
 * 処理済みの電文を覚えておく口 (ADR-0014 の決定 2、暫定判断 P-163)。
 *
 * <p>電文のキューは at-least-once である。確定の途中で落ちれば、同じ電文がもう一度配られる。
 * 処理済みの電文の ID を<b>業務の更新と同じトランザクション</b>で書いておけば、再配信を見分けて捨てられる。
 */
public interface MessageInbox {

    /** この電文はすでに処理され、確定しているか。 */
    boolean seen(String messageId);

    /**
     * 次の確定で、この電文を処理済みとして書く。
     *
     * <p>確定が失敗すれば書かれない。書かれなければ、再配信された電文はもう一度処理される。
     */
    void record(String messageId);
}
