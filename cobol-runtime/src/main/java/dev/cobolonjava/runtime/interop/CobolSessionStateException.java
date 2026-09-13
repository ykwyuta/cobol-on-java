package dev.cobolonjava.runtime.interop;

/** 閉鎖済み、終了済み、または別スレッド所有のセッションを利用した。 */
public final class CobolSessionStateException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    CobolSessionStateException(String message) {
        super(message);
    }
}
