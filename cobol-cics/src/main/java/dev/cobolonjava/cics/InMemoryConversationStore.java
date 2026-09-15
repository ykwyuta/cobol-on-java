package dev.cobolonjava.cics;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** 単一JVM向けreference実装。分散環境ではadapter契約試験用にだけ使う。 */
public final class InMemoryConversationStore implements ConversationStorePort {

    private final ConcurrentHashMap<ConversationId, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public ConversationMutationResult create(ConversationEnvelope initial, Instant now) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(now, "now");
        if (initial.version() != 0) {
            throw new IllegalArgumentException("an initial conversation must have version zero");
        }
        if (initial.isExpiredAt(now)) {
            throw new IllegalArgumentException("an initial conversation must expire in the future");
        }
        Entry added = entries.putIfAbsent(initial.id(), Entry.available(initial));
        return added == null
                ? ConversationMutationResult.CREATED
                : ConversationMutationResult.ALREADY_EXISTS;
    }

    @Override
    public Optional<ConversationEnvelope> load(ConversationId id, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");
        AtomicReference<ConversationEnvelope> found = new AtomicReference<>();
        entries.computeIfPresent(id, (ignored, current) -> {
            if (current.envelope.isExpiredAt(now)) {
                return null;
            }
            found.set(current.envelope);
            return current;
        });
        return Optional.ofNullable(found.get());
    }

    @Override
    public ConversationClaimResult claim(
            ConversationId id, long expectedVersion, String owner,
            Duration leaseDuration, Instant now) {
        Objects.requireNonNull(id, "id");
        owner = requireOwner(owner);
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(now, "now");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        Instant leasedUntil;
        try {
            leasedUntil = now.plus(leaseDuration);
        } catch (RuntimeException overflow) {
            throw new IllegalArgumentException("lease duration is out of range", overflow);
        }

        AtomicReference<ConversationClaimStatus> rejected = new AtomicReference<>(
                ConversationClaimStatus.NOT_FOUND);
        AtomicReference<ConversationLease> acquired = new AtomicReference<>();
        String claimedOwner = owner;
        entries.compute(id, (ignored, current) -> {
            if (current == null) {
                rejected.set(ConversationClaimStatus.NOT_FOUND);
                return null;
            }
            if (current.envelope.isExpiredAt(now)) {
                rejected.set(ConversationClaimStatus.EXPIRED);
                return null;
            }
            if (!current.envelope.owner().equals(claimedOwner)) {
                rejected.set(ConversationClaimStatus.OWNER_MISMATCH);
                return current;
            }
            if (current.envelope.version() != expectedVersion) {
                rejected.set(ConversationClaimStatus.VERSION_CONFLICT);
                return current;
            }
            if (current.hasActiveLeaseAt(now)) {
                rejected.set(ConversationClaimStatus.ALREADY_LEASED);
                return current;
            }
            ConversationLeaseToken token = ConversationLeaseToken.create();
            ConversationLease lease = new ConversationLease(current.envelope, token, leasedUntil);
            acquired.set(lease);
            return new Entry(current.envelope, token, leasedUntil);
        });
        return acquired.get() == null
                ? ConversationClaimResult.rejected(rejected.get())
                : ConversationClaimResult.claimed(acquired.get());
    }

    @Override
    public ConversationMutationResult save(
            ConversationLease lease, ConversationEnvelope next, Instant now) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(now, "now");
        validateSuccessor(lease.envelope(), next, now);
        AtomicReference<ConversationMutationResult> result = new AtomicReference<>(
                ConversationMutationResult.NOT_FOUND);
        entries.compute(lease.envelope().id(), (ignored, current) -> {
            ConversationMutationResult state = validateLease(current, lease, now);
            if (state != null) {
                result.set(state);
                return state == ConversationMutationResult.EXPIRED ? null : current;
            }
            result.set(ConversationMutationResult.SAVED);
            return Entry.available(next);
        });
        return result.get();
    }

    @Override
    public ConversationMutationResult complete(ConversationLease lease, Instant now) {
        return removeWithLease(lease, now, ConversationMutationResult.COMPLETED);
    }

    @Override
    public ConversationMutationResult release(ConversationLease lease, Instant now) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(now, "now");
        AtomicReference<ConversationMutationResult> result = new AtomicReference<>(
                ConversationMutationResult.NOT_FOUND);
        entries.compute(lease.envelope().id(), (ignored, current) -> {
            ConversationMutationResult state = validateLease(current, lease, now);
            if (state != null) {
                result.set(state);
                return state == ConversationMutationResult.EXPIRED ? null : current;
            }
            result.set(ConversationMutationResult.RELEASED);
            return Entry.available(current.envelope);
        });
        return result.get();
    }

    @Override
    public ConversationMutationResult discard(ConversationId id, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(now, "now");
        AtomicReference<ConversationMutationResult> result = new AtomicReference<>(
                ConversationMutationResult.NOT_FOUND);
        entries.computeIfPresent(id, (ignored, current) -> {
            if (current.envelope.isExpiredAt(now)) {
                result.set(ConversationMutationResult.EXPIRED);
                return null;
            }
            if (current.hasActiveLeaseAt(now)) {
                result.set(ConversationMutationResult.LEASE_MISMATCH);
                return current;
            }
            result.set(ConversationMutationResult.COMPLETED);
            return null;
        });
        return result.get();
    }

    private ConversationMutationResult removeWithLease(
            ConversationLease lease, Instant now, ConversationMutationResult success) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(now, "now");
        AtomicReference<ConversationMutationResult> result = new AtomicReference<>(
                ConversationMutationResult.NOT_FOUND);
        entries.compute(lease.envelope().id(), (ignored, current) -> {
            ConversationMutationResult state = validateLease(current, lease, now);
            if (state != null) {
                result.set(state);
                return state == ConversationMutationResult.EXPIRED ? null : current;
            }
            result.set(success);
            return null;
        });
        return result.get();
    }

    private static ConversationMutationResult validateLease(
            Entry current, ConversationLease lease, Instant now) {
        if (current == null) {
            return ConversationMutationResult.NOT_FOUND;
        }
        if (current.envelope.isExpiredAt(now)) {
            return ConversationMutationResult.EXPIRED;
        }
        if (!current.leasedUntil.isAfter(now)) {
            return ConversationMutationResult.LEASE_MISMATCH;
        }
        if (!current.envelope.id().equals(lease.envelope().id())
                || current.envelope.version() != lease.envelope().version()
                || !current.envelope.owner().equals(lease.envelope().owner())
                || !current.token.equals(lease.token())
                || !current.leasedUntil.equals(lease.leasedUntil())) {
            return ConversationMutationResult.LEASE_MISMATCH;
        }
        return null;
    }

    private static void validateSuccessor(
            ConversationEnvelope expected, ConversationEnvelope next, Instant now) {
        if (!next.id().equals(expected.id()) || !next.owner().equals(expected.owner())) {
            throw new IllegalArgumentException("successor must keep conversation ID and owner");
        }
        if (expected.version() == Long.MAX_VALUE || next.version() != expected.version() + 1) {
            throw new IllegalArgumentException("successor version must increment by exactly one");
        }
        if (next.isExpiredAt(now)) {
            throw new IllegalArgumentException("successor must expire in the future");
        }
    }

    private static String requireOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        owner = owner.strip();
        if (owner.isEmpty()) {
            throw new IllegalArgumentException("owner must not be empty");
        }
        return owner;
    }

    private record Entry(
            ConversationEnvelope envelope,
            ConversationLeaseToken token,
            Instant leasedUntil) {

        private Entry {
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(leasedUntil, "leasedUntil");
        }

        static Entry available(ConversationEnvelope envelope) {
            return new Entry(envelope, new ConversationLeaseToken("available00000000"), Instant.MIN);
        }

        boolean hasActiveLeaseAt(Instant now) {
            return leasedUntil.isAfter(now);
        }
    }
}
