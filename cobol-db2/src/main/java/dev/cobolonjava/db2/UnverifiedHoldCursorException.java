package dev.cobolonjava.db2;

/** 明示戦略またはtask-wide native profileなしでWITH HOLDへ到達した。 */
public final class UnverifiedHoldCursorException extends IllegalStateException {

    public UnverifiedHoldCursorException(String message) {
        super(message);
    }
}
