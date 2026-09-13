package dev.cobolonjava.junit;

/** テスト対象 COBOL を準備できなかった場合。 */
public final class CobolCompilationException extends RuntimeException {

    public CobolCompilationException(String message) {
        super(message);
    }

    public CobolCompilationException(String message, Throwable cause) {
        super(message, cause);
    }
}
