package dev.cobolonjava.cics.tn3270;

import dev.cobolonjava.cics.CicsAbend;
import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskCoordinator;
import dev.cobolonjava.cics.CicsTaskReply;
import dev.cobolonjava.cics.CicsTaskRequest;
import dev.cobolonjava.cics.CicsTerminalRegistryPort;
import dev.cobolonjava.cics.CicsTerminalRegistryPort.Terminal;
import dev.cobolonjava.cics.CicsTerminalRegistryPort.TerminalConversation;
import dev.cobolonjava.cics.CicsTerminalRegistryPort.TerminalLease;
import dev.cobolonjava.cics.CicsTerminalRegistryPort.TerminalScreen;
import dev.cobolonjava.cics.CicsTerminalScreen;
import dev.cobolonjava.cics.CicsTerminalTasks;
import dev.cobolonjava.cics.ConversationEnvelope;
import dev.cobolonjava.cics.ConversationReference;
import dev.cobolonjava.cics.ConversationStorePort;
import dev.cobolonjava.cics.DisabledTransactionException;
import dev.cobolonjava.cics.IdempotencyKey;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.cics.TransactionNotAuthorizedException;
import dev.cobolonjava.cics.UnknownTransactionException;
import dev.cobolonjava.cics.bms.BmsAid;
import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot.FieldState;
import dev.cobolonjava.cics.bms.BmsTerminalInput;
import dev.cobolonjava.runtime.codepage.CodePage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 3270 端末を Telnet で受ける CICS の端末の入口 (設計 86)。Spring にも servlet にも依らず、JDK だけで動く。
 *
 * <p>ホストの CICS と同じ形で端末を動かす:
 * <ol>
 *   <li>端末がつながると、書式のない空の画面を送ってキーボードを開ける</li>
 *   <li>端末が疑似会話の途中でなければ、送られたデータの先頭の語 (4 文字まで) を TRANSID として task を起こす</li>
 *   <li>task が {@code RETURN TRANSID} で終われば、次の入力でその TRANSID の task を起こす。入力は直前に送った
 *       BMS の画面と照合して field の名前に戻し、RECEIVE MAP が読む</li>
 *   <li>task が送った画面は 3270 のデータストリーム ({@link Tn3270DataStream}) にして送る</li>
 * </ol>
 *
 * <p>端末は {@link CicsTerminalRegistryPort} に登録し、task の前に lease する。START TERMID や ATI の task が端末へ
 * 置いた画面は、入力を待つ間に版の変化で見つけて送る (設計 83 §7)。
 *
 * <p>領域が出す通知 (TRANSID が無い、ABEND した) は CICS の DFHAC の文面に合わせた。<b>実機の画面と
 * 突き合わせていない</b> (暫定判断 P-191)。
 */
public final class Tn3270Server implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Tn3270Server.class.getName());

    /** 入力を伴わない AID。端末は AID だけを送る。 */
    private static final Set<BmsAid> SHORT_READ = Set.of(
            BmsAid.CLEAR, BmsAid.CLEAR_PARTITION, BmsAid.PA1, BmsAid.PA2, BmsAid.PA3);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yy", Locale.ROOT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    /**
     * 入口の構成。
     *
     * @param applid           通知に出す領域の APPLID (8 文字まで)
     * @param defaultUserId    sign on していない端末の task の user ID (CICS の DFLTUSER)。空なら user ID を渡さない
     * @param leaseDuration    1 つの入力で動く task (IMMEDIATE の連鎖を含む) の端末の lease の長さ
     * @param terminalLifetime 接続している端末の登録を延ばす長さ。入力を待つ間に延ばし続ける
     * @param negotiation      Telnet の取り決めを終えるまでの時間
     * @param poll             入力を待つ間に、端末へ出す task の画面を見る間隔
     * @param zone             通知の日時の時間帯
     */
    public record Options(String applid, Optional<String> defaultUserId, Duration leaseDuration,
                          Duration terminalLifetime, Duration negotiation, Duration poll, ZoneId zone) {

        public Options {
            Objects.requireNonNull(applid, "applid");
            if (!applid.matches("[A-Z0-9@#$]{1,8}")) {
                throw new IllegalArgumentException("APPLID must be 1 to 8 characters: " + applid);
            }
            Objects.requireNonNull(defaultUserId, "defaultUserId");
            Objects.requireNonNull(leaseDuration, "leaseDuration");
            Objects.requireNonNull(terminalLifetime, "terminalLifetime");
            Objects.requireNonNull(negotiation, "negotiation");
            Objects.requireNonNull(poll, "poll");
            Objects.requireNonNull(zone, "zone");
        }

        public static Options defaults() {
            return new Options("COBOLJVM", Optional.empty(), Duration.ofMinutes(5), Duration.ofMinutes(30),
                    Duration.ofSeconds(10), Duration.ofMillis(500), ZoneId.systemDefault());
        }

        public Options withApplid(String value) {
            return new Options(value, defaultUserId, leaseDuration, terminalLifetime, negotiation, poll, zone);
        }

        public Options withDefaultUserId(Optional<String> value) {
            return new Options(applid, value, leaseDuration, terminalLifetime, negotiation, poll, zone);
        }

        public Options withLeaseDuration(Duration value) {
            return new Options(applid, defaultUserId, value, terminalLifetime, negotiation, poll, zone);
        }

        public Options withZone(ZoneId value) {
            return new Options(applid, defaultUserId, leaseDuration, terminalLifetime, negotiation, poll, value);
        }
    }

    private final CicsTaskCoordinator coordinator;
    private final ConversationStorePort conversations;
    private final CicsTerminalRegistryPort terminals;
    private final CodePage codePage;
    private final Options options;
    private final Clock clock;
    private final ExecutorService sessions = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> open = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private ServerSocket listener;
    private Thread acceptor;

    /**
     * @param codePage 端末のコードページ。<b>実行時のコードページと同じでなければならない</b>
     *                 (RECEIVE MAP が field を符号化するのと同じ表で画面を書く)
     */
    public Tn3270Server(CicsTaskCoordinator coordinator, ConversationStorePort conversations,
                        CicsTerminalRegistryPort terminals, CodePage codePage, Options options, Clock clock) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.terminals = Objects.requireNonNull(terminals, "terminals");
        this.codePage = Objects.requireNonNull(codePage, "codePage");
        this.options = Objects.requireNonNull(options, "options");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 受け始める。port が 0 なら空いている port を使い、{@link #port()} で分かる。 */
    public synchronized void start(InetSocketAddress address) throws IOException {
        if (listener != null) {
            throw new IllegalStateException("the TN3270 server is already started");
        }
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(address);
        ServerSocket bound = listener;
        acceptor = Thread.ofPlatform().name("tn3270-accept").daemon(true).start(() -> accept(bound));
        LOG.log(System.Logger.Level.INFO, "TN3270 server {0} listening on {1}", options.applid(),
                bound.getLocalSocketAddress());
    }

    public synchronized int port() {
        if (listener == null) {
            throw new IllegalStateException("the TN3270 server is not started");
        }
        return listener.getLocalPort();
    }

    private void accept(ServerSocket bound) {
        while (!bound.isClosed()) {
            Socket socket;
            try {
                socket = bound.accept();
            } catch (IOException closed) {
                return;
            }
            open.add(socket);
            sessions.submit(() -> {
                try {
                    new Session(socket).run();
                } finally {
                    open.remove(socket);
                }
            });
        }
    }

    /** 受けるのをやめ、つながっている端末を切る。 */
    @Override
    public synchronized void close() throws IOException {
        if (listener != null) {
            listener.close();
        }
        for (Socket socket : List.copyOf(open)) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 閉じられなくても続ける
            }
        }
        sessions.shutdown();
    }

    /** 端末 1 台との接続。1 つの virtual thread が読み書きと task の起動を順に行う。 */
    private final class Session {

        private final Socket socket;
        private Tn3270Connection connection;
        private Tn3270Device device;
        private String terminalId;
        private String owner;
        /** 端末に今出ている BMS の画面。書式のない画面なら空。入力の address を field の名前に戻すのに使う。 */
        private Optional<BmsScreenSnapshot> onScreen = Optional.empty();
        /** 端末へ出す task の画面のうち、送った最後の版。 */
        private long screenVersion;

        Session(Socket socket) {
            this.socket = socket;
        }

        void run() {
            try (Tn3270Connection opened = new Tn3270Connection(socket)) {
                connection = opened;
                Tn3270Connection.Negotiated negotiated = connection.negotiate(options.negotiation(), this::assign);
                device = negotiated.device();
                LOG.log(System.Logger.Level.INFO, "terminal {0} connected ({1}, {2}, LU {3})", terminalId,
                        device.name(), negotiated.tn3270e() ? "TN3270E" : "TN3270",
                        negotiated.assignment().luName());
                screenVersion = terminals.find(terminalId, clock.instant()).map(Terminal::screenVersion).orElse(0L);
                connection.writeRecord(Tn3270DataStream.text("", true, false, device, codePage));
                connection.setReadTimeout(options.poll());
                while (true) {
                    Optional<byte[]> record;
                    try {
                        record = connection.readRecord();
                    } catch (SocketTimeoutException idle) {
                        idle();
                        continue;
                    }
                    if (record.isEmpty()) {
                        break;
                    }
                    if (record.orElseThrow().length > 0) {
                        receive(Tn3270DataStream.inbound(record.orElseThrow()));
                    }
                }
            } catch (SocketException closed) {
                // 端末が切れたか、server を止めた
            } catch (IOException | RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "terminal " + terminalId + " failed", failure);
            } finally {
                if (terminalId != null) {
                    terminals.remove(terminalId, clock.instant());
                    LOG.log(System.Logger.Level.INFO, "terminal {0} disconnected", terminalId);
                }
            }
        }

        /**
         * LU と端末の名前を決めて端末を登録する。
         *
         * <p>LU 名を求められれば、端末の名前は LU 名の末尾 4 文字とする。CICS の自動導入の既定の program が
         * NETNAME から TERMID を作る規則と同じである。求められなければ、登録が選んだ名前を LU 名にも使う。
         */
        private Tn3270Connection.Assignment assign(Optional<String> requested)
                throws Tn3270Connection.LuRejected {
            Instant now = clock.instant();
            Instant expiresAt = now.plus(options.terminalLifetime());
            if (requested.isPresent()) {
                String lu = requested.orElseThrow().toUpperCase(Locale.ROOT);
                if (!lu.matches("[A-Z@#$][A-Z0-9@#$]{0,7}")) {
                    throw new Tn3270Connection.LuRejected(Tn3270Connection.REASON_INV_NAME, "invalid LU name");
                }
                String id = lu.substring(Math.max(0, lu.length() - 4));
                String claimant = "tn3270:" + lu;
                if (!terminals.registerNamed(id, claimant, expiresAt, now)) {
                    throw new Tn3270Connection.LuRejected(Tn3270Connection.REASON_DEVICE_IN_USE,
                            "terminal " + id + " is in use");
                }
                // 同じ LU で 2 つ目の接続が来ても、同じ owner の登録は延ばされるだけなので断れない。
                // 1 つの LU に 2 台の端末をつながないのは利用者の構成に任せる
                terminalId = id;
                owner = claimant;
                return new Tn3270Connection.Assignment(lu, id);
            }
            String claimant = "tn3270:" + UUID.randomUUID();
            String id = terminals.register(claimant, expiresAt, now);
            terminalId = id;
            owner = claimant;
            return new Tn3270Connection.Assignment(id, id);
        }

        /** 入力を待つ間。端末の登録を延ばし、端末へ出す task が置いた画面があれば送る。 */
        private void idle() throws IOException {
            Instant now = clock.instant();
            terminals.touch(terminalId, owner, now.plus(options.terminalLifetime()), now);
            Optional<TerminalScreen> current = terminals.screen(terminalId, now);
            if (current.isPresent() && current.orElseThrow().version() > screenVersion) {
                screenVersion = current.orElseThrow().version();
                paint(current.orElseThrow().screen());
            }
        }

        private void receive(Tn3270DataStream.Inbound inbound) throws IOException {
            Optional<BmsAid> aid = inbound.bmsAid();
            if (aid.isEmpty()) {
                // 構造化 field の応答などは使わない
                connection.writeRecord(Tn3270DataStream.restoreKeyboard(false));
                return;
            }
            if (aid.orElseThrow() == BmsAid.CLEAR) {
                // CLEAR は端末の側で画面を消し、既定の大きさにする
                onScreen = Optional.empty();
            }
            Instant now = clock.instant();
            TerminalLease lease = terminals.lease(terminalId, owner, options.leaseDuration(), now).orElse(null);
            if (lease == null) {
                // 端末へ出す task が動いている。入力は動かさない
                connection.writeRecord(Tn3270DataStream.restoreKeyboard(true));
                return;
            }
            Optional<TransId> transaction = Optional.empty();
            try {
                Terminal terminal = terminals.find(terminalId, now)
                        .orElseThrow(() -> new IllegalStateException("the leased terminal is no longer registered"));
                Optional<TerminalConversation> conversation = terminal.conversation();
                Optional<ConversationEnvelope> envelope = conversation.flatMap(current ->
                        conversations.load(current.id(), now).filter(loaded -> loaded.version() == current.version()));
                BmsTerminalInput input = input(aid.orElseThrow(), inbound);
                if (envelope.isPresent()) {
                    TerminalConversation current = conversation.orElseThrow();
                    transaction = Optional.of(current.nextTransaction());
                    launch(lease, current.nextTransaction(), envelope.orElseThrow().payload(),
                            Optional.of(new ConversationReference(current.id(), current.version())), input);
                    return;
                }
                if (conversation.isPresent()) {
                    // 会話が期限で消えた。端末は疑似会話の途中ではない
                    requireConversation(lease, Optional.empty());
                }
                if (SHORT_READ.contains(aid.orElseThrow())) {
                    connection.writeRecord(Tn3270DataStream.restoreKeyboard(false));
                    return;
                }
                Optional<String> word = firstWord(inbound);
                if (word.isEmpty()) {
                    connection.writeRecord(Tn3270DataStream.restoreKeyboard(false));
                    return;
                }
                TransId requested;
                try {
                    requested = TransId.of(word.orElseThrow());
                } catch (IllegalArgumentException invalid) {
                    notice("DFHAC2001 " + stamp() + " " + options.applid() + " Transaction '"
                            + word.orElseThrow() + "' is not recognized. Check that the transaction name is correct.");
                    return;
                }
                transaction = Optional.of(requested);
                launch(lease, requested, CicsPayload.empty(), Optional.empty(), input);
            } catch (UnknownTransactionException unknown) {
                notice("DFHAC2001 " + stamp() + " " + options.applid() + " Transaction '"
                        + transaction.map(TransId::value).orElse("") + "' is not recognized."
                        + " Check that the transaction name is correct.");
            } catch (DisabledTransactionException disabled) {
                notice("DFHAC2008 " + stamp() + " " + options.applid() + " Transaction "
                        + transaction.map(TransId::value).orElse("") + " has been disabled and cannot be used.");
            } catch (TransactionNotAuthorizedException denied) {
                notice("DFHAC2033 " + stamp() + " " + options.applid() + " You are not authorized to use"
                        + " transaction " + transaction.map(TransId::value).orElse("") + ".");
            } catch (CicsAbend abend) {
                abandon(lease);
                notice("DFHAC2206 " + TIME.format(clock.instant().atZone(options.zone())) + " " + options.applid()
                        + " Transaction " + transaction.map(TransId::value).orElse("") + " failed with abend "
                        + abend.code().value() + ". Updates to local recoverable resources backed out.");
            } catch (RuntimeException failure) {
                abandon(lease);
                LOG.log(System.Logger.Level.WARNING, "task on terminal " + terminalId + " failed", failure);
                // 原因の文面は端末に出さない。ABEND の code を持たない失敗なので DFHAC の番号も使わない
                notice("COJTN001 " + stamp() + " " + options.applid() + " Transaction "
                        + transaction.map(TransId::value).orElse("") + " could not be completed.");
            } finally {
                terminals.release(lease, clock.instant());
            }
        }

        /** task を起こし、IMMEDIATE なら端末の入力なしで続け、最後の画面を送る。 */
        private void launch(TerminalLease lease, TransId transaction, CicsPayload payload,
                            Optional<ConversationReference> reference, BmsTerminalInput input) throws IOException {
            Optional<String> userId = options.defaultUserId();
            CicsTaskReply reply = coordinator.launch(new CicsTaskRequest(transaction.value(), owner, payload,
                    reference, key(), Optional.of(input), Optional.of(terminalId), userId));
            Optional<CicsTerminalScreen> screen = reply.screen();
            for (int step = 0; reply.immediateNext(); step++) {
                if (step >= CicsTerminalTasks.MAX_IMMEDIATE) {
                    throw new IllegalStateException("RETURN IMMEDIATE chain exceeded "
                            + CicsTerminalTasks.MAX_IMMEDIATE + " tasks");
                }
                ConversationEnvelope next = reply.nextConversation().orElseThrow();
                reply = coordinator.launch(new CicsTaskRequest(next.nextTransaction().value(), owner,
                        next.payload(), Optional.of(new ConversationReference(next.id(), next.version())), key(),
                        Optional.empty(), Optional.of(terminalId), userId));
                if (reply.screen().isPresent()) {
                    screen = reply.screen();
                }
            }
            requireConversation(lease, reply.nextConversation()
                    .map(next -> new TerminalConversation(next.id(), next.version(), next.nextTransaction())));
            if (screen.isPresent()) {
                paint(screen.orElseThrow());
            } else {
                // 画面を送らずに終わった task。画面はそのまま残し、キーボードだけを開ける
                connection.writeRecord(Tn3270DataStream.restoreKeyboard(false));
            }
        }

        private void requireConversation(TerminalLease lease, Optional<TerminalConversation> conversation) {
            if (!terminals.setConversation(lease, conversation, clock.instant())) {
                throw new IllegalStateException("the terminal lease expired while the task was running");
            }
        }

        /** 失敗した task のあとは、次の入力を疑似会話の続きにしない。 */
        private void abandon(TerminalLease lease) {
            try {
                terminals.setConversation(lease, Optional.empty(), clock.instant());
            } catch (RuntimeException ignored) {
                // 外せなくても、会話の版が合わずに次の task が断られる
            }
        }

        private void paint(CicsTerminalScreen screen) throws IOException {
            byte[] stream;
            try {
                stream = Tn3270DataStream.screen(screen, device, device.extended(), codePage);
            } catch (IllegalArgumentException unfit) {
                LOG.log(System.Logger.Level.WARNING, "screen for terminal " + terminalId + " not sent", unfit);
                notice("COJTN002 " + stamp() + " " + options.applid()
                        + " The screen does not fit the terminal " + device.name() + ".");
                return;
            }
            onScreen = screen instanceof CicsTerminalScreen.MapScreen map
                    ? Optional.of(map.snapshot()) : Optional.empty();
            connection.writeRecord(stream);
        }

        private void notice(String message) throws IOException {
            onScreen = Optional.empty();
            connection.writeRecord(Tn3270DataStream.text(message, true, true, device, codePage));
        }

        private String stamp() {
            var now = clock.instant().atZone(options.zone());
            return DATE.format(now) + " " + TIME.format(now);
        }

        /**
         * 端末の入力を、直前に送った画面の field の名前に戻す。
         *
         * <p>保護 field は端末で打てないので送られても無視する (FSET の保護 field は端末が送り返すが、
         * RECEIVE MAP は画面の記録の MDT から読む)。名前の無い field、画面に無い address も使わない。
         * 端末は null を送らないので、残った X'00' は落とす。
         */
        private BmsTerminalInput input(BmsAid aid, Tn3270DataStream.Inbound inbound) {
            if (onScreen.isEmpty()) {
                // 書式の無い画面 (消去した画面、SEND TEXT の画面) で打たれた文字。端末制御の RECEIVE が読む
                ByteArrayOutputStream typed = new ByteArrayOutputStream();
                for (Tn3270DataStream.InboundField field : inbound.fields()) {
                    typed.writeBytes(withoutNulls(field.data()));
                }
                return new BmsTerminalInput(aid, -1, List.of(),
                        Optional.of(codePage.decode(typed.toByteArray())));
            }
            BmsScreenSnapshot snapshot = onScreen.orElseThrow();
            // 画面を送ったときと同じ規則 (Tn3270DataStream) で、既定か代替の大きさかを選ぶ
            boolean primary = snapshot.rows() <= device.defaultRows()
                    && snapshot.columns() <= device.defaultColumns();
            int columns = primary ? device.defaultColumns() : device.alternateColumns();
            int size = (primary ? device.defaultRows() : device.alternateRows()) * columns;
            List<BmsTerminalInput.FieldInput> fields = new ArrayList<>();
            for (Tn3270DataStream.InboundField received : inbound.fields()) {
                if (received.address() < 0) {
                    continue;
                }
                for (FieldState field : snapshot.fields()) {
                    int data = (Tn3270DataStream.attributeAddress(field, columns) + 1) % size;
                    if (data != received.address() || field.name().isEmpty()) {
                        continue;
                    }
                    if (field.attributes().contains(BasicAttribute.PROT)
                            || field.attributes().contains(BasicAttribute.ASKIP)) {
                        break;
                    }
                    fields.add(new BmsTerminalInput.FieldInput(field.name().orElseThrow(), field.occurrence(),
                            codePage.decode(withoutNulls(received.data()))));
                    break;
                }
            }
            int cursor = -1;
            if (inbound.cursor() >= 0) {
                int row = inbound.cursor() / columns;
                int column = inbound.cursor() % columns;
                if (row < snapshot.rows() && column < snapshot.columns()) {
                    cursor = row * snapshot.columns() + column;
                }
            }
            return new BmsTerminalInput(aid, cursor, fields);
        }

        /**
         * 疑似会話の途中でない端末の入力の先頭の語。CICS はこれを TRANSID とする。
         * 先頭の空白は飛ばし、空白か 4 文字で区切る。
         */
        private Optional<String> firstWord(Tn3270DataStream.Inbound inbound) {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (Tn3270DataStream.InboundField field : inbound.fields()) {
                all.writeBytes(withoutNulls(field.data()));
                if (all.size() > 0) {
                    break;
                }
            }
            String text = codePage.decode(all.toByteArray()).stripLeading();
            int end = 0;
            while (end < text.length() && end < 4 && text.charAt(end) != ' ') {
                end++;
            }
            return end == 0 ? Optional.empty() : Optional.of(text.substring(0, end));
        }
    }

    private static byte[] withoutNulls(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (byte b : data) {
            if (b != 0) {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    private static IdempotencyKey key() {
        return new IdempotencyKey("tn3270-" + UUID.randomUUID().toString().replace("-", ""));
    }
}
