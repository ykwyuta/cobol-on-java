package dev.cobolonjava.cics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 1 つの JVM の中の区画内の一時データのキュー (暫定判断 P-137)。 */
final class InMemoryCicsTransientData implements CicsTransientDataPort {

    private static final Result NORMAL = new Result(CicsResponseCode.NORMAL, 0);
    /** QIDERR: キューが定義されていない。 */
    private static final Result NO_QUEUE = new Result(CicsResponseCode.QIDERR, 0);

    private final Map<String, CicsTransientDataQueueDefinition> definitions = new HashMap<>();
    private final Map<String, Deque<byte[]>> records = new HashMap<>();

    InMemoryCicsTransientData(List<CicsTransientDataQueueDefinition> queues) {
        for (CicsTransientDataQueueDefinition queue : queues) {
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
        if (data.length < 1 || data.length > definition.maxRecordLength()) {
            // LENGERR: 長さが定義の record の長さと合わない
            return new Result(CicsResponseCode.LENGERR, 0);
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
}
