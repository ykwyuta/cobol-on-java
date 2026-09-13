package dev.cobolonjava.cics;

public final class DisabledTransactionException extends IllegalStateException {

    private final TransId transId;

    public DisabledTransactionException(TransId transId) {
        super("disabled TRANSID: " + transId.value());
        this.transId = transId;
    }

    public TransId transId() {
        return transId;
    }
}
