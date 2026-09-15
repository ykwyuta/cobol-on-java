package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsResponseCode;
import dev.cobolonjava.cics.CicsTransientDataPort;
import dev.cobolonjava.cics.CicsTransientDataQueueDefinition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 区画内の一時データのキューを {@code COBOL_TD_QUEUE} / {@code COBOL_TD_RECORD} の表に置き、複数の JVM で分け合う
 * (設計 83 §8、暫定判断 P-137・P-144)。
 *
 * <p>TD は回復不能なので、WRITEQ / READQ / DELETEQ TD は task の UOW に入れず、別の transaction で直ちに確定する。
 * 操作の初めにキューの行を UPDATE して lock するので、同じキューの書き込みと読み出しはどの JVM から来ても直列になり、
 * 1 つの record を 2 つの task が読むことは無い。キューの定義は全部の JVM で同じものを渡す。
 * trigger の状態の列は ATI の増分で使う。
 */
public final class JdbcCicsTransientData implements CicsTransientDataPort {

    private static final Result NORMAL = new Result(CicsResponseCode.NORMAL, 0);
    /** QIDERR: キューが定義されていない。 */
    private static final Result NO_QUEUE = new Result(CicsResponseCode.QIDERR, 0);

    private final Map<String, CicsTransientDataQueueDefinition> definitions = new HashMap<>();
    private final Set<String> created = ConcurrentHashMap.newKeySet();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate separate;

    public JdbcCicsTransientData(DataSource dataSource, PlatformTransactionManager transactionManager,
                                 List<CicsTransientDataQueueDefinition> queues) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource"));
        this.separate = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (CicsTransientDataQueueDefinition queue : Objects.requireNonNull(queues, "queues")) {
            if (definitions.put(queue.name(), queue) != null) {
                throw new IllegalArgumentException("duplicate transient data queue: " + queue.name());
            }
        }
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
        });
        return NORMAL;
    }

    private record Record(long sequence, byte[] data) {
    }

    @Override
    public Read read(String queue) {
        if (!definitions.containsKey(Objects.requireNonNull(queue, "queue"))) {
            return new Read(NO_QUEUE.response(), NO_QUEUE.response2(), null);
        }
        ensureQueue(queue);
        return separate.execute(status -> {
            lock(queue, "RECORD_COUNT = RECORD_COUNT");
            List<Record> first = jdbc.query("SELECT SEQUENCE_NO, RECORD_DATA FROM COBOL_TD_RECORD WHERE QUEUE_NAME = ?"
                            + " ORDER BY SEQUENCE_NO FETCH FIRST 1 ROWS ONLY",
                    (row, index) -> new Record(row.getLong(1), row.getBytes(2)), queue);
            if (first.isEmpty()) {
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
}
