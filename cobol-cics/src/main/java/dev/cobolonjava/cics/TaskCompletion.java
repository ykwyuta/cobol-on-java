package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.Optional;

/** RETURNをtask coordinatorへ伝える正常な制御終了。 */
public record TaskCompletion(Optional<TransId> nextTransaction, CicsPayload payload)
        implements CicsControl {

    public TaskCompletion {
        Objects.requireNonNull(nextTransaction, "nextTransaction");
        Objects.requireNonNull(payload, "payload");
    }
}
