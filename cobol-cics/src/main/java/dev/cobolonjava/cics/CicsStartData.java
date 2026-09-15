package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.Optional;

/**
 * START が起こす task へ渡すもの (暫定判断 P-138)。起こされた task の RETRIEVE が読む。
 *
 * <p>RETRIEVE で読んだかどうかだけは変わる。1 つの START のデータは 1 度しか読めない。
 */
public final class CicsStartData {

    private final String requestId;
    private final TransId transaction;
    private final byte[] data;
    private final Optional<String> returnTransaction;
    private final Optional<String> returnTerminal;
    private final Optional<String> queue;
    private final String owner;
    private final Optional<String> userId;
    private boolean retrieved;

    /**
     * @param requestId         START の REQID (書かなければ CICS が作ったもの)
     * @param transaction       起こす transaction
     * @param data              FROM のデータ。FROM が無ければ null
     * @param returnTransaction RTRANSID
     * @param returnTerminal    RTERMID
     * @param queue             QUEUE
     * @param owner             START を出した task の owner。起こす task もこの owner で動く
     * @param userId            START を出した task の user ID。USERID を書かない START は同じ user ID で動く
     */
    public CicsStartData(String requestId, TransId transaction, byte[] data, Optional<String> returnTransaction,
                         Optional<String> returnTerminal, Optional<String> queue, String owner,
                         Optional<String> userId) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        if (requestId.isEmpty() || requestId.length() > 8 || requestId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("START REQID must be 1 to 8 characters: " + requestId);
        }
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.data = data == null ? null : data.clone();
        this.returnTransaction = Objects.requireNonNull(returnTransaction, "returnTransaction");
        this.returnTerminal = Objects.requireNonNull(returnTerminal, "returnTerminal");
        this.queue = Objects.requireNonNull(queue, "queue");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.userId = Objects.requireNonNull(userId, "userId");
    }

    public String requestId() {
        return requestId;
    }

    public TransId transaction() {
        return transaction;
    }

    /** FROM のデータ。FROM が無ければ空。 */
    public Optional<byte[]> data() {
        return data == null ? Optional.empty() : Optional.of(data.clone());
    }

    public Optional<String> returnTransaction() {
        return returnTransaction;
    }

    public Optional<String> returnTerminal() {
        return returnTerminal;
    }

    public Optional<String> queue() {
        return queue;
    }

    public String owner() {
        return owner;
    }

    public Optional<String> userId() {
        return userId;
    }

    synchronized boolean retrieved() {
        return retrieved;
    }

    synchronized void markRetrieved() {
        retrieved = true;
    }
}
