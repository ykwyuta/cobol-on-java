package dev.cobolonjava.cics;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 疑似会話をまたいで保存できるdataだけからなる不変envelope。 */
public record ConversationEnvelope(
        ConversationId id,
        long version,
        String owner,
        TransId nextTransaction,
        CicsPayload payload,
        Instant expiresAt,
        IdempotencyKey idempotencyKey,
        Optional<String> lastOutcome) {

    public ConversationEnvelope {
        Objects.requireNonNull(id, "id");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        owner = requireText(owner, "owner", 256);
        Objects.requireNonNull(nextTransaction, "nextTransaction");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(lastOutcome, "lastOutcome");
        lastOutcome = lastOutcome.map(value -> requireText(value, "lastOutcome", 256));
    }

    public ConversationEnvelope next(
            TransId transaction, CicsPayload nextPayload, Instant nextExpiry,
            IdempotencyKey nextIdempotencyKey, Optional<String> outcome) {
        if (version == Long.MAX_VALUE) {
            throw new IllegalStateException("conversation version is exhausted");
        }
        return new ConversationEnvelope(id, version + 1, owner, transaction, nextPayload,
                nextExpiry, nextIdempotencyKey, outcome);
    }

    public boolean isExpiredAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !expiresAt.isAfter(instant);
    }

    private static String requireText(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        value = value.strip();
        if (value.isEmpty() || value.length() > maxLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " has an unsupported format");
        }
        return value;
    }
}
