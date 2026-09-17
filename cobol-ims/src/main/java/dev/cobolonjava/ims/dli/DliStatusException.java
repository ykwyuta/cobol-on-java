package dev.cobolonjava.ims.dli;

/** 呼び出しを状態コードで打ち切る。PCB の状態欄へ書く値を運ぶだけなので、スタックを取らない。 */
final class DliStatusException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String status;

    DliStatusException(String status) {
        super(status, null, false, false);
        this.status = status;
    }

    String status() {
        return status;
    }
}
