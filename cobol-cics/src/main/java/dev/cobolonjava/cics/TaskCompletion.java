package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.Optional;

/**
 * RETURNをtask coordinatorへ伝える正常な制御終了。
 *
 * @param immediate 次のtaskを端末入力なしで始める指定
 * @param screen    taskが端末へ最後に送った画面。送っていなければ空
 */
public record TaskCompletion(
        Optional<TransId> nextTransaction,
        CicsPayload payload,
        boolean immediate,
        Optional<CicsTerminalScreen> screen) implements CicsControl {

    public TaskCompletion {
        Objects.requireNonNull(nextTransaction, "nextTransaction");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(screen, "screen");
        if (immediate && nextTransaction.isEmpty()) {
            throw new IllegalArgumentException("an immediate completion requires a next TRANSID");
        }
    }

    public TaskCompletion(Optional<TransId> nextTransaction, CicsPayload payload, boolean immediate) {
        this(nextTransaction, payload, immediate, Optional.empty());
    }

    public TaskCompletion(Optional<TransId> nextTransaction, CicsPayload payload) {
        this(nextTransaction, payload, false);
    }

    /** task programが実行の終わりに画面を添える。 */
    public TaskCompletion withScreen(Optional<CicsTerminalScreen> value) {
        return new TaskCompletion(nextTransaction, payload, immediate, value);
    }
}
