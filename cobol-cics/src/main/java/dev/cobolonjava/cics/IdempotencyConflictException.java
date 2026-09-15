package dev.cobolonjava.cics;

import java.util.Objects;

/** 同じ冪等キーの要求が動いているか、同じキーで違う要求が来た (暫定判断 P-142)。 */
public final class IdempotencyConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final CicsOutcomeStorePort.Status status;

    public IdempotencyConflictException(CicsOutcomeStorePort.Status status) {
        super("idempotency key conflict: " + Objects.requireNonNull(status, "status"));
        this.status = status;
    }

    public CicsOutcomeStorePort.Status status() {
        return status;
    }
}
