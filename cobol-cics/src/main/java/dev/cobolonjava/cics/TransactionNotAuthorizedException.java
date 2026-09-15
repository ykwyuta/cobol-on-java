package dev.cobolonjava.cics;

import java.util.Objects;

/**
 * 利用者の user ID がこの transaction を起こせない (設計 84、暫定判断 P-145)。task は起こしていない。
 *
 * <p>入口は 403 にする。応答に user ID や権限の構成を出さない。
 */
public final class TransactionNotAuthorizedException extends RuntimeException {

    private final TransId transaction;

    public TransactionNotAuthorizedException(TransId transaction) {
        super("the user is not authorized to attach transaction " + Objects.requireNonNull(transaction, "transaction")
                .value());
        this.transaction = transaction;
    }

    public TransId transaction() {
        return transaction;
    }
}
