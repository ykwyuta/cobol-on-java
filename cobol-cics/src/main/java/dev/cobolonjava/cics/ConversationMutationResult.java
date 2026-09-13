package dev.cobolonjava.cics;

public enum ConversationMutationResult {
    CREATED,
    SAVED,
    COMPLETED,
    RELEASED,
    ALREADY_EXISTS,
    NOT_FOUND,
    EXPIRED,
    LEASE_MISMATCH
}
