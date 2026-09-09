package dev.cobolonjava.junit;

/** Mock に定義していない回数の呼び出しが届いた場合。 */
public final class UnexpectedCobolCallException extends AssertionError {

    public UnexpectedCobolCallException(String message) {
        super(message);
    }
}
