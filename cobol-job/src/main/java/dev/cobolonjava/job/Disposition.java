package dev.cobolonjava.job;

import java.util.Locale;

/**
 * データセットの処置 (要件 FR-131)。
 *
 * <p>JCL の {@code DISP=} の 1 つ目の副パラメタである。<b>いま実行機構は見ていない</b>。
 * 開き方を決めるのはプログラムの {@code OPEN} であり、そこへ処置を割り込ませる形を
 * まだ決めていない (暫定判断 P-045)。読み取った内容は捨てずにここへ入れてある。
 */
public enum Disposition {

    /** 新しく作る。 */
    NEW,

    /** あるものを占有して使う。 */
    OLD,

    /** あるものを共有して使う。既定である。 */
    SHR,

    /** あるものの末尾へ足す。 */
    MOD;

    /** JCL の綴りから読む。知らない綴りは {@code null} を返す。 */
    public static Disposition of(String text) {
        return switch (text.trim().toUpperCase(Locale.ROOT)) {
            case "NEW" -> NEW;
            case "OLD" -> OLD;
            case "SHR" -> SHR;
            case "MOD" -> MOD;
            default -> null;
        };
    }
}
