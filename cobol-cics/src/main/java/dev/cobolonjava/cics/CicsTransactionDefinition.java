package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.interop.ProgramId;
import java.time.Duration;
import java.util.Objects;

/** 許可済みTRANSIDと初期プログラム、入力上限を結び付ける不変定義。 */
public record CicsTransactionDefinition(
        TransId transId,
        ProgramId initialProgram,
        Duration taskTimeout,
        int maxCommareaBytes,
        int maxContainers,
        int maxContainerBytes,
        int maxContainerTotalBytes,
        boolean enabled) {

    public CicsTransactionDefinition {
        Objects.requireNonNull(transId, "transId");
        Objects.requireNonNull(initialProgram, "initialProgram");
        Objects.requireNonNull(taskTimeout, "taskTimeout");
        if (taskTimeout.isZero() || taskTimeout.isNegative()) {
            throw new IllegalArgumentException("taskTimeout must be positive");
        }
        requireNonNegative(maxCommareaBytes, "maxCommareaBytes");
        requireNonNegative(maxContainers, "maxContainers");
        requireNonNegative(maxContainerBytes, "maxContainerBytes");
        requireNonNegative(maxContainerTotalBytes, "maxContainerTotalBytes");
        if (maxContainers > 0 && maxContainerBytes == 0) {
            throw new IllegalArgumentException(
                    "maxContainerBytes must be positive when containers are allowed");
        }
        if (maxContainers > 0 && maxContainerTotalBytes == 0) {
            throw new IllegalArgumentException(
                    "maxContainerTotalBytes must be positive when containers are allowed");
        }
    }

    public void validate(CicsPayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.commareaLength() > maxCommareaBytes) {
            throw new CicsInputLimitException("COMMAREA", payload.commareaLength(), maxCommareaBytes);
        }
        if (payload.containerCount() > maxContainers) {
            throw new CicsInputLimitException("container count", payload.containerCount(), maxContainers);
        }
        if (payload.maxContainerLength() > maxContainerBytes) {
            throw new CicsInputLimitException(
                    "single container", payload.maxContainerLength(), maxContainerBytes);
        }
        if (payload.containerTotalLength() > maxContainerTotalBytes) {
            throw new CicsInputLimitException(
                    "container total", payload.containerTotalLength(), maxContainerTotalBytes);
        }
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
