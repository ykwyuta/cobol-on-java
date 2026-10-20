package dev.cobolonjava.cics.tn3270;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 3270 端末との Telnet の接続 (RFC 1576 の TN3270、RFC 2355 の TN3270E)。
 *
 * <p>server は先に TN3270E を申し出る。端末が受ければ装置の型と LU 名を DEVICE-TYPE で取り決め、
 * 記録ごとに 5 byte の header を付ける。断れば terminal type、binary、end-of-record を取り決める
 * 素の TN3270 にする。どちらも記録は IAC EOR で終わり、データの X'FF' は IAC IAC で送る。
 *
 * <p>TN3270E の FUNCTIONS は 1 つも受けない (BIND-IMAGE、RESPONSES、SYSREQ、SCS-CTL-CODES、
 * DATA-STREAM-CTL)。端末の求めたものと違うので REQUEST で空の一覧を返し、端末に IS で同意させる。
 * 受けないので、3270 のデータの記録だけが流れる (暫定判断 P-191)。
 *
 * <p>読み書きは 1 つの thread からだけ行う。読みが時間切れになっても、途中まで読んだ記録は失われない。
 */
final class Tn3270Connection implements AutoCloseable {

    static final int IAC = 255;
    static final int DONT = 254;
    static final int DO = 253;
    static final int WONT = 252;
    static final int WILL = 251;
    static final int SB = 250;
    static final int SE = 240;
    static final int EOR = 239;

    static final int OPT_BINARY = 0;
    static final int OPT_TTYPE = 24;
    static final int OPT_EOR = 25;
    static final int OPT_TN3270E = 40;

    private static final int TTYPE_IS = 0;
    private static final int TTYPE_SEND = 1;

    // TN3270E の副取り決め (RFC 2355)
    static final int E_ASSOCIATE = 0;
    static final int E_CONNECT = 1;
    static final int E_DEVICE_TYPE = 2;
    static final int E_FUNCTIONS = 3;
    static final int E_IS = 4;
    static final int E_REASON = 5;
    static final int E_REJECT = 6;
    static final int E_REQUEST = 7;
    static final int E_SEND = 8;

    /** DEVICE-TYPE REJECT の理由。 */
    static final int REASON_DEVICE_IN_USE = 1;
    static final int REASON_INV_NAME = 3;
    static final int REASON_INV_DEVICE_TYPE = 4;
    static final int REASON_UNSUPPORTED_REQ = 7;

    /** TN3270E の header の data-type。 */
    private static final int E_3270_DATA = 0;

    /** 端末から届いたもの。 */
    sealed interface Event permits Verb, Subnegotiation, Record {
    }

    /** WILL / WONT / DO / DONT。 */
    record Verb(int command, int option) implements Event {
    }

    /** IAC SB ... IAC SE の中身 (option の byte から)。 */
    record Subnegotiation(byte[] data) implements Event {
    }

    /** IAC EOR で終わった 3270 のデータの記録。TN3270E の header は外してある。 */
    record Record(byte[] data) implements Event {
    }

    /** 端末に割り当てる LU。{@link LuAssigner} が返す。 */
    record Assignment(String luName, String terminalId) {
    }

    /** LU を断った。{@code reason} は DEVICE-TYPE REJECT の理由。 */
    static final class LuRejected extends Exception {

        private final int reason;

        LuRejected(int reason, String message) {
            super(message);
            this.reason = reason;
        }

        int reason() {
            return reason;
        }
    }

    /** 端末が求めた LU 名 (無ければ空) から LU と端末の名前を決める。 */
    interface LuAssigner {

        Assignment assign(Optional<String> requested) throws LuRejected;
    }

    /** 取り決めの結果。 */
    record Negotiated(Tn3270Device device, boolean tn3270e, Assignment assignment) {
    }

    private enum State {
        DATA, IAC, VERB, SB, SB_IAC
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final ByteArrayOutputStream record = new ByteArrayOutputStream();
    private final ByteArrayOutputStream sub = new ByteArrayOutputStream();
    private State state = State.DATA;
    private int verb;
    private boolean tn3270e;
    private int sequence;

    Tn3270Connection(Socket socket) throws IOException {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /**
     * 端末と取り決める。
     *
     * @throws IOException 端末が 3270 の取り決めに応じない、時間内に終わらない、接続が切れた
     */
    Negotiated negotiate(Duration timeout, LuAssigner assigner) throws IOException {
        Instant deadline = Instant.now().plus(timeout);
        send(IAC, DO, OPT_TN3270E);
        Verb answer = awaitVerb(deadline, OPT_TN3270E);
        if (answer.command() == WILL) {
            tn3270e = true;
            return negotiateTn3270e(deadline, assigner);
        }
        return negotiateTn3270(deadline, assigner);
    }

    private Negotiated negotiateTn3270e(Instant deadline, LuAssigner assigner) throws IOException {
        send(IAC, SB, OPT_TN3270E, E_SEND, E_DEVICE_TYPE, IAC, SE);
        while (true) {
            byte[] request = awaitSub(deadline, OPT_TN3270E, E_DEVICE_TYPE, E_REQUEST);
            int end = 3;
            while (end < request.length && request[end] != E_CONNECT && request[end] != E_ASSOCIATE) {
                end++;
            }
            String type = ascii(request, 3, end);
            if (end < request.length && request[end] == E_ASSOCIATE) {
                // 印刷装置の対応付け。印刷装置は受けない
                reject(REASON_UNSUPPORTED_REQ);
                continue;
            }
            Optional<String> lu = end < request.length
                    ? Optional.of(ascii(request, end + 1, request.length)) : Optional.empty();
            Optional<Tn3270Device> device = Tn3270Device.of(type);
            if (device.isEmpty()) {
                reject(REASON_INV_DEVICE_TYPE);
                continue;
            }
            Assignment assignment;
            try {
                assignment = assigner.assign(lu);
            } catch (LuRejected rejected) {
                reject(rejected.reason());
                continue;
            }
            ByteArrayOutputStream is = new ByteArrayOutputStream();
            is.writeBytes(new byte[] {(byte) IAC, (byte) SB, OPT_TN3270E, E_DEVICE_TYPE, E_IS});
            is.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
            is.write(E_CONNECT);
            is.writeBytes(assignment.luName().getBytes(StandardCharsets.US_ASCII));
            is.writeBytes(new byte[] {(byte) IAC, (byte) SE});
            out.write(is.toByteArray());
            out.flush();
            negotiateFunctions(deadline);
            return new Negotiated(device.orElseThrow(), true, assignment);
        }
    }

    /** FUNCTIONS は 1 つも受けない。端末が空でない一覧を求めれば、空の一覧を求め返して IS を待つ。 */
    private void negotiateFunctions(Instant deadline) throws IOException {
        byte[] request = awaitSub(deadline, OPT_TN3270E, E_FUNCTIONS, E_REQUEST);
        if (request.length == 3) {
            send(IAC, SB, OPT_TN3270E, E_FUNCTIONS, E_IS, IAC, SE);
            return;
        }
        send(IAC, SB, OPT_TN3270E, E_FUNCTIONS, E_REQUEST, IAC, SE);
        byte[] agreed = awaitSub(deadline, OPT_TN3270E, E_FUNCTIONS, E_IS);
        if (agreed.length != 3) {
            throw new IOException("the client insisted on TN3270E functions the server does not offer");
        }
    }

    private void reject(int reason) throws IOException {
        send(IAC, SB, OPT_TN3270E, E_DEVICE_TYPE, E_REJECT, E_REASON, reason, IAC, SE);
    }

    private Negotiated negotiateTn3270(Instant deadline, LuAssigner assigner) throws IOException {
        send(IAC, DO, OPT_TTYPE);
        if (awaitVerb(deadline, OPT_TTYPE).command() != WILL) {
            throw new IOException("the client refused to send its terminal type");
        }
        send(IAC, SB, OPT_TTYPE, TTYPE_SEND, IAC, SE);
        byte[] is = awaitSub(deadline, OPT_TTYPE, TTYPE_IS);
        String type = ascii(is, 2, is.length);
        // RFC 1646 の形 (型@LU 名) なら LU 名を求めている
        Optional<String> lu = Optional.empty();
        int at = type.indexOf('@');
        if (at >= 0) {
            lu = Optional.of(type.substring(at + 1));
            type = type.substring(0, at);
        }
        Tn3270Device device = Tn3270Device.of(type)
                .orElseThrow(() -> new IOException("not a 3270 display terminal type"));
        Assignment assignment;
        try {
            assignment = assigner.assign(lu);
        } catch (LuRejected rejected) {
            throw new IOException(rejected.getMessage());
        }
        send(IAC, DO, OPT_EOR, IAC, WILL, OPT_EOR, IAC, DO, OPT_BINARY, IAC, WILL, OPT_BINARY);
        Set<String> pending = new HashSet<>(Set.of("WILL EOR", "DO EOR", "WILL BINARY", "DO BINARY"));
        while (!pending.isEmpty()) {
            Event event = awaitEvent(deadline);
            if (event instanceof Verb answer && (answer.option() == OPT_EOR || answer.option() == OPT_BINARY)) {
                String name = answer.option() == OPT_EOR ? "EOR" : "BINARY";
                if (answer.command() == WONT || answer.command() == DONT) {
                    throw new IOException("the client refused " + name);
                }
                pending.remove((answer.command() == WILL ? "WILL " : "DO ") + name);
            }
        }
        return new Negotiated(device, false, assignment);
    }

    /** 3270 のデータの記録を 1 つ送る。TN3270E なら header を付け、X'FF' を重ね、IAC EOR で閉じる。 */
    void writeRecord(byte[] data) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(data.length + 16);
        if (tn3270e) {
            int number = sequence++ & 0x7FFF;
            byte[] header = {E_3270_DATA, 0, 0, (byte) (number >> 8), (byte) number};
            escape(frame, header);
        }
        escape(frame, data);
        frame.write(IAC);
        frame.write(EOR);
        out.write(frame.toByteArray());
        out.flush();
    }

    private static void escape(ByteArrayOutputStream frame, byte[] data) {
        for (byte b : data) {
            frame.write(b);
            if ((b & 0xFF) == IAC) {
                frame.write(IAC);
            }
        }
    }

    /**
     * 次の 3270 の記録を読む。取り決めの済んだあとに端末が出す WILL / DO には、こちらが求めたもの以外を断って返す。
     *
     * @return 記録。接続が閉じたら空
     * @throws SocketTimeoutException 時間切れ。読みかけの記録は保たれる
     */
    Optional<byte[]> readRecord() throws IOException {
        while (true) {
            Event event;
            try {
                event = next();
            } catch (EOFException closed) {
                return Optional.empty();
            }
            if (event instanceof Record found) {
                return Optional.of(found.data());
            }
            if (event instanceof Verb offered) {
                refuseUnknown(offered);
            }
            // 取り決めの済んだあとの副取り決めは使わない
        }
    }

    private void refuseUnknown(Verb offered) throws IOException {
        boolean ours = offered.option() == OPT_BINARY || offered.option() == OPT_EOR
                || offered.option() == OPT_TTYPE || offered.option() == OPT_TN3270E;
        if (ours) {
            return;
        }
        if (offered.command() == WILL) {
            send(IAC, DONT, offered.option());
        } else if (offered.command() == DO) {
            send(IAC, WONT, offered.option());
        }
    }

    void setReadTimeout(Duration timeout) throws IOException {
        socket.setSoTimeout((int) Math.max(1, timeout.toMillis()));
    }

    private Verb awaitVerb(Instant deadline, int option) throws IOException {
        while (true) {
            Event event = awaitEvent(deadline);
            if (event instanceof Verb verb && verb.option() == option) {
                return verb;
            }
            if (event instanceof Verb other) {
                refuseUnknown(other);
            }
        }
    }

    private byte[] awaitSub(Instant deadline, int... prefix) throws IOException {
        while (true) {
            Event event = awaitEvent(deadline);
            if (event instanceof Subnegotiation found && startsWith(found.data(), prefix)) {
                return found.data();
            }
            if (event instanceof Verb other) {
                refuseUnknown(other);
            }
        }
    }

    private Event awaitEvent(Instant deadline) throws IOException {
        while (true) {
            Duration left = Duration.between(Instant.now(), deadline);
            if (left.isNegative() || left.isZero()) {
                throw new IOException("the client did not complete the TN3270 negotiation in time");
            }
            setReadTimeout(left);
            try {
                return next();
            } catch (SocketTimeoutException ignored) {
                // 期限まで読み直す
            }
        }
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** byte を 1 つずつ読んで次のものを返す。状態は field に置くので、時間切れで途中のものは失われない。 */
    private Event next() throws IOException {
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("the client closed the connection");
            }
            switch (state) {
                case DATA -> {
                    if (b == IAC) {
                        state = State.IAC;
                    } else {
                        record.write(b);
                    }
                }
                case IAC -> {
                    state = State.DATA;
                    switch (b) {
                        case IAC -> record.write(IAC);
                        case EOR -> {
                            byte[] data = record.toByteArray();
                            record.reset();
                            Optional<byte[]> payload = payload(data);
                            if (payload.isPresent()) {
                                return new Record(payload.orElseThrow());
                            }
                        }
                        case WILL, WONT, DO, DONT -> {
                            verb = b;
                            state = State.VERB;
                        }
                        case SB -> {
                            sub.reset();
                            state = State.SB;
                        }
                        default -> {
                            // NOP、AYT、IP、BREAK などは使わない
                        }
                    }
                }
                case VERB -> {
                    state = State.DATA;
                    return new Verb(verb, b);
                }
                case SB -> {
                    if (b == IAC) {
                        state = State.SB_IAC;
                    } else {
                        sub.write(b);
                    }
                }
                case SB_IAC -> {
                    if (b == SE) {
                        state = State.DATA;
                        return new Subnegotiation(sub.toByteArray());
                    }
                    sub.write(b);
                    state = State.SB;
                }
            }
        }
    }

    /** TN3270E の header を外す。3270 のデータ以外の記録 (SSCP-LU、応答など) は使わないので空。 */
    private Optional<byte[]> payload(byte[] data) {
        if (!tn3270e) {
            return Optional.of(data);
        }
        if (data.length < 5 || data[0] != E_3270_DATA) {
            return Optional.empty();
        }
        return Optional.of(Arrays.copyOfRange(data, 5, data.length));
    }

    private void send(int... bytes) throws IOException {
        byte[] frame = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            frame[i] = (byte) bytes[i];
        }
        out.write(frame);
        out.flush();
    }

    private static String ascii(byte[] data, int from, int to) {
        return new String(data, from, Math.max(0, to - from), StandardCharsets.US_ASCII);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
