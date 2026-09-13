package dev.cobolonjava.cics;

import java.util.Objects;

/** task起動前に検出した会話のversion、owner、lease競合。 */
public final class ConversationConflictException extends IllegalStateException {

    private final ConversationClaimStatus status;

    public ConversationConflictException(ConversationClaimStatus status) {
        super("conversation cannot be claimed: " + safeMessage(status));
        this.status = Objects.requireNonNull(status, "status");
    }

    public ConversationClaimStatus status() {
        return status;
    }

    private static String safeMessage(ConversationClaimStatus status) {
        Objects.requireNonNull(status, "status");
        return status == ConversationClaimStatus.OWNER_MISMATCH
                ? ConversationClaimStatus.NOT_FOUND.name()
                : status.name();
    }
}
