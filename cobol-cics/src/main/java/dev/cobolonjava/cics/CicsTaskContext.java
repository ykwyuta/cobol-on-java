package dev.cobolonjava.cics;

import java.time.Instant;
import java.util.Objects;

/** 一つの同期要求に固定される、framework非依存のtask文脈。 */
public record CicsTaskContext(
        CicsTaskId taskId,
        TransId transactionId,
        String owner,
        Instant startedAt) {

    public CicsTaskContext {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(transactionId, "transactionId");
        owner = requireText(owner, "owner");
        Objects.requireNonNull(startedAt, "startedAt");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        value = value.strip();
        if (value.isEmpty() || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be non-empty and contain no controls");
        }
        return value;
    }
}
