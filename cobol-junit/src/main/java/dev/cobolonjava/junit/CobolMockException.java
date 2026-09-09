package dev.cobolonjava.junit;

/** Mock本体のchecked exceptionを原因付きで伝える。 */
public final class CobolMockException extends RuntimeException {

    public CobolMockException(String message, Throwable cause) {
        super(message, cause);
    }
}
