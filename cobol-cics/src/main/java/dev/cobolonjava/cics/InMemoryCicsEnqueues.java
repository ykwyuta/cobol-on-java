package dev.cobolonjava.cics;

import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** 1 つの JVM の中で task どうしが資源を排他する {@link CicsEnqueuePort}。 */
final class InMemoryCicsEnqueues implements CicsEnqueuePort {

    /** 資源を持つ task と、UOW の間・task の間それぞれで得た数。 */
    private static final class Hold {
        private final CicsTaskId owner;
        private int unitOfWork;
        private int task;

        private Hold(CicsTaskId owner) {
            this.owner = owner;
        }

        private boolean released() {
            return unitOfWork == 0 && task == 0;
        }
    }

    private final Map<String, Hold> holds = new HashMap<>();

    @Override
    public synchronized boolean enqueue(
            CicsTaskId owner, byte[] resource, boolean taskScope, boolean wait, Duration maxWait) {
        Objects.requireNonNull(owner, "owner");
        String key = key(resource);
        long deadline = maxWait == null ? Long.MAX_VALUE : System.nanoTime() + Math.max(0, maxWait.toNanos());
        while (true) {
            Hold hold = holds.computeIfAbsent(key, ignored -> new Hold(owner));
            if (hold.owner.equals(owner)) {
                if (taskScope) {
                    hold.task++;
                } else {
                    hold.unitOfWork++;
                }
                return true;
            }
            if (!wait) {
                return false;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new CicsTaskStateException("ENQ wait exceeded the task deadline");
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CicsTaskStateException("ENQ wait was interrupted");
            }
        }
    }

    @Override
    public synchronized void dequeue(CicsTaskId owner, byte[] resource, boolean taskScope) {
        String key = key(resource);
        Hold hold = holds.get(key);
        if (hold == null || !hold.owner.equals(owner)) {
            return;
        }
        if (taskScope) {
            hold.task = Math.max(0, hold.task - 1);
        } else {
            hold.unitOfWork = Math.max(0, hold.unitOfWork - 1);
        }
        if (hold.released()) {
            holds.remove(key);
            notifyAll();
        }
    }

    @Override
    public synchronized void releaseUnitOfWork(CicsTaskId owner) {
        release(owner, false);
    }

    @Override
    public synchronized void releaseTask(CicsTaskId owner) {
        release(owner, true);
    }

    private void release(CicsTaskId owner, boolean all) {
        boolean changed = false;
        for (Iterator<Hold> it = holds.values().iterator(); it.hasNext(); ) {
            Hold hold = it.next();
            if (!hold.owner.equals(owner)) {
                continue;
            }
            hold.unitOfWork = 0;
            if (all) {
                hold.task = 0;
            }
            if (hold.released()) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            notifyAll();
        }
    }

    private static String key(byte[] resource) {
        Objects.requireNonNull(resource, "resource");
        if (resource.length < 1 || resource.length > 255) {
            throw new IllegalArgumentException("ENQ resource must be 1 to 255 bytes: " + resource.length);
        }
        return HexFormat.of().formatHex(resource);
    }
}
