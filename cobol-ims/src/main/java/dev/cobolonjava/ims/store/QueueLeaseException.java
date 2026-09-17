package dev.cobolonjava.ims.store;

/**
 * ほかの領域が同じ取引コードのキューを読んでいるので、この領域を起こさない (P-167)。
 *
 * <p>置き場の失敗 ({@link DatabaseStoreException}) とは分けている。構成の誤りでも故障でもなく、
 * 「二重に起こした」という運用の誤りだからである。
 */
public class QueueLeaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public QueueLeaseException(String message) {
        super(message);
    }

    public QueueLeaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
