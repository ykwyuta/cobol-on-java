package dev.cobolonjava.cics;

import java.time.Duration;
import java.util.Objects;

/**
 * {@code DELAY} の待ちを実行するポート (設計 79 §7)。
 *
 * <p>待ちの長さの検査と task 期限との比較は呼ぶ側 ({@link CicsRuntimeOps}) が済ませてある。
 * ここは待つことだけを担う。試験では時計を進めるだけの実装に替える。
 */
@FunctionalInterface
public interface CicsIntervalPort {

    /** 指定の長さだけ待つ。割り込まれたら task を失敗させる。 */
    void delay(Duration duration);

    /**
     * 同期 thread を止めて待つ標準実装。
     *
     * <p>割り込みは task の取消しとして扱い、割り込み状態を戻したうえで失敗させる。
     * 待ちを途中で切り上げて正常終了にすると、遅延を前提にした処理が早く進む。
     */
    static CicsIntervalPort sleeping() {
        return duration -> {
            Objects.requireNonNull(duration, "duration");
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CicsTaskStateException("DELAY was interrupted");
            }
        };
    }
}
