package dev.cobolonjava.db2;

import java.util.Objects;

/** 値や資格情報を含めない、UOW rollbackの構造化理由。 */
public record RollbackReason(Kind kind, String code) {

    public enum Kind {
        EXPLICIT,
        CICS_ABEND,
        SQL_FAILURE,
        TIMEOUT,
        SHUTDOWN,
        RESOURCE_CLEANUP
    }

    public RollbackReason {
        Objects.requireNonNull(kind, "kind");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("rollback reason code must not be blank");
        }
    }

    public static RollbackReason cleanup() {
        return new RollbackReason(Kind.RESOURCE_CLEANUP, "TASK-CLOSE");
    }
}
