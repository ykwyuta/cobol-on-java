package dev.cobolonjava.cics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 1 つの JVM の中の START (暫定判断 P-138)。
 *
 * <p>未満了の START は JVM が止まれば消える。起こした task の失敗は START を出した task へは返らず、記録だけする。
 */
final class InMemoryCicsStarts implements CicsStartPort, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(InMemoryCicsStarts.class.getName());
    private static final Result NORMAL = new Result(CicsResponseCode.NORMAL, 0);
    /** 作る REQID の通し番号の桁 (36 進 6 桁)。 */
    private static final long SEQUENCE_LIMIT = 2_176_782_336L;

    private final Clock clock;
    private final Predicate<TransId> defined;
    private final Consumer<CicsStartData> launcher;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cics-start-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ScheduledFuture<?>> pending = new HashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    InMemoryCicsStarts(Clock clock, Predicate<TransId> defined, Consumer<CicsStartData> launcher) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.defined = Objects.requireNonNull(defined, "defined");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    @Override
    public synchronized Result start(Instant expiration, CicsStartData data) {
        if (!defined.test(data.transaction())) {
            // TRANSIDERR: 起こす transaction が定義されていない
            return new Result(CicsResponseCode.TRANSIDERR, 0);
        }
        String id = data.requestId();
        if (pending.containsKey(id)) {
            if (data.data().isPresent()) {
                // IOERR: FROM を持つ START の REQID が既にある
                return new Result(CicsResponseCode.IOERR, 0);
            }
            throw new CicsTaskStateException("START REQID(" + id + ") is already pending;"
                    + " the condition for a START without FROM is not documented");
        }
        long delay = Math.max(0, Duration.between(clock.instant(), expiration).toMillis());
        // 満了の処理はこの監視を取ってから pending を見るので、登録より先に起きることは無い
        pending.put(id, scheduler.schedule(() -> expire(id, data), delay, TimeUnit.MILLISECONDS));
        return NORMAL;
    }

    private void expire(String id, CicsStartData data) {
        synchronized (this) {
            if (pending.remove(id) == null) {
                return;
            }
        }
        tasks.execute(() -> {
            try {
                launcher.accept(data);
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING,
                        "task started by START REQID(" + id + ") TRANSID(" + data.transaction().value() + ") failed",
                        failure);
            }
        });
    }

    @Override
    public synchronized Result cancel(String requestId) {
        ScheduledFuture<?> future = pending.remove(Objects.requireNonNull(requestId, "requestId"));
        if (future == null) {
            // NOTFND: REQID が未満了の START に当たらない
            return new Result(CicsResponseCode.NOTFND, 0);
        }
        future.cancel(false);
        return NORMAL;
    }

    @Override
    public String newRequestId() {
        String digits = Long.toString(sequence.getAndIncrement() % SEQUENCE_LIMIT, 36).toUpperCase(java.util.Locale.ROOT);
        return "JV" + "0".repeat(6 - digits.length()) + digits;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        tasks.shutdown();
    }
}
