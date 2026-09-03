package dev.cobolonjava.compiler.source;

/** ソースの形式が妥当でないことを表す。 */
public class SourceFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SourceFormatException(String message) {
        super(message);
    }
}
