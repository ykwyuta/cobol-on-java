package dev.cobolonjava.ims.store;

/** 置き場を読めない、または確定できない。 */
public final class DatabaseStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DatabaseStoreException(String message) {
        super(message);
    }

    public DatabaseStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
