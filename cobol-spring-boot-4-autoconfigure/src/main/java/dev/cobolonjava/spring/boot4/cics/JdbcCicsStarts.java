package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsResponseCode;
import dev.cobolonjava.cics.CicsStartData;
import dev.cobolonjava.cics.CicsStartPort;
import dev.cobolonjava.cics.CicsTaskStateException;
import dev.cobolonjava.cics.TransId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.sql.DataSource;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * START を {@code COBOL_START} の表に置き、複数の JVM の dispatcher が満了したものを起こす
 * (設計 83 §8、暫定判断 P-138・P-144)。
 *
 * <p>各 JVM が一定の間隔で満了した行を探し、行を消せた JVM だけが task を起こす。消してから起こすので、どの JVM から見ても
 * attach は高々 1 回である。起こす前に JVM が止まれば START は失われる (回復不能の START が region の停止で失われるのと
 * 同じ側に倒した。2 回動かすことはしない)。
 *
 * <p>この増分では端末の無い START だけを扱う。{@code TERMINAL_ID} の列は START TERMID の増分で使う。
 * REQID を書かない START の名前は {@code JV} と 36 進 6 桁の乱数で、JVM をまたいで通し番号を持たない。
 */
public final class JdbcCicsStarts implements CicsStartPort, SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(JdbcCicsStarts.class.getName());
    private static final Result NORMAL = new Result(CicsResponseCode.NORMAL, 0);
    /** 1 度の周期で探す満了した START の数。残りは次の周期に回す。 */
    static final int BATCH = 64;
    private static final long REQUEST_ID_LIMIT = 2_176_782_336L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate separate;
    private final Clock clock;
    private final Predicate<TransId> defined;
    private final Consumer<CicsStartData> launcher;
    private final Duration pollInterval;
    private final Executor tasks;
    private final ExecutorService ownedTasks;
    private ScheduledExecutorService scheduler;

    /**
     * @param defined      transaction が定義されているか。されていなければ TRANSIDERR
     * @param launcher     満了した START の task を起こす。START を出した task とは別の thread で呼ぶ
     * @param pollInterval 満了した START を探す間隔。起こすまでの遅れはこの間隔までである
     */
    public JdbcCicsStarts(DataSource dataSource, PlatformTransactionManager transactionManager, Clock clock,
                          Predicate<TransId> defined, Consumer<CicsStartData> launcher, Duration pollInterval) {
        this(dataSource, transactionManager, clock, defined, launcher, pollInterval, null);
    }

    /** 試験は task を起こす executor を替え、起こした順を決める。 */
    JdbcCicsStarts(DataSource dataSource, PlatformTransactionManager transactionManager, Clock clock,
                   Predicate<TransId> defined, Consumer<CicsStartData> launcher, Duration pollInterval,
                   Executor tasks) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.separate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defined = Objects.requireNonNull(defined, "defined");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("poll interval must be positive");
        }
        this.ownedTasks = tasks == null ? Executors.newVirtualThreadPerTaskExecutor() : null;
        this.tasks = tasks == null ? ownedTasks : tasks;
    }

    @Override
    public Result check(CicsStartData data) {
        Objects.requireNonNull(data, "data");
        if (!defined.test(data.transaction())) {
            // TRANSIDERR: 起こす transaction が定義されていない
            return new Result(CicsResponseCode.TRANSIDERR, 0);
        }
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM COBOL_START WHERE REQUEST_ID = ?", Integer.class,
                data.requestId());
        return pending != null && pending > 0 ? duplicate(data) : NORMAL;
    }

    @Override
    public Result start(Instant expiration, CicsStartData data) {
        Objects.requireNonNull(expiration, "expiration");
        Result checked = check(data);
        if (checked.response() != CicsResponseCode.NORMAL) {
            return checked;
        }
        try {
            separate.executeWithoutResult(status -> jdbc.update("INSERT INTO COBOL_START (REQUEST_ID, START_TOKEN, TRANSID,"
                            + " TERMINAL_ID, EXPIRES_AT, START_DATA, RTRANSID, RTERMID, QUEUE_NAME, OWNER_NAME, USER_ID)"
                            + " VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?)",
                    data.requestId(), UUID.randomUUID().toString(), data.transaction().value(),
                    expiration.toEpochMilli(), data.data().orElse(null), data.returnTransaction().orElse(null),
                    data.returnTerminal().orElse(null), data.queue().orElse(null), data.owner(),
                    data.userId().orElse(null)));
            return NORMAL;
        } catch (DuplicateKeyException raced) {
            // 確かめてから登録するまでに、別の JVM が同じ REQID を登録した
            return duplicate(data);
        }
    }

    private static Result duplicate(CicsStartData data) {
        if (data.data().isPresent()) {
            // IOERR: FROM を持つ START の REQID が既にある
            return new Result(CicsResponseCode.IOERR, 0);
        }
        throw new CicsTaskStateException("START REQID(" + data.requestId() + ") is already pending;"
                + " the condition for a START without FROM is not documented");
    }

    @Override
    public Result cancel(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        long now = clock.instant().toEpochMilli();
        // 満了したが dispatcher がまだ起こしていない START は、1 つの JVM の中の実装と同じく取り消せない
        Integer deleted = separate.execute(status -> jdbc.update(
                "DELETE FROM COBOL_START WHERE REQUEST_ID = ? AND EXPIRES_AT > ?", requestId, now));
        return deleted != null && deleted == 1 ? NORMAL : new Result(CicsResponseCode.NOTFND, 0);
    }

    @Override
    public String newRequestId() {
        String digits = Long.toString(ThreadLocalRandom.current().nextLong(REQUEST_ID_LIMIT), 36)
                .toUpperCase(Locale.ROOT);
        return "JV" + "0".repeat(6 - digits.length()) + digits;
    }

    private record Due(String token, CicsStartData data) {
    }

    /**
     * 満了した START を探して起こす。
     *
     * @return この呼び出しが起こした START の数
     */
    public int dispatchDue() {
        long now = clock.instant().toEpochMilli();
        List<Due> due = jdbc.query("SELECT REQUEST_ID, START_TOKEN, TRANSID, START_DATA, RTRANSID, RTERMID, QUEUE_NAME,"
                        + " OWNER_NAME, USER_ID FROM COBOL_START WHERE TERMINAL_ID IS NULL AND EXPIRES_AT <= ?"
                        + " ORDER BY EXPIRES_AT FETCH FIRST " + BATCH + " ROWS ONLY",
                (row, index) -> new Due(row.getString(2), new CicsStartData(row.getString(1),
                        TransId.of(row.getString(3).strip()), row.getBytes(4), Optional.ofNullable(row.getString(5)),
                        Optional.ofNullable(row.getString(6)), Optional.ofNullable(row.getString(7)),
                        row.getString(8), Optional.ofNullable(row.getString(9)))),
                now);
        int launched = 0;
        for (Due start : due) {
            // 行を消せた JVM だけが起こす。START_TOKEN で、見てから消すまでに同じ REQID で登録し直された行を消さない
            Integer deleted = separate.execute(status -> jdbc.update(
                    "DELETE FROM COBOL_START WHERE REQUEST_ID = ? AND START_TOKEN = ?",
                    start.data().requestId(), start.token()));
            if (deleted == null || deleted != 1) {
                continue;
            }
            launched++;
            tasks.execute(() -> {
                try {
                    launcher.accept(start.data());
                } catch (RuntimeException failure) {
                    LOG.log(System.Logger.Level.WARNING, "task started by START REQID(" + start.data().requestId()
                            + ") TRANSID(" + start.data().transaction().value() + ") failed", failure);
                }
            });
        }
        return launched;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cics-start-dispatcher");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                dispatchDue();
            } catch (RuntimeException failure) {
                // 表に届かない周期は飛ばし、次の周期で試し直す
                LOG.log(System.Logger.Level.WARNING, "failed to dispatch expired START requests", failure);
            }
        }, pollInterval.toMillis(), pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (ownedTasks != null) {
            ownedTasks.shutdown();
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }
}
