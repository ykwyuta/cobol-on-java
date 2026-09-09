package dev.cobolonjava.cics;

import java.util.Objects;

/** commit失敗。UNKNOWNでは再実行もlease解放も自動では行わない。 */
public final class CicsTaskCommitException extends RuntimeException {

    private final CommitFailureState state;

    public CicsTaskCommitException(String message, CommitFailureState state, Throwable cause) {
        super(message, cause);
        this.state = Objects.requireNonNull(state, "state");
    }

    public CommitFailureState state() {
        return state;
    }
}
