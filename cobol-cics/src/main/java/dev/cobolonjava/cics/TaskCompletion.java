package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.Optional;

/**
 * RETURNをtask coordinatorへ伝える正常な制御終了。
 *
 * @param immediate 次のtaskを端末入力なしで始める指定
 */
public record TaskCompletion(Optional<TransId> nextTransaction, CicsPayload payload, boolean immediate)
        implements CicsControl {

    public TaskCompletion {
        Objects.requireNonNull(nextTransaction, "nextTransaction");
        Objects.requireNonNull(payload, "payload");
        if (immediate && nextTransaction.isEmpty()) {
            throw new IllegalArgumentException("an immediate completion requires a next TRANSID");
        }
    }

    public TaskCompletion(Optional<TransId> nextTransaction, CicsPayload payload) {
        this(nextTransaction, payload, false);
    }
}
