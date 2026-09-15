package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsTerminalRegistryPort;
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.ConversationLeaseToken;
import dev.cobolonjava.cics.TransId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 端末の登録を {@code COBOL_TERMINAL} の表に置き、複数の JVM で分け合う (設計 83 §4・§8、暫定判断 P-144)。
 *
 * <p>どの操作も他の要求から直ちに見える必要があるので、別の transaction (REQUIRES_NEW) で確定する。lease と
 * 会話の書き換えは条件つきの 1 つの UPDATE で行い、どの JVM から来ても 1 つだけが通る。表は
 * {@link JdbcConversationStore#SCHEMA} の DDL で作る。時刻の列はミリ秒である。
 */
public final class JdbcTerminalRegistry implements CicsTerminalRegistryPort {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate separate;

    public JdbcTerminalRegistry(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.separate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public String register(String owner, Instant expiresAt, Instant now) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("terminal owner must not be blank");
        }
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("a terminal must expire in the future");
        }
        for (int attempt = 0; attempt < REGISTER_ATTEMPTS; attempt++) {
            String id = CicsTerminalRegistryPort.randomTerminalId();
            try {
                separate.executeWithoutResult(status -> {
                    // 期限の過ぎた、task の動いていない端末の名前は使い直す
                    jdbc.update("DELETE FROM COBOL_TERMINAL WHERE TERMINAL_ID = ? AND EXPIRES_AT <= ? AND LEASED_UNTIL <= ?",
                            id, millis(now), millis(now));
                    jdbc.update("INSERT INTO COBOL_TERMINAL (TERMINAL_ID, OWNER_NAME, EXPIRES_AT, LEASE_TOKEN, LEASED_UNTIL,"
                                    + " CONVERSATION_ID, CONVERSATION_VERSION, NEXT_TRANSID) VALUES (?, ?, ?, NULL, 0, NULL, NULL, NULL)",
                            id, owner, millis(expiresAt));
                });
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
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("terminal owner must not be blank");
        }
        if (!expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("a terminal must expire in the future");
        }
        try {
            separate.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM COBOL_TERMINAL WHERE TERMINAL_ID = ? AND EXPIRES_AT <= ? AND LEASED_UNTIL <= ?",
                        terminalId, millis(now), millis(now));
                jdbc.update("INSERT INTO COBOL_TERMINAL (TERMINAL_ID, OWNER_NAME, EXPIRES_AT, LEASE_TOKEN, LEASED_UNTIL,"
                                + " CONVERSATION_ID, CONVERSATION_VERSION, NEXT_TRANSID) VALUES (?, ?, ?, NULL, 0, NULL, NULL, NULL)",
                        terminalId, owner, millis(expiresAt));
            });
            return true;
        } catch (DuplicateKeyException taken) {
            // 期限の過ぎていない端末がある。同じ owner なら期限を延ばしてそれを使う
            return updated(() -> jdbc.update("UPDATE COBOL_TERMINAL SET EXPIRES_AT = CASE WHEN EXPIRES_AT < ? THEN ?"
                            + " ELSE EXPIRES_AT END WHERE TERMINAL_ID = ? AND OWNER_NAME = ? AND EXPIRES_AT > ?",
                    millis(expiresAt), millis(expiresAt), terminalId, owner, millis(now)));
        }
    }

    @Override
    public Optional<Terminal> find(String terminalId, Instant now) {
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(now, "now");
        return jdbc.query("SELECT OWNER_NAME, EXPIRES_AT, LEASE_TOKEN, LEASED_UNTIL, CONVERSATION_ID, CONVERSATION_VERSION,"
                        + " NEXT_TRANSID FROM COBOL_TERMINAL WHERE TERMINAL_ID = ? AND EXPIRES_AT > ?",
                (row, index) -> {
                    String conversationId = row.getString(5);
                    Optional<TerminalConversation> conversation = conversationId == null
                            ? Optional.empty()
                            : Optional.of(new TerminalConversation(new ConversationId(conversationId), row.getLong(6),
                                    TransId.of(row.getString(7).strip())));
                    boolean leased = row.getString(3) != null && row.getLong(4) > millis(now);
                    return new Terminal(terminalId, row.getString(1), Instant.ofEpochMilli(row.getLong(2)),
                            conversation, leased);
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
                        + " NEXT_TRANSID = ? WHERE TERMINAL_ID = ? AND LEASE_TOKEN = ? AND LEASED_UNTIL = ? AND LEASED_UNTIL > ?",
                conversation.map(value -> value.id().value()).orElse(null),
                conversation.map(TerminalConversation::version).orElse(null),
                conversation.map(value -> value.nextTransaction().value()).orElse(null),
                lease.terminalId(), lease.token().value(), millis(lease.leasedUntil()), millis(now)));
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

    private boolean updated(java.util.function.IntSupplier statement) {
        Integer count = separate.execute(status -> statement.getAsInt());
        return count != null && count == 1;
    }

    private static long millis(Instant instant) {
        return instant.toEpochMilli();
    }
}
