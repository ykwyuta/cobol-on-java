package dev.cobolonjava.db2;

/** task、UOW、SQL executorまたはcursor方針のprofile混在。 */
public final class Db2ProfileMismatchException extends IllegalStateException {

    public Db2ProfileMismatchException(String message) {
        super(message);
    }
}
