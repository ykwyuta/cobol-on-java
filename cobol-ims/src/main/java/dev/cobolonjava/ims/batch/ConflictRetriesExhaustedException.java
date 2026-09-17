package dev.cobolonjava.ims.batch;

import dev.cobolonjava.runtime.abend.AbendCause;
import dev.cobolonjava.runtime.abend.AbendCode;

/**
 * ほかの領域との競合で、領域の再試行を使い切った (暫定判断 P-168、P-107)。
 *
 * <p>実機の IMS はロック待ちやデッドロックで領域を再スケジュールし、尽きれば擬似 ABEND で落とす。
 * ここも同じく異常終了として扱い、{@link AbendCode#U0777} を名乗る。綴りが実機の規定と一致するかは
 * 確かめていない (P-107)。
 */
public final class ConflictRetriesExhaustedException extends RuntimeException implements AbendCause {

    private static final long serialVersionUID = 1L;

    ConflictRetriesExhaustedException(int retries, Throwable lastConflict) {
        super("another region kept winning the conflict; this region gave up after " + retries
                + " retries (set " + ConflictRetry.LIMIT + " to change)", lastConflict);
    }

    @Override
    public AbendCode abendCode() {
        return AbendCode.U0777;
    }
}
