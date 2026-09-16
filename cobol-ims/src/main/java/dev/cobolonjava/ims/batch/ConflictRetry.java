package dev.cobolonjava.ims.batch;

import dev.cobolonjava.ims.store.DatabaseConflictException;
import java.util.function.Supplier;

/**
 * 競合した領域を、もう一度はじめから動かす (暫定判断 P-168、P-107 の実装)。
 *
 * <p>ほかの領域が先に確定していると、同期点の確定は {@link DatabaseConflictException} で止まる。
 * 実機の IMS はそこで領域を再スケジュールする。ここも同じく、巻き戻してからプログラムを頭から
 * 動かし直す。未確定の電文はキューへ戻っているので、やり直せば同じ電文から処理し直す。
 *
 * <p><b>電文駆動の領域だけ</b>である。バッチ (DLI) を頭から流し直すと、既に確定した同期点の分を
 * 二重に入れてしまうからである。
 */
final class ConflictRetry {

    /** 再試行の回数。実機の規定値ではない (P-107)。 */
    static final String LIMIT = "cobol.ims.conflict-retries";

    private static final int DEFAULT_LIMIT = 3;

    private ConflictRetry() {
    }

    /** 構成された再試行の回数。 */
    static int limit() {
        String written = System.getProperty(LIMIT);
        if (written == null || written.isBlank()) {
            return DEFAULT_LIMIT;
        }
        try {
            int value = Integer.parseInt(written.trim());
            if (value < 0) {
                throw new NumberFormatException(written);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ImsBatchException(LIMIT + " is the number of retries after a conflict, but it is written as "
                    + written);
        }
    }

    /**
     * 競合したらやり直す。
     *
     * @param enabled やり直してよいか (電文駆動の領域なら {@code true})
     * @param attempt 1 回分の実行。呼ばれるたびに、置き場から読み直すところから始めること
     * @throws ConflictRetriesExhaustedException 回数を使い切ったとき (U0777)
     */
    static <T> T run(boolean enabled, Supplier<T> attempt) {
        int limit = enabled ? limit() : 0;
        Throwable last = null;
        for (int tries = 0; tries <= limit; tries++) {
            try {
                return attempt.get();
            } catch (RuntimeException e) {
                DatabaseConflictException conflict = conflictIn(e);
                // 競合でなければ、そのまま上げる。やり直してよい条件ではない
                if (conflict == null || !enabled) {
                    throw e;
                }
                last = conflict;
            }
        }
        throw new ConflictRetriesExhaustedException(limit, last);
    }

    /** ほかの例外に包まれていても競合を見つける。生成したプログラムの中から上がってくるためである。 */
    private static DatabaseConflictException conflictIn(Throwable failure) {
        for (Throwable at = failure; at != null; at = at.getCause()) {
            if (at instanceof DatabaseConflictException conflict) {
                return conflict;
            }
            if (at.getCause() == at) {
                break;
            }
        }
        return null;
    }
}
