package dev.cobolonjava.cics;

public final class UnknownTransactionException extends IllegalArgumentException {

    private final TransId transId;

    public UnknownTransactionException(TransId transId) {
        super("unknown TRANSID: " + transId.value());
        this.transId = transId;
    }

    public TransId transId() {
        return transId;
    }
}
