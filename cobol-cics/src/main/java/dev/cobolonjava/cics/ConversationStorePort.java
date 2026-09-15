package dev.cobolonjava.cics;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Spring Session、JDBC表、Redis等へ適合させる疑似会話CAS port。 */
public interface ConversationStorePort {

    ConversationMutationResult create(ConversationEnvelope initial, Instant now);

    Optional<ConversationEnvelope> load(ConversationId id, Instant now);

    ConversationClaimResult claim(
            ConversationId id,
            long expectedVersion,
            String owner,
            Duration leaseDuration,
            Instant now);

    ConversationMutationResult save(
            ConversationLease lease, ConversationEnvelope next, Instant now);

    ConversationMutationResult complete(ConversationLease lease, Instant now);

    ConversationMutationResult release(ConversationLease lease, Instant now);

    /**
     * 会話を持つ HTTP session が消えた (logout・失効) ので、会話を捨てる (設計 77 §4.6、暫定判断 P-143)。
     *
     * <p>lease を持つ会話 (task が動いている最中) は消さずに {@link ConversationMutationResult#LEASE_MISMATCH} を返す。
     * 動いている task の commit を会話の不在で失敗させ、業務の更新まで巻き戻さないためである。残った会話は自分の期限で消える。
     *
     * @return 消せば COMPLETED、無ければ NOT_FOUND、期限切れを消せば EXPIRED、lease があれば LEASE_MISMATCH
     */
    ConversationMutationResult discard(ConversationId id, Instant now);
}
