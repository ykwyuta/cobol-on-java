package dev.cobolonjava.db2;

import java.time.Duration;
import java.util.Objects;

/** taskからadapterへ渡すUOW能力と期限。 */
public record UnitOfWorkOptions(
        Db2ExecutionProfile profile,
        Duration timeout,
        boolean readOnly,
        boolean requiresDriverManagedHold) {

    public UnitOfWorkOptions {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("UOW timeout must be positive");
        }
        if (requiresDriverManagedHold
                && profile != Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD) {
            throw new Db2ProfileMismatchException(
                    "WITH HOLD inventory requires DB2_DRIVER_MANAGED_HOLD profile");
        }
    }
}
