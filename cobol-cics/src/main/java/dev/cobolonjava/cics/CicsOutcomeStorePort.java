package dev.cobolonjava.cics;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 冪等キーごとに commit した task の結果を覚え、同じ要求の再送に返す (設計 77 §4.3、暫定判断 P-142)。
 *
 * <p>予約は task を動かす前に取り、他の JVM からも見える必要があるので、task の UOW とは別に確定する。
 * 結果の記録は task 境界の commit の中で行い、業務の更新と一緒に確定する。
 */
public interface CicsOutcomeStorePort {

    /** 予約の結果。 */
    enum Status {
        /** この要求が予約した。task を動かしてよい。 */
        RESERVED,
        /** 同じ要求がもう commit している。覚えた結果を返す。 */
        REPLAY,
        /** 同じ冪等キーの要求がまだ動いている。 */
        IN_PROGRESS,
        /** 同じ冪等キーで違う要求が来た。 */
        MISMATCH
    }

    record Reservation(Status status, Optional<CicsTaskReply> reply) {
        public Reservation {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reply, "reply");
            if ((status == Status.REPLAY) != reply.isPresent()) {
                throw new IllegalArgumentException("only a replay carries a reply");
            }
        }
    }

    /**
     * 冪等キーを予約する。
     *
     * @param fingerprint   要求の内容の要約。同じキーで違えば MISMATCH
     * @param inProgressFor 予約が残る長さ。task が落ちて予約が残っても、この長さのあとは予約し直せる
     */
    Reservation reserve(String owner, IdempotencyKey key, String fingerprint, Duration inProgressFor, Instant now);

    /** commit した task の結果を覚える。retainUntil まで再送に返す。task 境界の commit の中で呼ぶ。 */
    void record(String owner, IdempotencyKey key, CicsTaskReply reply, Instant retainUntil, Instant now);

    /** commit しなかった task の予約を外す。 */
    void release(String owner, IdempotencyKey key, Instant now);

    /** 1 つの JVM の中で覚える。 */
    static CicsOutcomeStorePort inMemory() {
        return new InMemoryCicsOutcomeStore();
    }
}
