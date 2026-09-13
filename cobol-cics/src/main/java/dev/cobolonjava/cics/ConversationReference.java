package dev.cobolonjava.cics;

import java.util.Objects;

/** browser等が提示する会話IDと期待version。 */
public record ConversationReference(ConversationId id, long expectedVersion) {

    public ConversationReference {
        Objects.requireNonNull(id, "id");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }
}
