package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** URLやsession属性へ安全に運べる、推測困難な不透明会話ID。 */
public record ConversationId(String value) {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    public ConversationId {
        Objects.requireNonNull(value, "value");
        if (!SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("conversation ID has an unsupported format");
        }
    }

    public static ConversationId create() {
        return new ConversationId(UUID.randomUUID().toString());
    }
}
