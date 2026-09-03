package dev.cobolonjava.runtime.picture;

/** PICTURE 文字列が構文的に妥当でないことを表す。 */
public class PictureSyntaxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PictureSyntaxException(String message) {
        super(message);
    }
}
