package dev.cobolonjava.ims.batch;

/**
 * IMS のバッチ領域を始められない (PARM、PSB / DBD のライブラリ、データベースの DD の誤り)。
 *
 * <p>実機ではここで異常終了する。その異常終了コードを突き合わせていないので、コードは名乗らない (P-155)。
 */
public final class ImsBatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ImsBatchException(String message) {
        super(message);
    }

    public ImsBatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
