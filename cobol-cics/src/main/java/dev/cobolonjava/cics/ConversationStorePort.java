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
}
