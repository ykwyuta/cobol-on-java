package dev.cobolonjava.runtime.program;

/**
 * {@code FILE STATUS} を書いていないファイルで異常が起きた (要件 FR-104)。
 *
 * <p>黙って続けると<b>読めていないデータで処理が進む</b>。参照実装もメッセージを出して
 * 異常終了する。
 */
public final class FileOperationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public FileOperationException(String name, String status) {
        super("file " + name + " failed with status " + status);
    }
}
