package dev.cobolonjava.cics;

import java.time.Instant;
import java.util.Objects;

/** version付きenvelopeに対する期限付き排他claim。 */
public record ConversationLease(
        ConversationEnvelope envelope,
        ConversationLeaseToken token,
        Instant leasedUntil) {

    public ConversationLease {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(leasedUntil, "leasedUntil");
    }
}
