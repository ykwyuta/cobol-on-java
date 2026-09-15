package dev.cobolonjava.cics;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 1 つの JVM の中の冪等キーの結果 (暫定判断 P-142)。 */
final class InMemoryCicsOutcomeStore implements CicsOutcomeStorePort {

    private record Key(String owner, String idempotencyKey) {
    }

    /** reply が null なら予約中。 */
    private record Entry(String fingerprint, CicsTaskReply reply, Instant until) {
    }

    /** 期限の切れた結果を掃く間隔 (予約の回数)。掃かなければ結果が溜まり続ける。 */
    private static final int SWEEP_INTERVAL = 256;

    private final Map<Key, Entry> entries = new HashMap<>();
    private int reservations;

    @Override
    public synchronized Reservation reserve(String owner, IdempotencyKey key, String fingerprint,
                                            Duration inProgressFor, Instant now) {
        Key id = new Key(Objects.requireNonNull(owner, "owner"), key.value());
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (++reservations % SWEEP_INTERVAL == 0) {
            entries.values().removeIf(entry -> !entry.until().isAfter(now));
        }
        Entry current = entries.get(id);
        if (current != null && !current.until().isAfter(now)) {
            entries.remove(id);
            current = null;
        }
        if (current == null) {
            entries.put(id, new Entry(fingerprint, null, now.plus(inProgressFor)));
            return new Reservation(Status.RESERVED, Optional.empty());
        }
        if (!current.fingerprint().equals(fingerprint)) {
            return new Reservation(Status.MISMATCH, Optional.empty());
        }
        return current.reply() == null
                ? new Reservation(Status.IN_PROGRESS, Optional.empty())
                : new Reservation(Status.REPLAY, Optional.of(current.reply()));
    }

    @Override
    public synchronized void record(String owner, IdempotencyKey key, CicsTaskReply reply, Instant retainUntil,
                                    Instant now) {
        Key id = new Key(owner, key.value());
        Entry current = entries.get(id);
        if (current == null || current.reply() != null) {
            throw new CicsTaskCommitException("idempotency key is not reserved: " + key.value(),
                    CommitFailureState.NOT_COMMITTED, null);
        }
        entries.put(id, new Entry(current.fingerprint(), Objects.requireNonNull(reply, "reply"), retainUntil));
    }

    @Override
    public synchronized void release(String owner, IdempotencyKey key, Instant now) {
        Key id = new Key(owner, key.value());
        Entry current = entries.get(id);
        if (current != null && current.reply() == null) {
            entries.remove(id);
        }
    }
}
