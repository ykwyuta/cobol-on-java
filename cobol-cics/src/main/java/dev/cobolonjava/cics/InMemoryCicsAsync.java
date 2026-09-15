package dev.cobolonjava.cics;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 1 つの JVM の中の非同期 API (暫定判断 P-140)。
 *
 * <p>子の task は親とは別の thread で、別の UOW として動く。子が ABEND 以外の理由で失敗したときの完了の状態は
 * 文書に無いので、その子を FETCH した親を失敗させる。
 */
final class InMemoryCicsAsync implements CicsAsyncPort, AutoCloseable {

    private static final class Child {
        private final byte[] token;
        private final boolean hasChannel;
        private boolean done;
        private boolean fetched;
        private boolean freed;
        private int status;
        private String abendCode = "";
        private Map<String, byte[]> reply;
        private RuntimeException failure;

        private Child(byte[] token, boolean hasChannel) {
            this.token = token;
            this.hasChannel = hasChannel;
        }
    }

    private final CicsTransactionRegistry transactions;
    private final Function<CicsAsyncChild, CicsPayload> launcher;
    private final Map<CicsTaskId, List<Child>> children = new HashMap<>();
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();

    InMemoryCicsAsync(CicsTransactionRegistry transactions, Function<CicsAsyncChild, CicsPayload> launcher) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    @Override
    public synchronized Run run(CicsTaskId parent, CicsAsyncChild request) {
        CicsTransactionDefinition definition = transactions.definitions().get(request.transaction());
        if (definition == null) {
            // TRANSIDERR (RESP2 1): transaction が定義されていない
            return new Run(CicsResponseCode.TRANSIDERR, 1, null);
        }
        if (!definition.enabled()) {
            // DISABLED (RESP2 50): transaction が使えない
            return new Run(CicsResponseCode.DISABLED, 50, null);
        }
        UUID id = UUID.randomUUID();
        Child child = new Child(ByteBuffer.allocate(TOKEN_LENGTH).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).array(), request.channelName().isPresent());
        children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(child);
        tasks.execute(() -> complete(child, request));
        return new Run(CicsResponseCode.NORMAL, 0, child.token.clone());
    }

    private void complete(Child child, CicsAsyncChild request) {
        int status = 0;
        String abendCode = "";
        Map<String, byte[]> reply = null;
        RuntimeException failure = null;
        try {
            CicsPayload payload = launcher.apply(request);
            status = CicsCvda.NORMAL;
            // 子の reply channel は、RUN で渡した channel を子が終えたときの姿である
            reply = child.hasChannel ? payload.containers() : null;
        } catch (CicsAbend abend) {
            status = CicsCvda.ABEND;
            abendCode = abend.code().value();
        } catch (RuntimeException unexpected) {
            failure = unexpected;
        }
        synchronized (this) {
            child.status = status;
            child.abendCode = abendCode;
            child.reply = reply;
            child.failure = failure;
            child.done = true;
            notifyAll();
        }
    }

    @Override
    public synchronized Fetched fetch(CicsTaskId parent, byte[] token, boolean noSuspend, long timeoutMillis,
                                      Duration maxWait) {
        if (timeoutMillis < 0 || timeoutMillis > MAX_TIMEOUT_MILLIS) {
            // INVREQ (RESP2 241): TIMEOUT の値が正しくない
            return condition(CicsResponseCode.INVREQ, 241);
        }
        List<Child> own = children.getOrDefault(parent, List.of()).stream().filter(child -> !child.freed).toList();
        Child wanted = null;
        if (token != null) {
            wanted = own.stream().filter(child -> Arrays.equals(child.token, token)).findFirst().orElse(null);
            if (wanted == null) {
                // INVREQ (RESP2 50): 正しい token でないか、もう FREE CHILD された
                return condition(CicsResponseCode.INVREQ, 50);
            }
            if (wanted.fetched) {
                // INVREQ (RESP2 51): 子の channel はもう取り出した
                return condition(CicsResponseCode.INVREQ, 51);
            }
        } else if (own.isEmpty()) {
            // INVREQ (RESP2 52): 親に子が無い
            return condition(CicsResponseCode.INVREQ, 52);
        } else if (own.stream().allMatch(child -> child.fetched)) {
            // NOTFND (RESP2 1): 取り出していない子が無い
            return condition(CicsResponseCode.NOTFND, 1);
        }
        long start = System.nanoTime();
        long timeoutDeadline = timeoutMillis == 0 ? Long.MAX_VALUE : start + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long taskDeadline = maxWait == null ? Long.MAX_VALUE : start + Math.max(0, maxWait.toNanos());
        while (true) {
            Child ready = wanted != null ? (wanted.done ? wanted : null)
                    : own.stream().filter(child -> child.done && !child.fetched).findFirst().orElse(null);
            if (ready != null) {
                ready.fetched = true;
                if (ready.failure != null) {
                    throw new CicsTaskStateException("child task failed without an ABEND code: "
                            + ready.failure.getMessage());
                }
                return new Fetched(CicsResponseCode.NORMAL, 0, ready.token.clone(), ready.status, ready.abendCode,
                        Optional.ofNullable(ready.reply));
            }
            if (noSuspend) {
                // NOTFINISHED (RESP2 52): NOSUSPEND で、終わった子が無い
                return condition(CicsResponseCode.NOTFINISHED, 52);
            }
            long now = System.nanoTime();
            if (now - timeoutDeadline >= 0) {
                // NOTFINISHED (RESP2 53): TIMEOUT の間に子が終わらなかった
                return condition(CicsResponseCode.NOTFINISHED, 53);
            }
            if (now - taskDeadline >= 0) {
                throw new CicsTaskStateException("FETCH wait exceeded the task deadline");
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, Math.min(timeoutDeadline - now, taskDeadline - now));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CicsTaskStateException("FETCH wait was interrupted");
            }
        }
    }

    @Override
    public synchronized Result free(CicsTaskId parent, byte[] token) {
        Child child = children.getOrDefault(parent, List.of()).stream()
                .filter(candidate -> !candidate.freed && Arrays.equals(candidate.token, token))
                .findFirst().orElse(null);
        if (child == null) {
            // INVREQ (RESP2 50): 正しい token でないか、もう FREE CHILD された
            return new Result(CicsResponseCode.INVREQ, 50);
        }
        child.freed = true;
        return new Result(CicsResponseCode.NORMAL, 0);
    }

    @Override
    public synchronized void releaseTask(CicsTaskId parent) {
        children.remove(parent);
    }

    private static Fetched condition(int response, int response2) {
        return new Fetched(response, response2, null, 0, "", Optional.empty());
    }

    @Override
    public void close() {
        tasks.shutdown();
    }
}
