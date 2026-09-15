package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsResponseCode;
import dev.cobolonjava.cics.CicsTransientDataPort;
import dev.cobolonjava.cics.CicsTransientDataQueueDefinition;
import dev.cobolonjava.cics.CicsTransientDataTrigger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 区画内の一時データのキューを {@code COBOL_TD_QUEUE} / {@code COBOL_TD_RECORD} の表に置き、複数の JVM で分け合う
 * (設計 83 §6・§8、暫定判断 P-137・P-144)。
 *
 * <p>TD は回復不能なので、WRITEQ / READQ / DELETEQ TD は task の UOW に入れず、別の transaction で直ちに確定する。
 * 操作の初めにキューの行を UPDATE して lock するので、同じキューの書き込みと読み出しはどの JVM から来ても直列になり、
 * 1 つの record を 2 つの task が読むことは無い。キューの定義は全部の JVM で同じものを渡す。
 *
 * <h2>trigger level (ATI)</h2>
 * <p>trigger の状態をキューの行に持つ (設計 83 §6.2)。
 * <ul>
 *   <li>{@code ARMED}: WRITEQ のあとの record の数が trigger level 以上なら {@code PENDING}</li>
 *   <li>{@code PENDING}: dispatcher が task の token を置いて {@code ATTACHED} にし、task を起こす。token がある間は
 *       次の task を起こさない (trigger の task はキューに対して逐次)</li>
 *   <li>READQ TD が QZERO を返せば {@code ARMED} に戻す (回復不能のキュー)</li>
 *   <li>task が正常に終われば、ATIFACILITY(FILE) は trigger を戻して token を外す。数が trigger level 以上なら
 *       {@code PENDING}。空にする前に ABEND すれば {@code BLOCKED} にし、次の QZERO まで起こさない</li>
 *   <li>token の期限が過ぎれば (JVM が止まった等) task が終わったか分からないので、ABEND と同じに扱う</li>
 * </ul>
 * ATIFACILITY(TERMINAL) はまだ持たず、構成の時点で断る。
 */
public final class JdbcCicsTransientData implements CicsTransientDataPort, SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(JdbcCicsTransientData.class.getName());
    private static final Result NORMAL = new Result(CicsResponseCode.NORMAL, 0);
    /** QIDERR: キューが定義されていない。 */
    private static final Result NO_QUEUE = new Result(CicsResponseCode.QIDERR, 0);

    private final Map<String, CicsTransientDataQueueDefinition> definitions = new HashMap<>();
    private final Set<String> created = ConcurrentHashMap.newKeySet();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate separate;
    private final Clock clock;
    private final String regionOwner;
    private final Optional<String> defaultUserId;
    private final Consumer<CicsTransientDataTrigger> launcher;
    private final Duration taskLease;
    private final Duration pollInterval;
    private final Executor tasks;
    private final ExecutorService ownedTasks;
    private ScheduledExecutorService scheduler;

    /** trigger level を持たないキューだけの region。trigger level を書いた定義は断る。 */
    public JdbcCicsTransientData(DataSource dataSource, PlatformTransactionManager transactionManager,
                                 List<CicsTransientDataQueueDefinition> queues) {
        this(dataSource, transactionManager, queues, Clock.systemUTC(), null, Optional.empty(), null,
                Duration.ofMinutes(5), Duration.ofSeconds(1), Runnable::run);
    }

    /**
     * trigger level で task を起こすキューを持てる region。
     *
     * @param regionOwner   ATIFACILITY(FILE) の task を動かす owner 名
     * @param defaultUserId 定義に USERID が無いときの user ID。どちらも無い trigger の定義は断る
     * @param launcher      trigger の task を起こす ({@link CicsTransientDataTrigger#launching})。例外は ABEND と扱う
     * @param taskLease     trigger の task の token の長さ。task の期限より長くなければならない
     * @param pollInterval  PENDING のキューを探す間隔
     */
    public JdbcCicsTransientData(DataSource dataSource, PlatformTransactionManager transactionManager,
                                 List<CicsTransientDataQueueDefinition> queues, Clock clock, String regionOwner,
                                 Optional<String> defaultUserId, Consumer<CicsTransientDataTrigger> launcher,
                                 Duration taskLease, Duration pollInterval) {
        this(dataSource, transactionManager, queues, clock, regionOwner, defaultUserId, launcher, taskLease,
                pollInterval, null);
    }

    /** 試験は task を起こす executor を替え、task が終わる時を決める。 */
    JdbcCicsTransientData(DataSource dataSource, PlatformTransactionManager transactionManager,
                          List<CicsTransientDataQueueDefinition> queues, Clock clock, String regionOwner,
                          Optional<String> defaultUserId, Consumer<CicsTransientDataTrigger> launcher,
                          Duration taskLease, Duration pollInterval, Executor tasks) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.separate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.regionOwner = regionOwner;
        this.defaultUserId = Objects.requireNonNull(defaultUserId, "defaultUserId");
        this.launcher = launcher;
        this.taskLease = Objects.requireNonNull(taskLease, "taskLease");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        if (taskLease.isNegative() || taskLease.isZero() || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("task lease and poll interval must be positive");
        }
        for (CicsTransientDataQueueDefinition queue : Objects.requireNonNull(queues, "queues")) {
            if (queue.triggers()) {
                if (launcher == null || regionOwner == null || regionOwner.isBlank()) {
                    throw new IllegalArgumentException("trigger level requires a launcher and a region owner: "
                            + queue.name());
                }
                if (queue.facility() != CicsTransientDataQueueDefinition.Facility.FILE) {
                    throw new IllegalArgumentException("ATIFACILITY(TERMINAL) is not supported yet: " + queue.name());
                }
                if (queue.userId().isEmpty() && defaultUserId.isEmpty()) {
                    // 推測で空の user ID の task を起こさない
                    throw new IllegalArgumentException("trigger level requires USERID or a default user ID: "
                            + queue.name());
                }
            }
            if (definitions.put(queue.name(), queue) != null) {
                throw new IllegalArgumentException("duplicate transient data queue: " + queue.name());
            }
        }
        this.ownedTasks = tasks == null ? Executors.newVirtualThreadPerTaskExecutor() : null;
        this.tasks = tasks == null ? ownedTasks : tasks;
    }

    @Override
    public Result write(String queue, byte[] data) {
        CicsTransientDataQueueDefinition definition = definitions.get(Objects.requireNonNull(queue, "queue"));
        if (definition == null) {
            return NO_QUEUE;
        }
        if (data.length < 1 || data.length > definition.maxRecordLength()) {
            // LENGERR: 長さが定義の record の長さと合わない
            return new Result(CicsResponseCode.LENGERR, 0);
        }
        ensureQueue(queue);
        byte[] copy = data.clone();
        separate.executeWithoutResult(status -> {
            lock(queue, "NEXT_SEQUENCE = NEXT_SEQUENCE + 1, RECORD_COUNT = RECORD_COUNT + 1");
            Long sequence = jdbc.queryForObject("SELECT NEXT_SEQUENCE FROM COBOL_TD_QUEUE WHERE QUEUE_NAME = ?",
                    Long.class, queue);
            jdbc.update("INSERT INTO COBOL_TD_RECORD (QUEUE_NAME, SEQUENCE_NO, RECORD_DATA) VALUES (?, ?, ?)",
                    queue, sequence, copy);
            if (definition.triggers()) {
                // 「達する」は WRITEQ のあとの数が trigger level 以上と読む (推定。P-144)
                jdbc.update("UPDATE COBOL_TD_QUEUE SET TRIGGER_STATE = 'PENDING'"
                        + " WHERE QUEUE_NAME = ? AND TRIGGER_STATE = 'ARMED' AND RECORD_COUNT >= ?",
                        queue, definition.triggerLevel());
            }
        });
        return NORMAL;
    }

    private record Record(long sequence, byte[] data) {
    }

    @Override
    public Read read(String queue) {
        CicsTransientDataQueueDefinition definition = definitions.get(Objects.requireNonNull(queue, "queue"));
        if (definition == null) {
            return new Read(NO_QUEUE.response(), NO_QUEUE.response2(), null);
        }
        ensureQueue(queue);
        return separate.execute(status -> {
            lock(queue, "RECORD_COUNT = RECORD_COUNT");
            List<Record> first = jdbc.query("SELECT SEQUENCE_NO, RECORD_DATA FROM COBOL_TD_RECORD WHERE QUEUE_NAME = ?"
                            + " ORDER BY SEQUENCE_NO FETCH FIRST 1 ROWS ONLY",
                    (row, index) -> new Record(row.getLong(1), row.getBytes(2)), queue);
            if (first.isEmpty()) {
                if (definition.triggers()) {
                    // 回復不能のキューは QZERO まで読んだときに次の ATI の周期が始まる (ATI の頁)
                    jdbc.update("UPDATE COBOL_TD_QUEUE SET TRIGGER_STATE = 'ARMED'"
                            + " WHERE QUEUE_NAME = ? AND TRIGGER_STATE <> 'ARMED'", queue);
                }
                // QZERO: キューが空
                return new Read(CicsResponseCode.QZERO, 0, null);
            }
            Record record = first.get(0);
            jdbc.update("DELETE FROM COBOL_TD_RECORD WHERE QUEUE_NAME = ? AND SEQUENCE_NO = ?", queue, record.sequence());
            jdbc.update("UPDATE COBOL_TD_QUEUE SET RECORD_COUNT = RECORD_COUNT - 1 WHERE QUEUE_NAME = ?", queue);
            return new Read(CicsResponseCode.NORMAL, 0, record.data());
        });
    }

    @Override
    public Result delete(String queue) {
        if (!definitions.containsKey(Objects.requireNonNull(queue, "queue"))) {
            return NO_QUEUE;
        }
        ensureQueue(queue);
        separate.executeWithoutResult(status -> {
            lock(queue, "RECORD_COUNT = 0");
            jdbc.update("DELETE FROM COBOL_TD_RECORD WHERE QUEUE_NAME = ?", queue);
        });
        return NORMAL;
    }

    /**
     * PENDING のキューの trigger の task を起こす。期限の過ぎた task の token も片付ける。
     *
     * @return この呼び出しが起こした task の数
     */
    public int dispatchDue() {
        long now = clock.instant().toEpochMilli();
        // token の期限が過ぎた task は終わったか分からない。空にする前 (ATTACHED) なら次の QZERO まで起こさない
        separate.executeWithoutResult(status -> jdbc.update("UPDATE COBOL_TD_QUEUE SET TRIGGER_STATE ="
                + " CASE WHEN TRIGGER_STATE = 'ATTACHED' THEN 'BLOCKED' ELSE TRIGGER_STATE END,"
                + " TASK_TOKEN = NULL, TASK_UNTIL = 0 WHERE TASK_TOKEN IS NOT NULL AND TASK_UNTIL <= ?", now));
        List<String> pending = jdbc.queryForList("SELECT QUEUE_NAME FROM COBOL_TD_QUEUE"
                + " WHERE TRIGGER_STATE = 'PENDING' AND TASK_TOKEN IS NULL", String.class);
        int launched = 0;
        for (String name : pending) {
            CicsTransientDataQueueDefinition definition = definitions.get(name.strip());
            if (definition == null || !definition.triggers()) {
                continue;
            }
            String token = UUID.randomUUID().toString();
            long until = Instant.ofEpochMilli(now).plus(taskLease).toEpochMilli();
            Integer claimed = separate.execute(status -> jdbc.update("UPDATE COBOL_TD_QUEUE SET TRIGGER_STATE = 'ATTACHED',"
                    + " TASK_TOKEN = ?, TASK_UNTIL = ? WHERE QUEUE_NAME = ? AND TRIGGER_STATE = 'PENDING'"
                    + " AND TASK_TOKEN IS NULL", token, until, definition.name()));
            if (claimed == null || claimed != 1) {
                continue;
            }
            launched++;
            CicsTransientDataTrigger trigger = new CicsTransientDataTrigger(definition.name(),
                    definition.transaction().orElseThrow(), regionOwner, definition.userId().or(() -> defaultUserId));
            tasks.execute(() -> runTrigger(definition, trigger, token));
        }
        return launched;
    }

    private void runTrigger(CicsTransientDataQueueDefinition definition, CicsTransientDataTrigger trigger,
                            String token) {
        boolean normal = false;
        try {
            launcher.accept(trigger);
            normal = true;
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "trigger-level task TRANSID(" + trigger.transaction().value()
                    + ") for queue " + trigger.queue() + " failed", failure);
        } finally {
            try {
                finish(definition, token, normal);
            } catch (RuntimeException failure) {
                // token は期限で片付く
                LOG.log(System.Logger.Level.WARNING, "failed to record the end of a trigger-level task", failure);
            }
        }
    }

    /** task の終わり。token が合うときだけ変える (期限で片付けられたあとの古い task は何も変えない)。 */
    private void finish(CicsTransientDataQueueDefinition definition, String token, boolean normal) {
        separate.executeWithoutResult(status -> {
            int ended = jdbc.update("UPDATE COBOL_TD_QUEUE SET TRIGGER_STATE = CASE WHEN TRIGGER_STATE = 'ATTACHED'"
                    + " THEN ? ELSE TRIGGER_STATE END, TASK_TOKEN = NULL, TASK_UNTIL = 0"
                    + " WHERE QUEUE_NAME = ? AND TASK_TOKEN = ?",
                    // FILE の task の正常な終わりは trigger を戻す。空にする前の ABEND は次の QZERO まで起こさない
                    normal ? "ARMED" : "BLOCKED", definition.name(), token);
            if (ended == 1) {
                jdbc.update("UPDATE COBOL_TD_QUEUE SET TRIGGER_STATE = 'PENDING'"
                        + " WHERE QUEUE_NAME = ? AND TRIGGER_STATE = 'ARMED' AND RECORD_COUNT >= ?",
                        definition.name(), definition.triggerLevel());
            }
        });
    }

    /** キューの行を更新して、この transaction の終わりまで同じキューの他の操作を待たせる。 */
    private void lock(String queue, String assignment) {
        int locked = jdbc.update("UPDATE COBOL_TD_QUEUE SET " + assignment + " WHERE QUEUE_NAME = ?", queue);
        if (locked != 1) {
            throw new IllegalStateException("transient data queue row is missing: " + queue);
        }
    }

    /** キューの行が無ければ作る。別の JVM が先に作っていれば、それを使う。 */
    private void ensureQueue(String queue) {
        if (created.contains(queue)) {
            return;
        }
        try {
            separate.executeWithoutResult(status -> jdbc.update("INSERT INTO COBOL_TD_QUEUE (QUEUE_NAME, NEXT_SEQUENCE,"
                    + " RECORD_COUNT, TRIGGER_STATE, TASK_TOKEN, TASK_UNTIL) VALUES (?, 0, 0, 'ARMED', NULL, 0)", queue));
        } catch (DuplicateKeyException exists) {
            // 別の JVM か、この JVM の先の要求が作った
        }
        created.add(queue);
    }

    @Override
    public synchronized void start() {
        if (scheduler != null || definitions.values().stream().noneMatch(CicsTransientDataQueueDefinition::triggers)) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cics-td-trigger-dispatcher");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                dispatchDue();
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "failed to dispatch trigger-level tasks", failure);
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
