package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.FileStatus;
import dev.cobolonjava.runtime.file.OpenMode;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.file.SequentialDataSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 区画外のキューを順編成のデータセットに置き、ほかの名前を区画内のキューへ渡す (設計 85 §7.1、暫定判断 P-148)。
 *
 * <p>条件は WRITEQ TD / READQ TD の頁による。INPUT のキューへの WRITEQ と OUTPUT のキューからの READQ は INVREQ、
 * 開けないデータセットは NOTOPEN、RECORDSIZE と合わない長さは LENGERR、終わりまで読めば QZERO。頁は RESP2 を示さないので 0。
 * DELETEQ TD は区画外のキューを消せないと読み、INVREQ にした (推定)。
 *
 * <p>READQ の位置は region の中でキューごとに 1 つ持ち、命令のたびにデータセットを開いて読み進める。ジョブが後から
 * 足した record は、QZERO のあとの READQ で読める。回復と ATI は持たない (TDQUEUE の定義)。
 */
final class CicsExtrapartitionQueues implements CicsTransientDataPort {

    private static final Result NORMAL = new Result(CicsResponseCode.NORMAL, 0);
    private static final Result INVALID = new Result(CicsResponseCode.INVREQ, 0);
    private static final Result NOT_OPEN = new Result(CicsResponseCode.NOTOPEN, 0);

    private final CicsTransientDataPort intrapartition;
    private final Map<String, CicsExtrapartitionQueueDefinition> definitions = new HashMap<>();
    /** INPUT のキューで次に読む record の番号 (0 起点)。 */
    private final Map<String, Integer> positions = new HashMap<>();

    CicsExtrapartitionQueues(CicsTransientDataPort intrapartition, List<CicsExtrapartitionQueueDefinition> queues) {
        this.intrapartition = Objects.requireNonNull(intrapartition, "intrapartition");
        for (CicsExtrapartitionQueueDefinition queue : queues) {
            if (definitions.put(queue.name(), queue) != null) {
                throw new IllegalArgumentException("duplicate transient data queue: " + queue.name());
            }
        }
    }

    @Override
    public Result write(String queue, byte[] data) {
        CicsExtrapartitionQueueDefinition definition = definitions.get(Objects.requireNonNull(queue, "queue"));
        return definition == null ? intrapartition.write(queue, data) : writeExtra(definition, data);
    }

    @Override
    public Read read(String queue) {
        CicsExtrapartitionQueueDefinition definition = definitions.get(Objects.requireNonNull(queue, "queue"));
        return definition == null ? intrapartition.read(queue) : readExtra(definition);
    }

    @Override
    public Result delete(String queue) {
        return definitions.containsKey(Objects.requireNonNull(queue, "queue")) ? INVALID : intrapartition.delete(queue);
    }

    @Override
    public Result write(CicsTaskId task, Optional<CicsTaskConnection> connection, String queue, byte[] data) {
        CicsExtrapartitionQueueDefinition definition = definitions.get(Objects.requireNonNull(queue, "queue"));
        return definition == null ? intrapartition.write(task, connection, queue, data) : writeExtra(definition, data);
    }

    @Override
    public Read read(CicsTaskId task, Optional<CicsTaskConnection> connection, String queue) {
        CicsExtrapartitionQueueDefinition definition = definitions.get(Objects.requireNonNull(queue, "queue"));
        return definition == null ? intrapartition.read(task, connection, queue) : readExtra(definition);
    }

    @Override
    public Result delete(CicsTaskId task, Optional<CicsTaskConnection> connection, String queue) {
        return definitions.containsKey(Objects.requireNonNull(queue, "queue"))
                ? INVALID : intrapartition.delete(task, connection, queue);
    }

    @Override
    public void commitUnitOfWork(CicsTaskId task) {
        intrapartition.commitUnitOfWork(task);
    }

    @Override
    public void rollbackUnitOfWork(CicsTaskId task) {
        intrapartition.rollbackUnitOfWork(task);
    }

    private synchronized Result writeExtra(CicsExtrapartitionQueueDefinition definition, byte[] data) {
        if (definition.direction() == CicsExtrapartitionQueueDefinition.Direction.INPUT) {
            // INVREQ: 入力のために開いた区画外のキューへの WRITEQ
            return INVALID;
        }
        if (data.length < 1 || data.length > definition.recordLength()
                || (!definition.variable() && data.length != definition.recordLength())) {
            // LENGERR: 長さが RECORDSIZE と合わない
            return new Result(CicsResponseCode.LENGERR, 0);
        }
        SequentialDataSet dataSet = SequentialDataSet.at(definition.path(), attributes(definition));
        if (!FileStatus.succeeded(dataSet.open(OpenMode.EXTEND, true))) {
            return NOT_OPEN;
        }
        String written;
        try {
            written = dataSet.write(data);
        } finally {
            dataSet.close();
        }
        return switch (written) {
            case FileStatus.OK -> NORMAL;
            // NOSPACE: 置く場所が無い
            case FileStatus.NO_SPACE -> new Result(CicsResponseCode.NOSPACE, 0);
            default -> new Result(CicsResponseCode.IOERR, 0);
        };
    }

    private synchronized Read readExtra(CicsExtrapartitionQueueDefinition definition) {
        if (definition.direction() == CicsExtrapartitionQueueDefinition.Direction.OUTPUT) {
            // INVREQ: 出力のために開いた区画外のキューからの READQ
            return read(INVALID);
        }
        SequentialDataSet dataSet = SequentialDataSet.at(definition.path(), attributes(definition));
        if (!FileStatus.succeeded(dataSet.open(OpenMode.INPUT, false))) {
            // NOTOPEN: データセットを開けない (キューが閉じている)
            return read(NOT_OPEN);
        }
        try {
            int position = positions.getOrDefault(definition.name(), 0);
            byte[] buffer = new byte[definition.recordLength()];
            for (int index = 0; ; index++) {
                String status = dataSet.read(buffer);
                if (FileStatus.AT_END.equals(status)) {
                    // QZERO: キューの終わりに達した
                    return new Read(CicsResponseCode.QZERO, 0, null);
                }
                if (index < position) {
                    continue;
                }
                positions.put(definition.name(), index + 1);
                if (!FileStatus.OK.equals(status) && !FileStatus.LENGTH_MISMATCH.equals(status)) {
                    // IOERR: 誤りのある record は飛ばす (READQ TD の頁)
                    return read(new Result(CicsResponseCode.IOERR, 0));
                }
                return new Read(CicsResponseCode.NORMAL, 0, Arrays.copyOf(buffer, dataSet.lastLength()));
            }
        } finally {
            dataSet.close();
        }
    }

    private static Read read(Result result) {
        return new Read(result.response(), result.response2(), null);
    }

    private static DataSetAttributes attributes(CicsExtrapartitionQueueDefinition definition) {
        return new DataSetAttributes(definition.variable() ? RecordFormat.VARIABLE : RecordFormat.FIXED,
                definition.recordLength(), definition.codePage());
    }
}
