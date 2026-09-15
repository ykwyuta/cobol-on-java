package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsTerminalRegistryPort;
import dev.cobolonjava.cics.CicsTerminalScreen;
import dev.cobolonjava.cics.ConversationCodec;
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.ConversationLeaseToken;
import dev.cobolonjava.cics.TransId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.IntSupplier;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 端末の登録を {@code COBOL_TERMINAL} の表に置き、複数の JVM で分け合う (設計 83 §4・§7・§8、暫定判断 P-144)。
 *
 * <p>どの操作も他の要求から直ちに見える必要があるので、別の transaction (REQUIRES_NEW) で確定する。lease と
 * 会話・画面の書き換えは条件つきの 1 つの UPDATE で行い、どの JVM から来ても 1 つだけが通る。表は
 * {@link JdbcConversationStore#SCHEMA} の DDL で作る。時刻の列はミリ秒、画面は {@link ConversationCodec} の byte 列である。
 */
public final class JdbcTerminalRegistry implements CicsTerminalRegistryPort {

    private static final String INSERT = "INSERT INTO COBOL_TERMINAL (TERMINAL_ID, OWNER_NAME, EXPIRES_AT, LEASE_TOKEN,"
            + " LEASED_UNTIL, CONVERSATION_ID, CONVERSATION_VERSION, NEXT_TRANSID, SCREEN_VERSION, SCREEN)"
            + " VALUES (?, ?, ?, NULL, 0, NULL, NULL, NULL, 0, NULL)";
    /** lease を持つ要求だけが書き換えられる条件。値は端末、token、lease の期限、今。 */
    private static final String LEASE_CONDITION = " WHERE TERMINAL_ID = ? AND LEASE_TOKEN = ? AND LEASED_UNTIL = ?"
            + " AND LEASED_UNTIL > ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate separate;

    public JdbcTerminalRegistry(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.separate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String register(String owner, Instant expiresAt, Instant now) {
        requireOwner(owner);
        requireFuture(expiresAt, now);
        for (int attempt = 0; attempt < REGISTER_ATTEMPTS; attempt++) {
            String id = CicsTerminalRegistryPort.randomTerminalId();
            try {
                insertReplacingExpired(id, owner, expiresAt, now);
                return id;
            } catch (DuplicateKeyException taken) {
                // 他の端末が使っている。transaction ごと戻して名前を選び直す
            }
        }
        throw new IllegalStateException("no free terminal ID after " + REGISTER_ATTEMPTS + " attempts");
    }

    @Override
    public boolean registerNamed(String terminalId, String owner, Instant expiresAt, Instant now) {
        CicsTerminalRegistryPort.requireTerminalId(terminalId);
        requireOwner(owner);
        requireFuture(expiresAt, now);
        try {
            insertReplacingExpired(terminalId, owner, expiresAt, now);
            return true;
        } catch (DuplicateKeyException taken) {
            // 期限の過ぎていない端末がある。同じ owner なら期限を延ばしてそれを使う
            return updated(() -> jdbc.update("UPDATE COBOL_TERMINAL SET EXPIRES_AT = CASE WHEN EXPIRES_AT < ? THEN ?"
                            + " ELSE EXPIRES_AT END WHERE TERMINAL_ID = ? AND OWNER_NAME = ? AND EXPIRES_AT > ?",
                    millis(expiresAt), millis(expiresAt), terminalId, owner, millis(now)));
        }
    }

    /** 期限の過ぎた、task の動いていない端末の名前は使い直す。 */
    private void insertReplacingExpired(String terminalId, String owner, Instant expiresAt, Instant now) {
        separate.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM COBOL_TERMINAL WHERE TERMINAL_ID = ? AND EXPIRES_AT <= ? AND LEASED_UNTIL <= ?",
                    terminalId, millis(now), millis(now));
            jdbc.update(INSERT, terminalId, owner, millis(expiresAt));
        });
    }

    @Override
    public Optional<Terminal> find(String terminalId, Instant now) {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(now, "now");
        return jdbc.query("SELECT OWNER_NAME, EXPIRES_AT, LEASE_TOKEN, LEASED_UNTIL, CONVERSATION_ID, CONVERSATION_VERSION,"
                        + " NEXT_TRANSID, SCREEN_VERSION FROM COBOL_TERMINAL WHERE TERMINAL_ID = ? AND EXPIRES_AT > ?",
                (row, index) -> {
                    String conversationId = row.getString(5);
                    Optional<TerminalConversation> conversation = conversationId == null
                            ? Optional.empty()
                            : Optional.of(new TerminalConversation(new ConversationId(conversationId), row.getLong(6),
                                    TransId.of(row.getString(7).strip())));
                    boolean leased = row.getString(3) != null && row.getLong(4) > millis(now);
                    return new Terminal(terminalId, row.getString(1), Instant.ofEpochMilli(row.getLong(2)),
                            conversation, leased, row.getLong(8));
                },
                terminalId, millis(now)).stream().findFirst();
    }

    @Override
    public boolean touch(String terminalId, String owner, Instant expiresAt, Instant now) {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");
        return updated(() -> jdbc.update("UPDATE COBOL_TERMINAL SET EXPIRES_AT = ?"
                        + " WHERE TERMINAL_ID = ? AND OWNER_NAME = ? AND EXPIRES_AT > ?",
                millis(expiresAt), terminalId, owner, millis(now)));
    }

    @Override
    public Optional<TerminalLease> lease(String terminalId, String owner, Duration duration, Instant now) {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(owner, "owner");
        Instant until = CicsTerminalRegistryPort.leaseUntil(now, duration);
        ConversationLeaseToken token = ConversationLeaseToken.create();
        boolean acquired = updated(() -> jdbc.update("UPDATE COBOL_TERMINAL SET LEASE_TOKEN = ?, LEASED_UNTIL = ?"
                        + " WHERE TERMINAL_ID = ? AND OWNER_NAME = ? AND EXPIRES_AT > ? AND LEASED_UNTIL <= ?",
                token.value(), millis(until), terminalId, owner, millis(now), millis(now)));
        return acquired ? Optional.of(new TerminalLease(terminalId, token, until)) : Optional.empty();
    }

    @Override
    public boolean setConversation(TerminalLease lease, Optional<TerminalConversation> conversation, Instant now) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(now, "now");
        return updated(() -> jdbc.update("UPDATE COBOL_TERMINAL SET CONVERSATION_ID = ?, CONVERSATION_VERSION = ?,"
                        + " NEXT_TRANSID = ?" + LEASE_CONDITION,
                conversation.map(value -> value.id().value()).orElse(null),
                conversation.map(TerminalConversation::version).orElse(null),
                conversation.map(value -> value.nextTransaction().value()).orElse(null),
                lease.terminalId(), lease.token().value(), millis(lease.leasedUntil()), millis(now)));
    }

    @Override
    public OptionalLong setScreen(TerminalLease lease, CicsTerminalScreen screen, Instant now) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(now, "now");
        byte[] encoded = ConversationCodec.encodeScreen(screen);
        Long version = separate.execute(status -> {
            int updated = jdbc.update("UPDATE COBOL_TERMINAL SET SCREEN = ?, SCREEN_VERSION = SCREEN_VERSION + 1"
                            + LEASE_CONDITION,
                    encoded, lease.terminalId(), lease.token().value(), millis(lease.leasedUntil()), millis(now));
            return updated == 1
                    ? jdbc.queryForObject("SELECT SCREEN_VERSION FROM COBOL_TERMINAL WHERE TERMINAL_ID = ?", Long.class,
                            lease.terminalId())
                    : null;
        });
        return version == null ? OptionalLong.empty() : OptionalLong.of(version);
    }

    @Override
    public Optional<TerminalScreen> screen(String terminalId, Instant now) {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(now, "now");
        return jdbc.query("SELECT SCREEN_VERSION, SCREEN FROM COBOL_TERMINAL"
                        + " WHERE TERMINAL_ID = ? AND EXPIRES_AT > ? AND SCREEN IS NOT NULL",
                (row, index) -> new TerminalScreen(row.getLong(1), ConversationCodec.decodeScreen(row.getBytes(2))),
                terminalId, millis(now)).stream().findFirst();
    }

    @Override
    public boolean release(TerminalLease lease, Instant now) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(now, "now");
        return updated(() -> jdbc.update("UPDATE COBOL_TERMINAL SET LEASE_TOKEN = NULL, LEASED_UNTIL = 0"
                        + " WHERE TERMINAL_ID = ? AND LEASE_TOKEN = ? AND LEASED_UNTIL = ?",
                lease.terminalId(), lease.token().value(), millis(lease.leasedUntil())));
    }

    @Override
    public boolean remove(String terminalId, Instant now) {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(now, "now");
        return updated(() -> jdbc.update("DELETE FROM COBOL_TERMINAL WHERE TERMINAL_ID = ? AND LEASED_UNTIL <= ?",
                terminalId, millis(now)));
    }

    @Override
    public int purgeExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        Integer purged = separate.execute(status -> jdbc.update(
                "DELETE FROM COBOL_TERMINAL WHERE EXPIRES_AT <= ? AND LEASED_UNTIL <= ?", millis(now), millis(now)));
        return purged == null ? 0 : purged;
    }

    private boolean updated(IntSupplier statement) {
        Integer count = separate.execute(status -> statement.getAsInt());
        return count != null && count == 1;
    }

    private static void requireOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("terminal owner must not be blank");
        }
    }

    private static void requireFuture(Instant expiresAt, Instant now) {
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("a terminal must expire in the future");
        }
    }

    private static long millis(Instant instant) {
        return instant.toEpochMilli();
    }
}
