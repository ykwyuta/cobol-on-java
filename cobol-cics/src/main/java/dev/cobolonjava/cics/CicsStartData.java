package dev.cobolonjava.cics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    /** START CHANNEL の channel の名前。CHANNEL を書かない START なら null。 */
    private final String channelName;
    /** START を出した時点の channel の container の写し (設計 85 §8)。 */
    private final Map<String, byte[]> containers;
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
                List.of(), null, Map.of());
    }

    private CicsStartData(String requestId, TransId transaction, byte[] data, Optional<String> returnTransaction,
                          Optional<String> returnTerminal, Optional<String> queue, String owner,
                          Optional<String> userId, Optional<String> terminalId, List<CicsStartData> following,
                          String channelName, Map<String, byte[]> containers) {
        if (channelName != null && (channelName.isEmpty() || channelName.length() > 16)) {
            throw new IllegalArgumentException("START CHANNEL must be 1 to 16 characters: " + channelName);
        }
        this.channelName = channelName;
        Map<String, byte[]> copied = new java.util.LinkedHashMap<>();
        Objects.requireNonNull(containers, "containers").forEach((key, value) -> copied.put(key, value.clone()));
        this.containers = copied;
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
                userId, terminalId, later, channelName, containers);
    }

    /** START CHANNEL。channel の container の写しを持たせる (設計 85 §8、暫定判断 P-149)。 */
    public CicsStartData withChannel(String name, Map<String, byte[]> channel) {
        return new CicsStartData(requestId, transaction, data, returnTransaction, returnTerminal, queue, owner,
                userId, terminalId, List.copyOf(following), Objects.requireNonNull(name, "name"), channel);
    }

    /** START CHANNEL の channel の名前。CHANNEL を書かない START なら空。 */
    public Optional<String> channelName() {
        return Optional.ofNullable(channelName);
    }

    /** START CHANNEL の container の写し。 */
    public Map<String, byte[]> containers() {
        Map<String, byte[]> copy = new java.util.LinkedHashMap<>();
        containers.forEach((key, value) -> copy.put(key, value.clone()));
        return copy;
    }

    /** 起こす task の入力。START CHANNEL なら channel を現在の channel にし、ほかは空 (COMMAREA も channel も無い)。 */
    public CicsPayload payload() {
        return channelName == null ? CicsPayload.empty() : new CicsPayload(new byte[0], containers, channelName);
    }

    /** container を置き場の列に置く byte 列にする。数、名前 (UTF-8)、長さ、中身の順。 */
    public byte[] encodedContainers() {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(containers.size());
            for (Map.Entry<String, byte[]> entry : containers.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeInt(entry.getValue().length);
                out.write(entry.getValue());
            }
        } catch (java.io.IOException impossible) {
            throw new java.io.UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    /** {@link #encodedContainers()} の逆。 */
    public static Map<String, byte[]> decodeContainers(byte[] encoded) {
        Map<String, byte[]> decoded = new java.util.LinkedHashMap<>();
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(Objects.requireNonNull(encoded, "encoded")))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                byte[] value = new byte[in.readInt()];
                in.readFully(value);
                decoded.put(name, value);
            }
        } catch (java.io.IOException broken) {
            throw new IllegalArgumentException("START channel data is broken", broken);
        }
        return decoded;
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
