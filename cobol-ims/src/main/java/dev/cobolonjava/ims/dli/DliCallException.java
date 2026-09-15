package dev.cobolonjava.ims.dli;

/**
 * DL/I の呼び出しを動かせない。
 *
 * <p>状態コードで知らせる誤り (見つからない、PROCOPT が許さない等) とは分ける。ここに来るのは、
 * 対応していない形 (コマンドコード等) と、実機なら記憶域を壊すか異常終了になる渡し方 (I/O 域が短い、
 * PCB でないものを PCB の位置に渡した) である。近い結果を黙って返すより、止めて知らせる。
 */
public final class DliCallException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DliCallException(String message) {
        super(message);
    }
}
