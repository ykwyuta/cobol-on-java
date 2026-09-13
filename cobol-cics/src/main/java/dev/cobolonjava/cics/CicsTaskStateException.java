package dev.cobolonjava.cics;

/** CICS task/sessionの所有境界または制御契約違反。 */
public final class CicsTaskStateException extends IllegalStateException {

    public CicsTaskStateException(String message) {
        super(message);
    }
}
