package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.Optional;

public record ConversationClaimResult(
        ConversationClaimStatus status,
        Optional<ConversationLease> lease) {

    public ConversationClaimResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(lease, "lease");
        if ((status == ConversationClaimStatus.CLAIMED) != lease.isPresent()) {
            throw new IllegalArgumentException("only CLAIMED may contain a lease");
        }
    }

    public static ConversationClaimResult claimed(ConversationLease lease) {
        return new ConversationClaimResult(
                ConversationClaimStatus.CLAIMED, Optional.of(Objects.requireNonNull(lease, "lease")));
    }

    public static ConversationClaimResult rejected(ConversationClaimStatus status) {
        if (status == ConversationClaimStatus.CLAIMED) {
            throw new IllegalArgumentException("CLAIMED requires a lease");
        }
        return new ConversationClaimResult(status, Optional.empty());
    }
}
