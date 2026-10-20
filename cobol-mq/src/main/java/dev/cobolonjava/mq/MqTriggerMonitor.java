package dev.cobolonjava.mq;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * trigger monitor (CICS の CKTI にあたる、設計 88 §4.5): 開始待ち行列を入力で開いておき、置かれた起動電文を読む。
 *
 * <p>読んだ電文で transaction を起こすのは持ち主である。CICS なら {@code START TRANSID(transactionId)} の
 * データとして MQTM を渡し、起こされたプログラムが {@code RETRIEVE INTO(MQTM)} で受ける (CardDemo の CODATE01 /
 * COACCT01 の形)。`cobol-mq` を CICS に依らせないために、起こす部分は持ち主に任せた (Zeus d2f1cfc の
 * {@code crates/zeus-cics/src/region.rs} の {@code poll_triggers} も、読むのは MQ の側、起こすのは region の側である)。
 *
 * <p>開いている間は開始待ち行列の入力の数 (IPPROCS) に数えられる。起動の条件の 1 つが「開始待ち行列を入力で
 * 開いているものがある」なので、monitor を開く前に届いた電文では起動しない。
 */
public final class MqTriggerMonitor implements AutoCloseable {

    private final MqQueueManager manager;
    private final LocalQueue queue;
    private boolean closed;

    /** 開始待ち行列 {@code initiationQueue} を開く。定義の無い待ち行列は断る。 */
    public MqTriggerMonitor(MqQueueManager manager, String initiationQueue) {
        this.manager = Objects.requireNonNull(manager, "manager");
        manager.lock.lock();
        try {
            LocalQueue q = manager.lookup(initiationQueue);
            if (q == null) {
                throw new IllegalArgumentException("initiation queue is not defined: " + initiationQueue);
            }
            q.openInput++;
            this.queue = q;
        } finally {
            manager.lock.unlock();
        }
    }

    /** いま置かれている起動電文をすべて取る (同期点の外)。 */
    public List<TriggerMessage> poll() {
        List<TriggerMessage> out = new ArrayList<>();
        Optional<TriggerMessage> next;
        while ((next = next(Duration.ZERO)).isPresent()) {
            out.add(next.get());
        }
        return out;
    }

    /** 次の起動電文を {@code wait} だけ待って取る。来なければ空。 */
    public Optional<TriggerMessage> next(Duration wait) {
        manager.lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("trigger monitor is closed");
            }
            long remaining = wait.toNanos();
            while (true) {
                queue.expire(manager.now());
                LocalQueue.Entry e = queue.getInhibited ? null : queue.select(null, null);
                if (e != null) {
                    queue.visible.remove(e);
                    return Optional.of(TriggerMessage.parse(manager.codePage(), e.message().data()));
                }
                if (remaining <= 0) {
                    return Optional.empty();
                }
                try {
                    remaining = manager.await(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        } finally {
            manager.lock.unlock();
        }
    }

    @Override
    public void close() {
        manager.lock.lock();
        try {
            if (!closed) {
                closed = true;
                queue.openInput--;
            }
        } finally {
            manager.lock.unlock();
        }
    }
}
