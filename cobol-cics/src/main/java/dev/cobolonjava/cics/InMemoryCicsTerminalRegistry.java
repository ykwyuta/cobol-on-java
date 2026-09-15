package dev.cobolonjava.cics;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** 1 つの JVM の中の端末の登録 (設計 83 §4)。複数の JVM では {@code JdbcTerminalRegistry} を使う。 */
final class InMemoryCicsTerminalRegistry implements CicsTerminalRegistryPort {

    private record Entry(String owner, Instant expiresAt, Optional<TerminalConversation> conversation,
                         ConversationLeaseToken token, Instant leasedUntil, long screenVersion,
                         CicsTerminalScreen screen) {

        static Entry registered(String owner, Instant expiresAt) {
            return new Entry(owner, expiresAt, Optional.empty(), null, Instant.EPOCH, 0, null);
        }

        boolean expiredAt(Instant now) {
            return !expiresAt.isAfter(now);
        }

        boolean leasedAt(Instant now) {
            return token != null && leasedUntil.isAfter(now);
        }

        boolean holds(TerminalLease lease) {
            return token != null && token.equals(lease.token()) && leasedUntil.equals(lease.leasedUntil());
        }

        Entry withExpiry(Instant value) {
            return new Entry(owner, value, conversation, token, leasedUntil, screenVersion, screen);
        }

        Entry withLease(ConversationLeaseToken value, Instant until) {
            return new Entry(owner, expiresAt, conversation, value, until, screenVersion, screen);
        }

        Entry withConversation(Optional<TerminalConversation> value) {
            return new Entry(owner, expiresAt, value, token, leasedUntil, screenVersion, screen);
        }

        Entry withScreen(CicsTerminalScreen value) {
            return new Entry(owner, expiresAt, conversation, token, leasedUntil, screenVersion + 1, value);
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public String register(String owner, Instant expiresAt, Instant now) {
        String required = requireOwner(owner);
        requireFuture(expiresAt, now);
        for (int attempt = 0; attempt < REGISTER_ATTEMPTS; attempt++) {
            String id = CicsTerminalRegistryPort.randomTerminalId();
            AtomicBoolean registered = new AtomicBoolean();
            entries.compute(id, (ignored, current) -> {
                if (current == null || (current.expiredAt(now) && !current.leasedAt(now))) {
                    registered.set(true);
                    return Entry.registered(required, expiresAt);
                }
                return current;
            });
            if (registered.get()) {
                return id;
            }
        }
        throw new IllegalStateException("no free terminal ID after " + REGISTER_ATTEMPTS + " attempts");
    }

    @Override
    public boolean registerNamed(String terminalId, String owner, Instant expiresAt, Instant now) {
        CicsTerminalRegistryPort.requireTerminalId(terminalId);
        String required = requireOwner(owner);
        requireFuture(expiresAt, now);
        AtomicBoolean registered = new AtomicBoolean();
        entries.compute(terminalId, (ignored, current) -> {
            if (current == null || (current.expiredAt(now) && !current.leasedAt(now))) {
                registered.set(true);
                return Entry.registered(required, expiresAt);
            }
            if (!current.expiredAt(now) && current.owner().equals(required)) {
                registered.set(true);
                return current.withExpiry(expiresAt.isAfter(current.expiresAt()) ? expiresAt : current.expiresAt());
            }
            return current;
        });
        return registered.get();
    }

    @Override
    public Optional<Terminal> find(String terminalId, Instant now) {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(now, "now");
        Entry entry = entries.get(terminalId);
        if (entry == null || entry.expiredAt(now)) {
            return Optional.empty();
        }
        return Optional.of(new Terminal(terminalId, entry.owner(), entry.expiresAt(), entry.conversation(),
                entry.leasedAt(now), entry.screenVersion()));
    }

    @Override
    public boolean touch(String terminalId, String owner, Instant expiresAt, Instant now) {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");
        AtomicBoolean touched = new AtomicBoolean();
        entries.computeIfPresent(terminalId, (ignored, current) -> {
            if (current.expiredAt(now) || !current.owner().equals(owner)) {
                return current;
            }
            touched.set(true);
            return current.withExpiry(expiresAt);
        });
        return touched.get();
    }

    @Override
    public Optional<TerminalLease> lease(String terminalId, String owner, Duration duration, Instant now) {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(owner, "owner");
        Instant until = CicsTerminalRegistryPort.leaseUntil(now, duration);
        AtomicReference<TerminalLease> acquired = new AtomicReference<>();
        entries.computeIfPresent(terminalId, (ignored, current) -> {
            if (current.expiredAt(now) || !current.owner().equals(owner) || current.leasedAt(now)) {
                return current;
            }
            ConversationLeaseToken token = ConversationLeaseToken.create();
            acquired.set(new TerminalLease(terminalId, token, until));
            return current.withLease(token, until);
        });
        return Optional.ofNullable(acquired.get());
    }

    @Override
    public boolean setConversation(TerminalLease lease, Optional<TerminalConversation> conversation, Instant now) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(now, "now");
        AtomicBoolean changed = new AtomicBoolean();
        entries.computeIfPresent(lease.terminalId(), (ignored, current) -> {
            if (!current.holds(lease) || !current.leasedUntil().isAfter(now)) {
                return current;
            }
            changed.set(true);
            return current.withConversation(conversation);
        });
        return changed.get();
    }

    @Override
    public OptionalLong setScreen(TerminalLease lease, CicsTerminalScreen screen, Instant now) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(now, "now");
        AtomicLong version = new AtomicLong(-1);
        entries.computeIfPresent(lease.terminalId(), (ignored, current) -> {
            if (!current.holds(lease) || !current.leasedUntil().isAfter(now)) {
                return current;
            }
            Entry next = current.withScreen(screen);
            version.set(next.screenVersion());
            return next;
        });
        return version.get() < 0 ? OptionalLong.empty() : OptionalLong.of(version.get());
    }

    @Override
    public Optional<TerminalScreen> screen(String terminalId, Instant now) {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(now, "now");
        Entry entry = entries.get(terminalId);
        if (entry == null || entry.expiredAt(now) || entry.screen() == null) {
            return Optional.empty();
        }
        return Optional.of(new TerminalScreen(entry.screenVersion(), entry.screen()));
    }

    @Override
    public boolean release(TerminalLease lease, Instant now) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(now, "now");
        AtomicBoolean released = new AtomicBoolean();
        entries.computeIfPresent(lease.terminalId(), (ignored, current) -> {
            if (!current.holds(lease)) {
                return current;
            }
            released.set(true);
            return current.withLease(null, Instant.EPOCH);
        });
        return released.get();
    }

    @Override
    public boolean remove(String terminalId, Instant now) {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(now, "now");
        AtomicBoolean removed = new AtomicBoolean();
        entries.computeIfPresent(terminalId, (ignored, current) -> {
            if (current.leasedAt(now)) {
                return current;
            }
            removed.set(true);
            return null;
        });
        return removed.get();
    }

    @Override
    public int purgeExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        int before = entries.size();
        entries.values().removeIf(entry -> entry.expiredAt(now) && !entry.leasedAt(now));
        return Math.max(0, before - entries.size());
    }

    private static String requireOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("terminal owner must not be blank");
        }
        return owner;
    }

    private static void requireFuture(Instant expiresAt, Instant now) {
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("a terminal must expire in the future");
        }
    }
}
