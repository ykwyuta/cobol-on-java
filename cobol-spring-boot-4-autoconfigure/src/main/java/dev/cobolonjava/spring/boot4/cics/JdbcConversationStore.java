package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsOutcomeStorePort;
import dev.cobolonjava.cics.CicsTaskCommitException;
import dev.cobolonjava.cics.CicsTaskReply;
import dev.cobolonjava.cics.CommitFailureState;
import dev.cobolonjava.cics.ConversationClaimResult;
import dev.cobolonjava.cics.ConversationClaimStatus;
import dev.cobolonjava.cics.ConversationCodec;
import dev.cobolonjava.cics.ConversationEnvelope;
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.ConversationLease;
import dev.cobolonjava.cics.ConversationLeaseToken;
import dev.cobolonjava.cics.ConversationMutationResult;
import dev.cobolonjava.cics.ConversationStorePort;
import dev.cobolonjava.cics.IdempotencyKey;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 会話と冪等キーの結果を JDBC の表に置く STRICT の置き場 (設計 77 §4.6、暫定判断 P-143)。
 *
 * <h2>どの操作がどの transaction で確定するか</h2>
 * <p>claim、release、冪等キーの予約と解放は、task を動かす前後に他の要求から見える必要があるので、
 * 別の transaction (REQUIRES_NEW) で直ちに確定する。create、save、complete と結果の記録 ({@link TaskWrites}) は
 * 呼び手の transaction に入る。SPRING_MANAGED の task 境界 ({@link SpringStrictTaskBoundaryFactory}) は {@link #writes()}
 * を Spring の transaction の中で、DB2_DRIVER_MANAGED_HOLD の境界 ({@link DriverManagedStrictTaskBoundaryFactory}) は
 * {@link #writesOn(Connection)} を native lease の connection で呼び、業務の更新と一緒に commit する。
 *
 * <p>表は {@link #SCHEMA} の DDL で作る。自動構成は表を作らない。envelope と応答は {@link ConversationCodec} の
 * byte 列で持ち、検索に使う版・owner・期限・lease だけを列に出す。時刻の列はミリ秒である。
 */
public final class JdbcConversationStore implements ConversationStorePort, CicsOutcomeStorePort {

    /** 表を作る DDL の classpath resource。H2 と Db2 で通る型だけを使う。 */
    public static final String SCHEMA = "dev/cobolonjava/spring/boot4/cics/cobol-conversation-schema.sql";

    /** task の UOW の中で行う更新。commit / rollback は呼び手の UOW が決める。 */
    public interface TaskWrites {

        ConversationMutationResult create(ConversationEnvelope initial, Instant now);

        ConversationMutationResult save(ConversationLease lease, ConversationEnvelope next, Instant now);

        ConversationMutationResult complete(ConversationLease lease, Instant now);

        void record(String owner, IdempotencyKey key, CicsTaskReply reply, Instant retainUntil, Instant now);
    }

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate separate;
    private final Writes spring;

    public JdbcConversationStore(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = new JdbcTemplate(dataSource);
        this.separate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.spring = new Writes(jdbc);
    }

    /** 表を置いた DataSource。STRICT の境界が業務の SQL と同じ DataSource かを確かめる。 */
    public DataSource dataSource() {
        return dataSource;
    }

    /** DataSource の上の更新。Spring の transaction が進行中なら、その connection に入る。 */
    public TaskWrites writes() {
        return spring;
    }

    /**
     * 渡された connection の上の更新。connection は閉じず、autoCommit も commit も触らない。
     *
     * <p>connection は {@link #dataSource()} と同じ database を指していなければならない。claim と予約は DataSource の
     * 別の transaction で確定するので、別の database なら会話が見つからず断られる。同じ database かは JDBC の情報だけでは
     * 確かめきれないので、構成する利用者が保証する。
     */
    public TaskWrites writesOn(Connection connection) {
        JdbcTemplate template = new JdbcTemplate(
                new SingleConnectionDataSource(Objects.requireNonNull(connection, "connection"), true));
        // 重複キーの写像を DataSource の側と揃え、task ごとに database の metadata を引かない
        template.setExceptionTranslator(jdbc.getExceptionTranslator());
        return new Writes(template);
    }

    // ---- 会話 ----

    private record Row(ConversationEnvelope envelope, String leaseToken, long leasedUntil) {
    }

    @Override
    public ConversationMutationResult create(ConversationEnvelope initial, Instant now) {
        return spring.create(initial, now);
    }

    @Override
    public Optional<ConversationEnvelope> load(ConversationId id, Instant now) {
        Objects.requireNonNull(now, "now");
        return row(jdbc, id).map(Row::envelope).filter(envelope -> !envelope.isExpiredAt(now));
    }

    @Override
    public ConversationClaimResult claim(ConversationId id, long expectedVersion, String owner,
                                         Duration leaseDuration, Instant now) {
        Objects.requireNonNull(id, "id");
        String claimedOwner = Objects.requireNonNull(owner, "owner").strip();
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(now, "now");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        // 列はミリ秒なので、lease の期限もミリ秒に揃えて、save / complete のときに列と等しく比べられるようにする
        Instant leasedUntil = Instant.ofEpochMilli(millis(now.plus(leaseDuration)));
        ConversationLeaseToken token = ConversationLeaseToken.create();
        return separate.execute(status -> {
            Optional<ConversationClaimResult> rejected = claimRejection(id, expectedVersion, claimedOwner, now);
            if (rejected.isPresent()) {
                return rejected.orElseThrow();
            }
            int updated = jdbc.update("UPDATE COBOL_CONVERSATION SET LEASE_TOKEN = ?, LEASED_UNTIL = ?"
                            + " WHERE CONVERSATION_ID = ? AND CONVERSATION_VERSION = ? AND LEASED_UNTIL <= ?",
                    token.value(), millis(leasedUntil), id.value(), expectedVersion, millis(now));
            if (updated != 1) {
                // 見てから更新するまでに他の要求が claim したか版を進めた
                return claimRejection(id, expectedVersion, claimedOwner, now)
                        .orElse(ConversationClaimResult.rejected(ConversationClaimStatus.ALREADY_LEASED));
            }
            return ConversationClaimResult.claimed(new ConversationLease(row(jdbc, id).orElseThrow().envelope(), token,
                    leasedUntil));
        });
    }

    /** claim できない理由。claim できるなら空。 */
    private Optional<ConversationClaimResult> claimRejection(ConversationId id, long expectedVersion, String owner,
                                                             Instant now) {
        Optional<Row> row = row(jdbc, id);
        if (row.isEmpty()) {
            return Optional.of(ConversationClaimResult.rejected(ConversationClaimStatus.NOT_FOUND));
        }
        ConversationEnvelope current = row.orElseThrow().envelope();
        if (current.isExpiredAt(now)) {
            jdbc.update("DELETE FROM COBOL_CONVERSATION WHERE CONVERSATION_ID = ?", id.value());
            return Optional.of(ConversationClaimResult.rejected(ConversationClaimStatus.EXPIRED));
        }
        if (!current.owner().equals(owner)) {
            return Optional.of(ConversationClaimResult.rejected(ConversationClaimStatus.OWNER_MISMATCH));
        }
        if (current.version() != expectedVersion) {
            return Optional.of(ConversationClaimResult.rejected(ConversationClaimStatus.VERSION_CONFLICT));
        }
        if (row.orElseThrow().leasedUntil() > millis(now)) {
            return Optional.of(ConversationClaimResult.rejected(ConversationClaimStatus.ALREADY_LEASED));
        }
        return Optional.empty();
    }

    @Override
    public ConversationMutationResult save(ConversationLease lease, ConversationEnvelope next, Instant now) {
        return spring.save(lease, next, now);
    }

    @Override
    public ConversationMutationResult complete(ConversationLease lease, Instant now) {
        return spring.complete(lease, now);
    }

    @Override
    public ConversationMutationResult release(ConversationLease lease, Instant now) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(now, "now");
        return separate.execute(status -> {
            int updated = jdbc.update("UPDATE COBOL_CONVERSATION SET LEASE_TOKEN = NULL, LEASED_UNTIL = 0"
                            + LEASE_CONDITION,
                    lease.envelope().id().value(), lease.envelope().version(), lease.envelope().owner(),
                    lease.token().value(), millis(lease.leasedUntil()), millis(now), millis(now));
            return updated == 1 ? ConversationMutationResult.RELEASED : mismatch(jdbc, lease, now);
        });
    }

    /** lease を持つ要求だけが更新できる条件。値は ID、版、owner、token、lease の期限、今、今。 */
    private static final String LEASE_CONDITION = " WHERE CONVERSATION_ID = ? AND CONVERSATION_VERSION = ?"
            + " AND OWNER_NAME = ? AND LEASE_TOKEN = ? AND LEASED_UNTIL = ? AND LEASED_UNTIL > ? AND EXPIRES_AT > ?";

    private static ConversationMutationResult mismatch(JdbcTemplate template, ConversationLease lease, Instant now) {
        Optional<Row> row = row(template, lease.envelope().id());
        if (row.isEmpty()) {
            return ConversationMutationResult.NOT_FOUND;
        }
        return row.orElseThrow().envelope().isExpiredAt(now)
                ? ConversationMutationResult.EXPIRED : ConversationMutationResult.LEASE_MISMATCH;
    }

    private static Optional<Row> row(JdbcTemplate template, ConversationId id) {
        List<Row> rows = template.query("SELECT ENVELOPE, LEASE_TOKEN, LEASED_UNTIL FROM COBOL_CONVERSATION"
                        + " WHERE CONVERSATION_ID = ?",
                (result, index) -> new Row(ConversationCodec.decodeEnvelope(result.getBytes(1)), result.getString(2),
                        result.getLong(3)),
                Objects.requireNonNull(id, "id").value());
        return rows.stream().findFirst();
    }

    private static void validateSuccessor(ConversationEnvelope expected, ConversationEnvelope next, Instant now) {
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

    /** 1 つの JdbcTemplate の上の task の更新。 */
    private static final class Writes implements TaskWrites {

        private final JdbcTemplate template;

        private Writes(JdbcTemplate template) {
            this.template = template;
        }

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
            try {
                template.update("INSERT INTO COBOL_CONVERSATION (CONVERSATION_ID, CONVERSATION_VERSION, OWNER_NAME,"
                                + " EXPIRES_AT, LEASE_TOKEN, LEASED_UNTIL, ENVELOPE) VALUES (?, ?, ?, ?, NULL, 0, ?)",
                        initial.id().value(), initial.version(), initial.owner(), millis(initial.expiresAt()),
                        ConversationCodec.encodeEnvelope(initial));
                return ConversationMutationResult.CREATED;
            } catch (DuplicateKeyException exists) {
                return ConversationMutationResult.ALREADY_EXISTS;
            }
        }

        @Override
        public ConversationMutationResult save(ConversationLease lease, ConversationEnvelope next, Instant now) {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(next, "next");
            Objects.requireNonNull(now, "now");
            validateSuccessor(lease.envelope(), next, now);
            int updated = template.update("UPDATE COBOL_CONVERSATION SET CONVERSATION_VERSION = ?, EXPIRES_AT = ?,"
                            + " LEASE_TOKEN = NULL, LEASED_UNTIL = 0, ENVELOPE = ?" + LEASE_CONDITION,
                    next.version(), millis(next.expiresAt()), ConversationCodec.encodeEnvelope(next),
                    lease.envelope().id().value(), lease.envelope().version(), lease.envelope().owner(),
                    lease.token().value(), millis(lease.leasedUntil()), millis(now), millis(now));
            return updated == 1 ? ConversationMutationResult.SAVED : mismatch(template, lease, now);
        }

        @Override
        public ConversationMutationResult complete(ConversationLease lease, Instant now) {
            Objects.requireNonNull(lease, "lease");
            Objects.requireNonNull(now, "now");
            int deleted = template.update("DELETE FROM COBOL_CONVERSATION" + LEASE_CONDITION,
                    lease.envelope().id().value(), lease.envelope().version(), lease.envelope().owner(),
                    lease.token().value(), millis(lease.leasedUntil()), millis(now), millis(now));
            return deleted == 1 ? ConversationMutationResult.COMPLETED : mismatch(template, lease, now);
        }

        @Override
        public void record(String owner, IdempotencyKey key, CicsTaskReply reply, Instant retainUntil, Instant now) {
            int updated = template.update("UPDATE COBOL_TASK_OUTCOME SET REPLY = ?, RETAIN_UNTIL = ?"
                            + " WHERE OWNER_NAME = ? AND IDEMPOTENCY_KEY = ? AND REPLY IS NULL",
                    ConversationCodec.encodeReply(reply), millis(retainUntil), owner, key.value());
            if (updated != 1) {
                throw new CicsTaskCommitException("idempotency key is not reserved: " + key.value(),
                        CommitFailureState.NOT_COMMITTED, null);
            }
        }
    }

    // ---- 冪等キーの結果 ----

    private record OutcomeRow(String fingerprint, long retainUntil, byte[] reply) {
    }

    @Override
    public Reservation reserve(String owner, IdempotencyKey key, String fingerprint, Duration inProgressFor,
                               Instant now) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");
        return separate.execute(status -> {
            Optional<OutcomeRow> row = outcome(owner, key);
            if (row.isPresent() && row.orElseThrow().retainUntil() <= millis(now)) {
                jdbc.update("DELETE FROM COBOL_TASK_OUTCOME WHERE OWNER_NAME = ? AND IDEMPOTENCY_KEY = ?",
                        owner, key.value());
                row = Optional.empty();
            }
            if (row.isEmpty()) {
                try {
                    jdbc.update("INSERT INTO COBOL_TASK_OUTCOME (OWNER_NAME, IDEMPOTENCY_KEY, FINGERPRINT, RETAIN_UNTIL,"
                                    + " REPLY) VALUES (?, ?, ?, ?, NULL)",
                            owner, key.value(), fingerprint, millis(now.plus(inProgressFor)));
                    return new Reservation(Status.RESERVED, Optional.empty());
                } catch (DuplicateKeyException raced) {
                    // 他の要求が同じキーを先に予約した。その要求の内容はまだ比べられないので、動いている最中として扱う
                    return new Reservation(Status.IN_PROGRESS, Optional.empty());
                }
            }
            OutcomeRow current = row.orElseThrow();
            if (!current.fingerprint().equals(fingerprint)) {
                return new Reservation(Status.MISMATCH, Optional.empty());
            }
            return current.reply() == null
                    ? new Reservation(Status.IN_PROGRESS, Optional.empty())
                    : new Reservation(Status.REPLAY, Optional.of(ConversationCodec.decodeReply(current.reply())));
        });
    }

    @Override
    public void record(String owner, IdempotencyKey key, CicsTaskReply reply, Instant retainUntil, Instant now) {
        spring.record(owner, key, reply, retainUntil, now);
    }

    @Override
    public void release(String owner, IdempotencyKey key, Instant now) {
        separate.executeWithoutResult(status -> jdbc.update(
                "DELETE FROM COBOL_TASK_OUTCOME WHERE OWNER_NAME = ? AND IDEMPOTENCY_KEY = ? AND REPLY IS NULL",
                owner, key.value()));
    }

    private Optional<OutcomeRow> outcome(String owner, IdempotencyKey key) {
        return jdbc.query("SELECT FINGERPRINT, RETAIN_UNTIL, REPLY FROM COBOL_TASK_OUTCOME"
                        + " WHERE OWNER_NAME = ? AND IDEMPOTENCY_KEY = ?",
                (result, index) -> new OutcomeRow(result.getString(1).strip(), result.getLong(2), result.getBytes(3)),
                owner, key.value()).stream().findFirst();
    }

    /**
     * 期限の切れた会話と結果を消す。定期に呼ぶのは利用者である。lease を持つ会話は残す。
     *
     * @return 消した会話と結果の行の数
     */
    public int purgeExpired(Instant now) {
        Integer purged = separate.execute(status ->
                jdbc.update("DELETE FROM COBOL_CONVERSATION WHERE EXPIRES_AT <= ? AND LEASED_UNTIL <= ?",
                        millis(now), millis(now))
                        + jdbc.update("DELETE FROM COBOL_TASK_OUTCOME WHERE RETAIN_UNTIL <= ?", millis(now)));
        return purged == null ? 0 : purged;
    }

    private static long millis(Instant instant) {
        return instant.toEpochMilli();
    }
}
