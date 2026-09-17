package dev.cobolonjava.ims.dli;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** 1 つの JVM の中に持つメッセージキュー。試験と測定で使う。 */
public final class InMemoryMessageQueue implements MessageQueue {

    private final Deque<InputMessage> input = new ArrayDeque<>();
    private final List<OutputMessage> output = new ArrayList<>();

    /** 入力の電文を後ろに積む。 */
    public synchronized InMemoryMessageQueue offer(InputMessage message) {
        input.addLast(Objects.requireNonNull(message, "message"));
        return this;
    }

    @Override
    public synchronized boolean enqueue(InputMessage message) {
        offer(message);
        return true;
    }

    @Override
    public synchronized InputMessage next() {
        return input.pollFirst();
    }

    @Override
    public synchronized void send(OutputMessage message) {
        output.add(Objects.requireNonNull(message, "message"));
    }

    /** 送られた応答を送られた順に返す。 */
    public synchronized List<OutputMessage> sent() {
        return List.copyOf(output);
    }
}
