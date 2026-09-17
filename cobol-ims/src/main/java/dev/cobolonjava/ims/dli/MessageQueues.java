package dev.cobolonjava.ims.dli;

import java.util.ServiceLoader;

/** 差し込まれた電文のキューを引く。 */
public final class MessageQueues {

    private MessageQueues() {
    }

    /**
     * 構成されている差し込みのキューを開く。
     *
     * @return どれも構成されていなければ {@code null} (呼ぶ側はメモリのキューを使う)
     */
    public static MessageQueue open(String transactionCode) {
        for (MessageQueueProvider provider
                : ServiceLoader.load(MessageQueueProvider.class, MessageQueues.class.getClassLoader())) {
            MessageQueue queue = provider.open(transactionCode);
            if (queue != null) {
                return queue;
            }
        }
        return null;
    }
}
