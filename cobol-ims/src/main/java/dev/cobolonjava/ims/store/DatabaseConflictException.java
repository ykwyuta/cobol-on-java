package dev.cobolonjava.ims.store;

/**
 * ほかの領域が、この領域の読んだあとに同じ根を確定していた (ADR-0015、暫定判断 P-161)。
 *
 * <p>黙って上書きすれば、先に確定した更新が失われる。確定をやめて知らせ、呼ぶ側は最後の同期点まで戻す。
 * 実機ならロック待ちかデッドロックで再スケジュールされる。電文駆動の領域は、巻き戻してから頭で動かし直す
 * (既定 3 回、P-168)。使い切れば U0777 で落ちる。バッチ (DLI) はやり直さず、そのまま上げる。
 */
public final class DatabaseConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DatabaseConflictException(String message) {
        super(message);
    }
}
