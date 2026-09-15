package dev.cobolonjava.cics;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * task の終わりに task 境界が業務の UOW と一緒に確定するもの (暫定判断 P-142)。
 *
 * @param conversation 会話の変更
 * @param outcome      再送に返すために覚える task の結果。冪等キーを覚えない構成では空
 */
public record TaskCommit(ConversationMutation conversation, Optional<RecordedOutcome> outcome) {

    /** 覚える task の結果。 */
    public record RecordedOutcome(String owner, IdempotencyKey key, CicsTaskReply reply, Instant retainUntil) {
        public RecordedOutcome {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(reply, "reply");
            Objects.requireNonNull(retainUntil, "retainUntil");
        }
    }

    public TaskCommit {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(outcome, "outcome");
    }
}
