package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** claim取得者だけがsave/completeに使える不透明token。 */
public record ConversationLeaseToken(String value) {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    public ConversationLeaseToken {
        Objects.requireNonNull(value, "value");
        if (!SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("lease token has an unsupported format");
        }
    }

    public static ConversationLeaseToken create() {
        return new ConversationLeaseToken(UUID.randomUUID().toString());
    }
}
