package dev.cobolonjava.cics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 1 つの JVM の中の一時記憶のキュー (暫定判断 P-137)。 */
final class InMemoryCicsTemporaryStorage implements CicsTemporaryStoragePort {

    private static final Result NORMAL = new Result(CicsResponseCode.NORMAL, 0);
    /** QIDERR: キューが無い。 */
    private static final Result NO_QUEUE = new Result(CicsResponseCode.QIDERR, 0);
    /** ITEMERR: item の番号がキューの範囲の外か、item の数の上限を越える。 */
    private static final Result BAD_ITEM = new Result(CicsResponseCode.ITEMERR, 0);

    private static final class Queue {
        private final List<byte[]> items = new ArrayList<>();
        /** 直前に読まれた item の番号。まだ読まれていなければ 0。task をまたいで 1 つである。 */
        private int cursor;
    }

    private final Map<String, Queue> queues = new HashMap<>();

    @Override
    public synchronized Written write(byte[] name, byte[] data) {
        Result invalid = lengthCondition(data);
        if (invalid != null) {
            return new Written(invalid.response(), invalid.response2(), 0);
        }
        Queue queue = queues.computeIfAbsent(key(name), ignored -> new Queue());
        if (queue.items.size() >= MAX_ITEMS) {
            return new Written(BAD_ITEM.response(), BAD_ITEM.response2(), 0);
        }
        queue.items.add(data.clone());
        return new Written(CicsResponseCode.NORMAL, 0, queue.items.size());
    }

    @Override
    public synchronized Result rewrite(byte[] name, int item, byte[] data) {
        Result invalid = lengthCondition(data);
        if (invalid != null) {
            return invalid;
        }
        Queue queue = queues.get(key(name));
        if (queue == null) {
            return NO_QUEUE;
        }
        if (item < 1 || item > queue.items.size()) {
            return BAD_ITEM;
        }
        queue.items.set(item - 1, data.clone());
        return NORMAL;
    }

    @Override
    public synchronized Read read(byte[] name, int item, boolean next) {
        Queue queue = queues.get(key(name));
        if (queue == null) {
            return new Read(NO_QUEUE.response(), NO_QUEUE.response2(), null, 0);
        }
        // NEXT は「直前に読まれた record の次」である。ITEM で読んだ record も直前に読まれた record に数える
        int wanted = next ? queue.cursor + 1 : item;
        if (wanted < 1 || wanted > queue.items.size()) {
            return new Read(BAD_ITEM.response(), BAD_ITEM.response2(), null, queue.items.size());
        }
        queue.cursor = wanted;
        return new Read(CicsResponseCode.NORMAL, 0, queue.items.get(wanted - 1).clone(), queue.items.size());
    }

    @Override
    public synchronized Result delete(byte[] name) {
        return queues.remove(key(name)) == null ? NO_QUEUE : NORMAL;
    }

    /** LENGERR: 長さが 0 か 32763 を越える。 */
    private static Result lengthCondition(byte[] data) {
        return data.length < 1 || data.length > MAX_ITEM_LENGTH
                ? new Result(CicsResponseCode.LENGERR, 0) : null;
    }

    private static String key(byte[] name) {
        Objects.requireNonNull(name, "name");
        if (name.length != NAME_LENGTH) {
            throw new IllegalArgumentException("temporary storage queue name must be 16 bytes: " + name.length);
        }
        return HexFormat.of().formatHex(name);
    }
}
