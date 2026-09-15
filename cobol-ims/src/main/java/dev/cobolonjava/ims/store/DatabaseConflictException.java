package dev.cobolonjava.ims.store;

/**
 * ほかの領域が、この領域の読んだあとに同じ根を確定していた (ADR-0015、暫定判断 P-161)。
 *
 * <p>黙って上書きすれば、先に確定した更新が失われる。確定をやめて知らせ、呼ぶ側は最後の同期点まで戻す。
 * 実機ならロック待ちかデッドロックで再スケジュールされるところだが、自動の再試行はまだ持たない (P-107)。
 */
public final class DatabaseConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DatabaseConflictException(String message) {
        super(message);
    }
}
