package dev.cobolonjava.cics;

public enum ConversationClaimStatus {
    CLAIMED,
    NOT_FOUND,
    EXPIRED,
    VERSION_CONFLICT,
    OWNER_MISMATCH,
    ALREADY_LEASED
}
