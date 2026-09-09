package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.Optional;

/** transport検証とBMS decodeを完了した後の中立task入力。 */
public record CicsTaskRequest(
        String transactionId,
        String owner,
        CicsPayload payload,
        Optional<ConversationReference> conversation,
        IdempotencyKey idempotencyKey) {

    public CicsTaskRequest {
        Objects.requireNonNull(transactionId, "transactionId");
        owner = requireOwner(owner);
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    private static String requireOwner(String value) {
        Objects.requireNonNull(value, "owner");
        value = value.strip();
        if (value.isEmpty() || value.length() > 256
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("owner has an unsupported format");
        }
        return value;
    }
}
