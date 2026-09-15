package dev.cobolonjava.cics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * START が起こす task へ渡すもの (暫定判断 P-138、設計 83 §5)。起こされた task の RETRIEVE が読む。
 *
 * <p>RETRIEVE で読んだかどうかだけは変わる。1 つの START のデータは 1 度しか読めない。
 *
 * <p>端末へ出す START (TERMID) は、同じ端末と TRANSID の満了した START をまとめて 1 つの task にする。task を起こした
 * START の後ろに {@link #following()} が満了の順に並び、RETRIEVE はこの順に読む (RETRIEVE の頁)。
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
    private final Optional<String> terminalId;
    /** RETRIEVE WAIT が受け取った START を足すので、task の中だけで伸びる。 */
    private final java.util.concurrent.CopyOnWriteArrayList<CicsStartData> following;
    private boolean retrieved;

    /**
     * 端末の無い START。
     *
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
        this(requestId, transaction, data, returnTransaction, returnTerminal, queue, owner, userId, Optional.empty());
    }

    /**
     * @param terminalId TERMID。起こす task の principal facility になる端末
     */
    public CicsStartData(String requestId, TransId transaction, byte[] data, Optional<String> returnTransaction,
                         Optional<String> returnTerminal, Optional<String> queue, String owner,
                         Optional<String> userId, Optional<String> terminalId) {
        this(requestId, transaction, data, returnTransaction, returnTerminal, queue, owner, userId, terminalId,
                List.of());
    }

    private CicsStartData(String requestId, TransId transaction, byte[] data, Optional<String> returnTransaction,
                          Optional<String> returnTerminal, Optional<String> queue, String owner,
                          Optional<String> userId, Optional<String> terminalId, List<CicsStartData> following) {
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
        this.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        terminalId.ifPresent(value -> {
            if (value.isEmpty() || value.length() > 4) {
                throw new IllegalArgumentException("START TERMID must be 1 to 4 characters: " + value);
            }
        });
        this.following = new java.util.concurrent.CopyOnWriteArrayList<>(
                Objects.requireNonNull(following, "following"));
    }

    /**
     * 同じ端末と TRANSID の、この START のあとに満了した START を後ろに並べる。
     *
     * @throws IllegalArgumentException 端末か TRANSID が違う START が混じっている
     */
    public CicsStartData withFollowing(List<CicsStartData> later) {
        requireSameTarget(later);
        return new CicsStartData(requestId, transaction, data, returnTransaction, returnTerminal, queue, owner,
                userId, terminalId, later);
    }

    /** RETRIEVE WAIT が受け取った、あとに満了した START を後ろに足す。 */
    void append(List<CicsStartData> later) {
        requireSameTarget(later);
        following.addAll(later);
    }

    private void requireSameTarget(List<CicsStartData> later) {
        for (CicsStartData start : later) {
            if (!start.transaction.equals(transaction) || !start.terminalId.equals(terminalId)) {
                throw new IllegalArgumentException("following START must have the same TRANSID and TERMID");
            }
        }
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

    /** TERMID。端末の無い START なら空。 */
    public Optional<String> terminalId() {
        return terminalId;
    }

    /** task を起こした START のあとに満了した、同じ端末と TRANSID の START。 */
    public List<CicsStartData> following() {
        return List.copyOf(following);
    }

    /** RETRIEVE が読む順。task を起こした START、続いて {@link #following()}。 */
    List<CicsStartData> sequence() {
        List<CicsStartData> all = new ArrayList<>(following.size() + 1);
        all.add(this);
        all.addAll(following);
        return all;
    }

    synchronized boolean retrieved() {
        return retrieved;
    }

    synchronized void markRetrieved() {
        retrieved = true;
    }
}
