package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.Optional;

/**
 * taskを終了し、任意で次のTRANSIDと会話dataを残す。
 *
 * @param immediate 端末入力を待たずに次のtaskを始める ({@code RETURN TRANSID IMMEDIATE})
 */
public record ReturnCommand(Optional<TransId> nextTransaction, CicsPayload payload, boolean immediate)
        implements CicsCommand {

    public ReturnCommand {
        Objects.requireNonNull(nextTransaction, "nextTransaction");
        Objects.requireNonNull(payload, "payload");
        if (immediate && nextTransaction.isEmpty()) {
            throw new IllegalArgumentException("RETURN IMMEDIATE requires a next TRANSID");
        }
    }

    public ReturnCommand(Optional<TransId> nextTransaction, CicsPayload payload) {
        this(nextTransaction, payload, false);
    }

    public static ReturnCommand complete() {
        return new ReturnCommand(Optional.empty(), CicsPayload.empty());
    }

    public static ReturnCommand next(TransId transId, CicsPayload payload) {
        return new ReturnCommand(Optional.of(Objects.requireNonNull(transId, "transId")), payload);
    }
}
