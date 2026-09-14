package dev.cobolonjava.cics;

import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 疑似会話をまたいで保存できるdataだけからなる不変envelope。
 *
 * @param screen 直前のtaskが送ったmap画面。次のtaskのRECEIVE MAPが入力と照合する。
 *               HTMLやDOMではなく中立なsnapshotを保存する (ADR-0010)
 */
public record ConversationEnvelope(
        ConversationId id,
        long version,
        String owner,
        TransId nextTransaction,
        CicsPayload payload,
        Instant expiresAt,
        IdempotencyKey idempotencyKey,
        Optional<String> lastOutcome,
        Optional<BmsScreenSnapshot> screen) {

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
        Objects.requireNonNull(screen, "screen");
        lastOutcome = lastOutcome.map(value -> requireText(value, "lastOutcome", 256));
    }

    public ConversationEnvelope(
            ConversationId id, long version, String owner, TransId nextTransaction,
            CicsPayload payload, Instant expiresAt, IdempotencyKey idempotencyKey,
            Optional<String> lastOutcome) {
        this(id, version, owner, nextTransaction, payload, expiresAt, idempotencyKey,
                lastOutcome, Optional.empty());
    }

    /** 次の版。画面は引き継がず、送った task が {@link #withScreen} で置く。 */
    public ConversationEnvelope next(
            TransId transaction, CicsPayload nextPayload, Instant nextExpiry,
            IdempotencyKey nextIdempotencyKey, Optional<String> outcome) {
        if (version == Long.MAX_VALUE) {
            throw new IllegalStateException("conversation version is exhausted");
        }
        return new ConversationEnvelope(id, version + 1, owner, transaction, nextPayload,
                nextExpiry, nextIdempotencyKey, outcome, Optional.empty());
    }

    /** 同じ版に画面を置いた envelope。 */
    public ConversationEnvelope withScreen(Optional<BmsScreenSnapshot> value) {
        return new ConversationEnvelope(id, version, owner, nextTransaction, payload, expiresAt,
                idempotencyKey, lastOutcome, value);
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
