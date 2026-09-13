package dev.cobolonjava.cics;

import java.time.Duration;
import java.util.Objects;

/** 疑似会話の期限と排他claim期間。 */
public record CicsTaskPolicy(Duration conversationTtl, Duration leaseDuration) {

    public CicsTaskPolicy {
        requirePositive(conversationTtl, "conversationTtl");
        requirePositive(leaseDuration, "leaseDuration");
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
