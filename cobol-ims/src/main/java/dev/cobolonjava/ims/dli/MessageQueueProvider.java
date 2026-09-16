package dev.cobolonjava.ims.dli;

/**
 * 電文のキューを別のモジュールから差し込む口 ({@link java.util.ServiceLoader} で引く)。
 *
 * <p>{@code cobol-ims} は JMS の型を持たない (設計 78 §2.2)。ブローカのキューは {@code cobol-ims-jms} が差し込む。
 * {@link dev.cobolonjava.ims.store.DatabaseStoreProvider} と同じ形である。
 */
public interface MessageQueueProvider {

    /**
     * 取引コードのキューを開く。
     *
     * @param transactionCode 読むキューを決める取引コード
     * @return この差し込みを使う構成になっていなければ {@code null}
     */
    MessageQueue open(String transactionCode);
}
