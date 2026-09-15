package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsResponseCode;
import dev.cobolonjava.cics.CicsStartData;
import dev.cobolonjava.cics.CicsStartPort;
import dev.cobolonjava.cics.CicsTaskStateException;
import dev.cobolonjava.cics.CicsTerminalTasks;
import dev.cobolonjava.cics.CicsTerminalRegistryPort;
import dev.cobolonjava.cics.CicsTerminalRegistryPort.Terminal;
import dev.cobolonjava.cics.CicsTerminalRegistryPort.TerminalConversation;
import dev.cobolonjava.cics.CicsTerminalRegistryPort.TerminalLease;
import dev.cobolonjava.cics.ConversationEnvelope;
import dev.cobolonjava.cics.TransId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import java.util.function.Function;
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
 * (設計 83 §5・§8、暫定判断 P-138・P-144)。
 *
 * <p>各 JVM が一定の間隔で満了した行を探し、行を消せた JVM だけが task を起こす。消してから起こすので、どの JVM から見ても
 * attach は高々 1 回である。起こす前に JVM が止まれば START は失われる (回復不能の START が region の停止で失われるのと
 * 同じ側に倒した。2 回動かすことはしない)。
 *
 * <h2>端末へ出す START (TERMID)</h2>
 * <ul>
 *   <li>命令の時点で端末が登録に無いか、端末の owner が START を出した task の owner と違えば TERMIDERR</li>
 *   <li>満了しても、端末で task が動いているか疑似会話の途中なら待つ (利用者の決定)。満了時に端末が無ければ捨てる</li>
 *   <li>端末を lease してから、同じ端末と TRANSID の満了した START をまとめて 1 つの task にする。task が返した次の
 *       疑似会話を端末に置き、lease を返す</li>
 *   <li>端末の名前が別の利用者に振り直されていれば (owner が違う)、その START は端末と一緒に捨てる</li>
 * </ul>
 *
 * <p>REQID を書かない START の名前は {@code JV} と 36 進 6 桁の乱数で、JVM をまたいで通し番号を持たない。
 */
public final class JdbcCicsStarts implements CicsStartPort, SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(JdbcCicsStarts.class.getName());
    private static final Result NORMAL = new Result(CicsResponseCode.NORMAL, 0);
    /** 1 度の周期で探す満了した START (端末ごとの組) の数。残りは次の周期に回す。 */
    static final int BATCH = 64;
    private static final long REQUEST_ID_LIMIT = 2_176_782_336L;
    private static final String COLUMNS = "REQUEST_ID, START_TOKEN, TRANSID, START_DATA, RTRANSID, RTERMID,"
            + " QUEUE_NAME, OWNER_NAME, USER_ID, TERMINAL_ID";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate separate;
    private final Clock clock;
    private final Predicate<TransId> defined;
    private final CicsTerminalRegistryPort terminals;
    private final Duration terminalLease;
    private final Function<CicsStartData, CicsTerminalTasks.Outcome> launcher;
    private final Duration pollInterval;
    private final Executor tasks;
    private final ExecutorService ownedTasks;
    private ScheduledExecutorService scheduler;

    /**
     * @param defined       transaction が定義されているか。されていなければ TRANSIDERR
     * @param terminals     TERMID の端末の登録
     * @param terminalLease 端末へ出す task の間、端末を lease する長さ
     * @param launcher      満了した START の task を起こし、端末の次の疑似会話を返す ({@link CicsStartPort#conversing})。
     *                      START を出した task とは別の thread で呼ぶ
     * @param pollInterval  満了した START を探す間隔。起こすまでの遅れはこの間隔までである
     */
    public JdbcCicsStarts(DataSource dataSource, PlatformTransactionManager transactionManager, Clock clock,
                          Predicate<TransId> defined, CicsTerminalRegistryPort terminals, Duration terminalLease,
                          Function<CicsStartData, CicsTerminalTasks.Outcome> launcher, Duration pollInterval) {
        this(dataSource, transactionManager, clock, defined, terminals, terminalLease, launcher, pollInterval, null);
    }

    /** 試験は task を起こす executor を替え、起こした順を決める。 */
    JdbcCicsStarts(DataSource dataSource, PlatformTransactionManager transactionManager, Clock clock,
                   Predicate<TransId> defined, CicsTerminalRegistryPort terminals, Duration terminalLease,
                   Function<CicsStartData, CicsTerminalTasks.Outcome> launcher, Duration pollInterval,
                   Executor tasks) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.separate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defined = Objects.requireNonNull(defined, "defined");
        this.terminals = Objects.requireNonNull(terminals, "terminals");
        this.terminalLease = Objects.requireNonNull(terminalLease, "terminalLease");
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
        if (data.terminalId().isPresent()) {
            Optional<Terminal> terminal = terminals.find(data.terminalId().orElseThrow(), clock.instant());
            if (terminal.isEmpty() || !terminal.orElseThrow().owner().equals(data.owner())) {
                // TERMIDERR: 端末が定義されていない。他の利用者の端末も無いものとして見せる (暫定判断 P-144)
                return new Result(CicsResponseCode.TERMIDERR, 0);
            }
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
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    data.requestId(), UUID.randomUUID().toString(), data.transaction().value(),
                    data.terminalId().orElse(null), expiration.toEpochMilli(), data.data().orElse(null),
                    data.returnTransaction().orElse(null), data.returnTerminal().orElse(null),
                    data.queue().orElse(null), data.owner(), data.userId().orElse(null)));
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
        // 満了した START は、dispatcher がまだ起こしていなくても (端末を待っていても) 取り消せない (推定。P-144)
        Integer deleted = separate.execute(status -> jdbc.update(
                "DELETE FROM COBOL_START WHERE REQUEST_ID = ? AND EXPIRES_AT > ?", requestId, now));
        return deleted != null && deleted == 1 ? NORMAL : new Result(CicsResponseCode.NOTFND, 0);
    }

    /**
     * 待っている task の端末と TRANSID の、満了した START を取り出す。この task が端末を lease しているので、dispatcher は
     * これらの行を起こさない。取り出した行は消し、owner の違う行 (端末の名前が振り直された) は捨てる。
     */
    @Override
    public List<CicsStartData> retrieveMore(CicsStartData started) {
        String terminal = started.terminalId().orElseThrow(() -> new CicsTaskStateException(
                "RETRIEVE WAIT requires a task started by START TERMID"));
        List<Due> due = jdbc.query("SELECT " + COLUMNS + " FROM COBOL_START WHERE TERMINAL_ID = ? AND TRANSID = ?"
                        + " AND EXPIRES_AT <= ? ORDER BY EXPIRES_AT, REQUEST_ID",
                (row, index) -> due(row), terminal, started.transaction().value(), clock.instant().toEpochMilli());
        List<CicsStartData> claimed = new ArrayList<>();
        for (Due start : due) {
            if (claim(start) && start.data().owner().equals(started.owner())) {
                claimed.add(start.data());
            }
        }
        return claimed;
    }

    @Override
    public String newRequestId() {
        String digits = Long.toString(ThreadLocalRandom.current().nextLong(REQUEST_ID_LIMIT), 36)
                .toUpperCase(Locale.ROOT);
        return "JV" + "0".repeat(6 - digits.length()) + digits;
    }

    private record Due(String token, CicsStartData data) {
    }

    private static Due due(java.sql.ResultSet row) throws java.sql.SQLException {
        String terminal = row.getString(10);
        return new Due(row.getString(2), new CicsStartData(row.getString(1), TransId.of(row.getString(3).strip()),
                row.getBytes(4), Optional.ofNullable(row.getString(5)), Optional.ofNullable(row.getString(6)),
                Optional.ofNullable(row.getString(7)), row.getString(8), Optional.ofNullable(row.getString(9)),
                Optional.ofNullable(terminal).map(String::strip)));
    }

    /** 行を消せた JVM だけが起こす。START_TOKEN で、見てから消すまでに同じ REQID で登録し直された行を消さない。 */
    private boolean claim(Due start) {
        Integer deleted = separate.execute(status -> jdbc.update(
                "DELETE FROM COBOL_START WHERE REQUEST_ID = ? AND START_TOKEN = ?",
                start.data().requestId(), start.token()));
        return deleted != null && deleted == 1;
    }

    /**
     * 満了した START を探して起こす。
     *
     * @return この呼び出しが起こした task の数
     */
    public int dispatchDue() {
        Instant now = clock.instant();
        return dispatchDetached(now) + dispatchToTerminals(now);
    }

    private int dispatchDetached(Instant now) {
        List<Due> due = jdbc.query("SELECT " + COLUMNS + " FROM COBOL_START WHERE TERMINAL_ID IS NULL AND EXPIRES_AT <= ?"
                        + " ORDER BY EXPIRES_AT FETCH FIRST " + BATCH + " ROWS ONLY",
                (row, index) -> due(row), now.toEpochMilli());
        int launched = 0;
        for (Due start : due) {
            if (!claim(start)) {
                continue;
            }
            launched++;
            tasks.execute(() -> {
                try {
                    launcher.apply(start.data());
                } catch (RuntimeException failure) {
                    LOG.log(System.Logger.Level.WARNING, "task started by START REQID(" + start.data().requestId()
                            + ") TRANSID(" + start.data().transaction().value() + ") failed", failure);
                }
            });
        }
        return launched;
    }

    private record Pair(String terminal, String transaction) {
    }

    private int dispatchToTerminals(Instant now) {
        long millis = now.toEpochMilli();
        List<Pair> pairs = jdbc.query("SELECT TERMINAL_ID, TRANSID, MIN(EXPIRES_AT) FROM COBOL_START"
                        + " WHERE TERMINAL_ID IS NOT NULL AND EXPIRES_AT <= ? GROUP BY TERMINAL_ID, TRANSID"
                        + " ORDER BY MIN(EXPIRES_AT) FETCH FIRST " + BATCH + " ROWS ONLY",
                (row, index) -> new Pair(row.getString(1).strip(), row.getString(2).strip()), millis);
        int launched = 0;
        for (Pair pair : pairs) {
            Optional<Terminal> found = terminals.find(pair.terminal(), now);
            if (found.isEmpty()) {
                // 満了したときに端末が無い START は捨てる (START の頁)
                separate.executeWithoutResult(status -> jdbc.update(
                        "DELETE FROM COBOL_START WHERE TERMINAL_ID = ? AND EXPIRES_AT <= ?", pair.terminal(), millis));
                continue;
            }
            Terminal terminal = found.orElseThrow();
            if (terminal.leased() || terminal.conversation().isPresent()) {
                // 端末で task が動いているか、疑似会話の途中。次の周期に待つ (利用者の決定、P-144)
                continue;
            }
            Optional<TerminalLease> acquired = terminals.lease(pair.terminal(), terminal.owner(), terminalLease, now);
            if (acquired.isEmpty()) {
                continue;
            }
            TerminalLease lease = acquired.orElseThrow();
            List<CicsStartData> claimed = new ArrayList<>();
            try {
                List<Due> due = jdbc.query("SELECT " + COLUMNS + " FROM COBOL_START WHERE TERMINAL_ID = ? AND TRANSID = ?"
                                + " AND EXPIRES_AT <= ? ORDER BY EXPIRES_AT, REQUEST_ID",
                        (row, index) -> due(row), pair.terminal(), pair.transaction(), millis);
                for (Due start : due) {
                    if (!claim(start)) {
                        continue;
                    }
                    if (!start.data().owner().equals(terminal.owner())) {
                        // 端末の名前が別の利用者に振り直された。元の端末と一緒に START を捨てる
                        continue;
                    }
                    claimed.add(start.data());
                }
            } catch (RuntimeException failure) {
                terminals.release(lease, clock.instant());
                throw failure;
            }
            if (claimed.isEmpty()) {
                terminals.release(lease, clock.instant());
                continue;
            }
            CicsStartData first = claimed.get(0).withFollowing(claimed.subList(1, claimed.size()));
            launched++;
            tasks.execute(() -> runOnTerminal(first, lease));
        }
        return launched;
    }

    private void runOnTerminal(CicsStartData start, TerminalLease lease) {
        try {
            CicsTerminalTasks.Outcome outcome = launcher.apply(start);
            // task が送った画面を端末の現在の画面にして版を進める。ブラウザはこの版の変化で書き換わった画面を知る
            outcome.screen().ifPresent(screen -> terminals.setScreen(lease, screen, clock.instant()));
            Optional<TerminalConversation> next = outcome.next()
                    .map(envelope -> new TerminalConversation(envelope.id(), envelope.version(),
                            envelope.nextTransaction()));
            if (!terminals.setConversation(lease, next, clock.instant())) {
                LOG.log(System.Logger.Level.WARNING, "terminal lease expired while the task started by START REQID("
                        + start.requestId() + ") was running; the terminal keeps its previous conversation");
            }
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "task started on a terminal by START REQID(" + start.requestId()
                    + ") TRANSID(" + start.transaction().value() + ") failed", failure);
        } finally {
            try {
                terminals.release(lease, clock.instant());
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "failed to release a terminal lease", failure);
            }
        }
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
