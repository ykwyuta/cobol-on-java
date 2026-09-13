package dev.cobolonjava.junit;

/** fixture のライフサイクルに反する操作を検出した場合。 */
public final class IllegalTestStateException extends IllegalStateException {

    public IllegalTestStateException(String message) {
        super(message);
    }
}
