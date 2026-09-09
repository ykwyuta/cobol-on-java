package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.Optional;

/** taskを終了し、任意で次のTRANSIDと会話dataを残す。 */
public record ReturnCommand(Optional<TransId> nextTransaction, CicsPayload payload)
        implements CicsCommand {

    public ReturnCommand {
        Objects.requireNonNull(nextTransaction, "nextTransaction");
        Objects.requireNonNull(payload, "payload");
    }

    public static ReturnCommand complete() {
        return new ReturnCommand(Optional.empty(), CicsPayload.empty());
    }

    public static ReturnCommand next(TransId transId, CicsPayload payload) {
        return new ReturnCommand(Optional.of(Objects.requireNonNull(transId, "transId")), payload);
    }
}
