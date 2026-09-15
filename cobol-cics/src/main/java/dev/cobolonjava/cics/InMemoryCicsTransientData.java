package dev.cobolonjava.cics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 1 つの JVM の中の区画内の一時データのキュー (暫定判断 P-137、P-148)。
 *
 * <p>回復可能なキューへの task の変更は task ごとに貯め、同期点の commit で反映し、ROLLBACK と ABEND で捨てる
 * (設計 85 §7.2)。読んだ record はキューから外し、取り消せば先頭へ元の順に戻す。書いた record は commit まで
 * どの task (書いた task 自身を含む) にも見えない。
 */
final class InMemoryCicsTransientData implements CicsTransientDataPort {

    private static final Result NORMAL = new Result(CicsResponseCode.NORMAL, 0);
    /** QIDERR: キューが定義されていない。 */
    private static final Result NO_QUEUE = new Result(CicsResponseCode.QIDERR, 0);

    /** task が回復可能なキューに対して貯めた変更。 */
    private static final class Pending {
        private final Map<String, List<byte[]>> writes = new LinkedHashMap<>();
        private final Map<String, Deque<byte[]>> reads = new LinkedHashMap<>();
        private final Set<String> deleted = new HashSet<>();
    }

    private final Map<String, CicsTransientDataQueueDefinition> definitions = new HashMap<>();
    private final Map<String, Deque<byte[]>> records = new HashMap<>();
    private final Map<CicsTaskId, Pending> pending = new HashMap<>();

    InMemoryCicsTransientData(List<CicsTransientDataQueueDefinition> queues) {
        for (CicsTransientDataQueueDefinition queue : queues) {
            if (queue.triggers()) {
                // 1 つの JVM の中のキューは task を起こす先を持たない。trigger level を黙って無視しない
                throw new IllegalArgumentException("trigger level (ATI) requires JdbcCicsTransientData: " + queue.name());
            }
            if (definitions.put(queue.name(), queue) != null) {
                throw new IllegalArgumentException("duplicate transient data queue: " + queue.name());
            }
            records.put(queue.name(), new ArrayDeque<>());
        }
    }

    @Override
    public synchronized Result write(String queue, byte[] data) {
        CicsTransientDataQueueDefinition definition = definitions.get(Objects.requireNonNull(queue, "queue"));
        if (definition == null) {
            return NO_QUEUE;
        }
        Result length = lengthCondition(definition, data);
        if (length != null) {
            return length;
        }
        records.get(queue).addLast(data.clone());
        return NORMAL;
    }

    @Override
    public synchronized Read read(String queue) {
        if (!definitions.containsKey(Objects.requireNonNull(queue, "queue"))) {
            return new Read(NO_QUEUE.response(), NO_QUEUE.response2(), null);
        }
        byte[] record = records.get(queue).pollFirst();
        if (record == null) {
            // QZERO: キューが空
            return new Read(CicsResponseCode.QZERO, 0, null);
        }
        return new Read(CicsResponseCode.NORMAL, 0, record);
    }

    @Override
    public synchronized Result delete(String queue) {
        if (!definitions.containsKey(Objects.requireNonNull(queue, "queue"))) {
            return NO_QUEUE;
        }
        records.get(queue).clear();
        return NORMAL;
    }

    @Override
    public synchronized Result write(CicsTaskId task, Optional<CicsTaskConnection> connection, String queue,
                                     byte[] data) {
        CicsTransientDataQueueDefinition definition = definitions.get(Objects.requireNonNull(queue, "queue"));
        if (definition == null || !recoverable(definition)) {
            return write(queue, data);
        }
        Result length = lengthCondition(definition, data);
        if (length != null) {
            return length;
        }
        pendingOf(task).writes.computeIfAbsent(queue, ignored -> new ArrayList<>()).add(data.clone());
        return NORMAL;
    }

    @Override
    public synchronized Read read(CicsTaskId task, Optional<CicsTaskConnection> connection, String queue) {
        CicsTransientDataQueueDefinition definition = definitions.get(Objects.requireNonNull(queue, "queue"));
        if (definition == null || !recoverable(definition)) {
            return read(queue);
        }
        byte[] record = records.get(queue).pollFirst();
        if (record == null) {
            return new Read(CicsResponseCode.QZERO, 0, null);
        }
        pendingOf(task).reads.computeIfAbsent(queue, ignored -> new ArrayDeque<>()).addLast(record);
        return new Read(CicsResponseCode.NORMAL, 0, record.clone());
    }

    @Override
    public synchronized Result delete(CicsTaskId task, Optional<CicsTaskConnection> connection, String queue) {
        CicsTransientDataQueueDefinition definition = definitions.get(Objects.requireNonNull(queue, "queue"));
        if (definition == null || !recoverable(definition)) {
            return delete(queue);
        }
        Pending changes = pendingOf(task);
        // 消す前に書いた record も一緒に消える
        changes.writes.remove(queue);
        changes.deleted.add(queue);
        return NORMAL;
    }

    @Override
    public synchronized void commitUnitOfWork(CicsTaskId task) {
        Pending changes = pending.remove(Objects.requireNonNull(task, "task"));
        if (changes == null) {
            return;
        }
        changes.deleted.forEach(queue -> records.get(queue).clear());
        changes.writes.forEach((queue, written) -> records.get(queue).addAll(written));
    }

    @Override
    public synchronized void rollbackUnitOfWork(CicsTaskId task) {
        Pending changes = pending.remove(Objects.requireNonNull(task, "task"));
        if (changes == null) {
            return;
        }
        changes.reads.forEach((queue, taken) -> {
            for (Iterator<byte[]> latest = taken.descendingIterator(); latest.hasNext(); ) {
                records.get(queue).addFirst(latest.next());
            }
        });
    }

    private Pending pendingOf(CicsTaskId task) {
        return pending.computeIfAbsent(Objects.requireNonNull(task, "task"), ignored -> new Pending());
    }

    private static boolean recoverable(CicsTransientDataQueueDefinition definition) {
        return definition.recovery() == CicsTransientDataQueueDefinition.Recovery.LOGICAL;
    }

    private static Result lengthCondition(CicsTransientDataQueueDefinition definition, byte[] data) {
        if (data.length < 1 || data.length > definition.maxRecordLength()) {
            // LENGERR: 長さが定義の record の長さと合わない
            return new Result(CicsResponseCode.LENGERR, 0);
        }
        return null;
    }
}
