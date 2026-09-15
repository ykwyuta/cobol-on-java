package dev.cobolonjava.spring.boot4.bms;

import dev.cobolonjava.cics.CicsAbend;
import dev.cobolonjava.cics.CicsInputLimitException;
import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskCoordinator;
import dev.cobolonjava.cics.CicsTaskPolicy;
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
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.ConversationReference;
import dev.cobolonjava.cics.ConversationStorePort;
import dev.cobolonjava.cics.DisabledTransactionException;
import dev.cobolonjava.cics.IdempotencyConflictException;
import dev.cobolonjava.cics.IdempotencyKey;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.cics.UnknownTransactionException;
import dev.cobolonjava.cics.bms.BmsTerminalInput;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ブラウザから CICS の疑似会話を動かす入口 (設計 77 §4.2、設計 81 §5、設計 83 §4・§7、暫定判断 P-134・P-144)。
 *
 * <p>GET は開始の画面を返すだけで task を動かさない。task は CSRF で守った POST で動かす。
 * 会話の COMMAREA と直前の画面は server の会話ストアから読み、client から受け取らない。
 * HTTP session に置くのは端末の名前だけで、端末の疑似会話の参照は端末の登録 ({@link CicsTerminalRegistryPort}) に置く。
 * task の前に端末を lease するので、1 つの端末で task は同時に 1 つになる。
 *
 * <h2>端末へ出す task の画面</h2>
 * <p>START TERMID や ATI の task が送った画面は端末の現在の画面になり、画面の版が進む。画面は版を hidden で持ち、
 * {@code /cics/terminal/events} の SSE で版が進んだことを受けて {@code /cics/terminal} を読み直す。SSE が無ければ、
 * 古い版の画面からの送信は task を動かさず、現在の画面を返す (3270 では書き換わった画面への入力は成り立たない)。
 */
@Controller
public class CicsBrowserController implements SmartLifecycle {

    static final String TERMINAL = "dev.cobolonjava.cics.browser.terminal";
    /** 画面の form が運ぶ冪等キーと会話の参照 (暫定判断 P-142)。 */
    static final String KEY_PARAMETER = "idempotencyKey";
    static final String CONVERSATION_ID_PARAMETER = "conversationId";
    static final String CONVERSATION_VERSION_PARAMETER = "conversationVersion";
    /** 画面が運ぶ端末の画面の版 (設計 83 §7)。 */
    static final String SCREEN_VERSION_PARAMETER = "screenVersion";
    /** IMMEDIATE で続ける task の上限。業務の無限の連鎖で要求を返さなくなるのを防ぐ。 */
    static final int MAX_IMMEDIATE = CicsTerminalTasks.MAX_IMMEDIATE;
    /** HTTP session が失効の時間を持たないときの端末の期限。 */
    static final Duration DEFAULT_TERMINAL_LIFETIME = Duration.ofMinutes(30);
    /** SSE で端末の画面の版を見る間隔と、1 つの接続を保つ長さ。切れれば EventSource がつなぎ直す。 */
    static final Duration EVENT_POLL = Duration.ofSeconds(1);
    static final Duration EVENT_TIMEOUT = Duration.ofMinutes(5);

    private final CicsTaskCoordinator coordinator;
    private final ConversationStorePort conversations;
    private final CicsTerminalRegistryPort terminals;
    private final BmsScreenViewFactory views;
    private final BmsTerminalInputBinder binder;
    private final Clock clock;
    private final Duration terminalLease;
    private final CicsBrowserTerminalNames names;
    private ScheduledExecutorService events;
    /** 開いている SSE。application の停止で閉じないと、web server の graceful shutdown が接続の期限まで待つ。 */
    private final java.util.Set<SseEmitter> openEvents = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean running;

    /** 固定の端末名を別の利用者が使っている。 */
    static final class TerminalInUseException extends RuntimeException {

        TerminalInUseException() {
            super("the fixed terminal name is in use by another user");
        }
    }

    /**
     * @param policy 端末の lease の長さは会話の lease ({@link CicsTaskPolicy#leaseDuration}) と同じにする。
     *               会話の lease は task の期限と IMMEDIATE の連鎖より長いことを利用者が保証する
     * @param names  利用者ごとの固定の端末名
     */
    public CicsBrowserController(CicsTaskCoordinator coordinator, ConversationStorePort conversations,
                                 CicsTerminalRegistryPort terminals, BmsScreenViewFactory views,
                                 BmsTerminalInputBinder binder, Clock clock, CicsTaskPolicy policy,
                                 CicsBrowserTerminalNames names) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.terminals = Objects.requireNonNull(terminals, "terminals");
        this.views = Objects.requireNonNull(views, "views");
        this.binder = Objects.requireNonNull(binder, "binder");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.terminalLease = Objects.requireNonNull(policy, "policy").leaseDuration();
        this.names = Objects.requireNonNull(names, "names");
    }

    @GetMapping("/cics/{transid}")
    public String shell(@PathVariable("transid") String transid, Principal principal,
                        HttpServletRequest request, HttpServletResponse response, Model model) {
        if (principal == null) {
            return error(response, model, HttpServletResponse.SC_UNAUTHORIZED, "Authentication is required.");
        }
        TransId transaction = TransId.of(transid);
        model.addAttribute("transaction", transaction.value());
        model.addAttribute("action", request.getContextPath() + "/cics/" + transaction.value());
        model.addAttribute("idempotencyKey", idempotencyKey().value());
        return "cobol/bms/start";
    }

    @PostMapping("/cics/{transid}")
    public String launch(@PathVariable("transid") String transid, @RequestParam MultiValueMap<String, String> form,
                         Principal principal, HttpServletRequest request, HttpServletResponse response,
                         HttpSession session, Model model) {
        if (principal == null) {
            return error(response, model, HttpServletResponse.SC_UNAUTHORIZED, "Authentication is required.");
        }
        TransId transaction = TransId.of(transid);
        String owner = principal.getName();
        Optional<BmsTerminalInput> input = form.containsKey("aid")
                ? Optional.of(binder.bind(form.getFirst("aid"), form.getFirst("cursor"), singleValues(form)))
                : Optional.empty();
        String sentKeyValue = form.getFirst(KEY_PARAMETER);
        Optional<IdempotencyKey> sentKey = sentKeyValue == null || sentKeyValue.isBlank()
                ? Optional.empty() : Optional.of(new IdempotencyKey(sentKeyValue));
        String sentScreen = form.getFirst(SCREEN_VERSION_PARAMETER);
        long screenVersion = sentScreen == null ? -1 : Long.parseLong(sentScreen);

        Instant now = clock.instant();
        String terminalId = terminalOf(session, owner, now);
        TerminalLease lease = terminals.lease(terminalId, owner, terminalLease, now).orElse(null);
        if (lease == null) {
            // 同じ端末で task が動いている。3270 の入力禁止と同じく、この送信は動かさない
            return error(response, model, HttpServletResponse.SC_CONFLICT,
                    "The terminal is busy. Wait for the reply.");
        }
        try {
            return run(transaction, owner, input, sentKey, screenVersion, form, terminalId, lease, request, response,
                    model);
        } catch (RuntimeException failure) {
            // task を動かす前に断ったもの (冪等キーの衝突、未定義・無効の transaction、入力の形) は端末の会話を残す。
            // 会話の衝突、ABEND、確定の失敗は端末の会話を外し、次は開始からにする
            boolean keepsConversation = failure instanceof IdempotencyConflictException
                    || failure instanceof dev.cobolonjava.cics.TransactionNotAuthorizedException
                    || failure instanceof UnknownTransactionException
                    || failure instanceof DisabledTransactionException
                    || failure instanceof CicsInputLimitException
                    || failure instanceof IllegalArgumentException;
            if (!keepsConversation) {
                try {
                    terminals.setConversation(lease, Optional.empty(), clock.instant());
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        } finally {
            terminals.release(lease, clock.instant());
        }
    }

    private String run(TransId transaction, String owner, Optional<BmsTerminalInput> input,
                       Optional<IdempotencyKey> sentKey, long screenVersion, MultiValueMap<String, String> form,
                       String terminalId, TerminalLease lease, HttpServletRequest request,
                       HttpServletResponse response, Model model) {
        Instant now = clock.instant();
        Terminal terminal = terminals.find(terminalId, now)
                .orElseThrow(() -> new IllegalStateException("the leased terminal is no longer registered"));
        if (screenVersion >= 0 && screenVersion < terminal.screenVersion()) {
            // 端末へ出す task が画面を書き換えた。古い画面への入力は動かさず、現在の画面を返す (設計 83 §7)
            return renderCurrent(terminal, Optional.of(transaction), request, model);
        }
        Optional<ConversationReference> reference = Optional.empty();
        CicsPayload payload = CicsPayload.empty();
        Optional<TerminalConversation> current = terminal.conversation();
        if (sentKey.isPresent()) {
            // 画面が運ぶ冪等キーと会話の参照で動かす。同じ画面の再送は、会話がもう進んでいても coordinator が
            // 覚えた結果を返す。会話の owner は coordinator が照合する (暫定判断 P-142)
            String sentId = form.getFirst(CONVERSATION_ID_PARAMETER);
            if (sentId != null) {
                String sentVersion = form.getFirst(CONVERSATION_VERSION_PARAMETER);
                if (sentVersion == null) {
                    throw new IllegalArgumentException("conversation version is required");
                }
                ConversationId id = new ConversationId(sentId);
                long version = Long.parseLong(sentVersion);
                reference = Optional.of(new ConversationReference(id, version));
                payload = conversations.load(id, now)
                        .filter(loaded -> loaded.version() == version)
                        .map(ConversationEnvelope::payload)
                        .orElse(CicsPayload.empty());
            }
        } else if (current.isPresent()) {
            TerminalConversation conversation = current.orElseThrow();
            ConversationEnvelope envelope = conversation.nextTransaction().equals(transaction)
                    ? conversations.load(conversation.id(), now)
                            .filter(loaded -> loaded.version() == conversation.version())
                            .orElse(null)
                    : null;
            if (envelope == null) {
                // 端末が待っている TRANSID と違うか、会話がもう無い。古い tab からの送信とみなして動かさない
                requireConversationChanged(lease, Optional.empty());
                return error(response, model, HttpServletResponse.SC_CONFLICT,
                        "The screen is out of date. Start the transaction again.");
            }
            reference = Optional.of(new ConversationReference(conversation.id(), conversation.version()));
            payload = envelope.payload();
        }

        CicsTaskReply reply = coordinator.launch(new CicsTaskRequest(transaction.value(), owner, payload,
                reference, sentKey.orElseGet(CicsBrowserController::idempotencyKey), input,
                Optional.of(terminalId), Optional.empty()));
        for (int step = 0; reply.immediateNext(); step++) {
            if (step >= MAX_IMMEDIATE) {
                throw new IllegalStateException("RETURN IMMEDIATE chain exceeded " + MAX_IMMEDIATE + " tasks");
            }
            ConversationEnvelope next = reply.nextConversation().orElseThrow();
            // 画面のキーから段ごとのキーを作る。再送でも同じキーになり、各段の覚えた結果が返る
            int number = step + 1;
            IdempotencyKey stepKey = sentKey.map(key -> new IdempotencyKey(key.value() + "." + number))
                    .orElseGet(CicsBrowserController::idempotencyKey);
            reply = coordinator.launch(new CicsTaskRequest(next.nextTransaction().value(), owner, next.payload(),
                    Optional.of(new ConversationReference(next.id(), next.version())), stepKey,
                    Optional.empty(), Optional.of(terminalId), Optional.empty()));
        }

        String nextTransaction = reply.nextConversation().map(next -> next.nextTransaction().value())
                .orElse(transaction.value());
        requireConversationChanged(lease, reply.nextConversation()
                .map(next -> new TerminalConversation(next.id(), next.version(), next.nextTransaction())));

        long version = terminals.find(terminalId, clock.instant()).map(Terminal::screenVersion)
                .orElse(terminal.screenVersion());
        attributes(request, model, Optional.of(nextTransaction), reply.nextConversation().isPresent(),
                reply.nextConversation().map(next -> new TerminalConversation(next.id(), next.version(),
                        next.nextTransaction())), version);
        return view(reply.screen().orElse(null), model);
    }

    /** 端末の現在の画面を読み直す。SSE で版が進んだことを受けたブラウザが開く。 */
    @GetMapping("/cics/terminal")
    public String currentScreen(Principal principal, HttpServletRequest request, HttpServletResponse response,
                                Model model) {
        if (principal == null) {
            return error(response, model, HttpServletResponse.SC_UNAUTHORIZED, "Authentication is required.");
        }
        Optional<Terminal> terminal = sessionTerminal(request, principal.getName());
        if (terminal.isEmpty()) {
            return error(response, model, HttpServletResponse.SC_NOT_FOUND, "The terminal is not registered.");
        }
        return renderCurrent(terminal.orElseThrow(), Optional.empty(), request, model);
    }

    /**
     * 端末の画面の版が {@code version} より進んだら {@code screen} event を 1 度送って閉じる。
     * 画面の中身は送らない (読み直しは通常の要求で認証と一緒に行う)。
     */
    @GetMapping(path = "/cics/terminal/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> screenEvents(@RequestParam("version") long version, Principal principal,
                                                   HttpServletRequest request) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        HttpSession session = request.getSession(false);
        String terminalId = session == null ? null : (String) session.getAttribute(TERMINAL);
        if (terminalId == null) {
            return ResponseEntity.notFound().build();
        }
        String owner = principal.getName();
        SseEmitter emitter = new SseEmitter(EVENT_TIMEOUT.toMillis());
        if (announced(emitter, terminalId, owner, version)) {
            return ResponseEntity.ok(emitter);
        }
        if (!running) {
            // 停止の途中。接続を持たずに閉じ、EventSource は起動し直した server へつなぎ直す
            emitter.complete();
            return ResponseEntity.ok(emitter);
        }
        openEvents.add(emitter);
        AtomicReference<ScheduledFuture<?>> polling = new AtomicReference<>();
        polling.set(scheduler().scheduleWithFixedDelay(() -> {
            try {
                if (announced(emitter, terminalId, owner, version)) {
                    polling.get().cancel(false);
                }
            } catch (RuntimeException failure) {
                emitter.completeWithError(failure);
                polling.get().cancel(false);
            }
        }, EVENT_POLL.toMillis(), EVENT_POLL.toMillis(), TimeUnit.MILLISECONDS));
        Runnable stop = () -> {
            openEvents.remove(emitter);
            ScheduledFuture<?> future = polling.get();
            if (future != null) {
                future.cancel(false);
            }
        };
        emitter.onCompletion(stop);
        emitter.onTimeout(stop);
        emitter.onError(ignored -> stop.run());
        return ResponseEntity.ok(emitter);
    }

    /** 版が進んでいれば event を送って閉じ、true。端末が無くなっていれば閉じて true。 */
    private boolean announced(SseEmitter emitter, String terminalId, String owner, long version) {
        Optional<Terminal> terminal = terminals.find(terminalId, clock.instant())
                .filter(found -> found.owner().equals(owner));
        if (terminal.isEmpty()) {
            emitter.complete();
            return true;
        }
        long current = terminal.orElseThrow().screenVersion();
        if (current <= version) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name("screen").data(Long.toString(current)));
            emitter.complete();
        } catch (IOException | IllegalStateException gone) {
            // 接続はもう閉じている
            emitter.completeWithError(gone);
        }
        return true;
    }

    private synchronized ScheduledExecutorService scheduler() {
        if (events == null) {
            events = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "cics-terminal-events");
                thread.setDaemon(true);
                return thread;
            });
        }
        return events;
    }

    @Override
    public void start() {
        running = true;
    }

    /**
     * 開いている SSE をすべて閉じる。SmartLifecycle の既定の phase は web server の graceful shutdown より先に止まるので、
     * graceful shutdown が SSE の接続の期限 ({@link #EVENT_TIMEOUT}) まで待たずに済む。
     */
    @Override
    public void stop() {
        running = false;
        for (SseEmitter emitter : java.util.List.copyOf(openEvents)) {
            emitter.complete();
        }
        openEvents.clear();
        synchronized (this) {
            if (events != null) {
                events.shutdownNow();
                events = null;
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private Optional<Terminal> sessionTerminal(HttpServletRequest request, String owner) {
        HttpSession session = request.getSession(false);
        String terminalId = session == null ? null : (String) session.getAttribute(TERMINAL);
        return terminalId == null ? Optional.empty()
                : terminals.find(terminalId, clock.instant()).filter(found -> found.owner().equals(owner));
    }

    /** 端末へ出す task が置いた現在の画面を描く。会話の途中ならその会話を続ける form にする。 */
    private String renderCurrent(Terminal terminal, Optional<TransId> fallback, HttpServletRequest request,
                                 Model model) {
        Optional<TerminalScreen> current = terminals.screen(terminal.id(), clock.instant());
        Optional<String> next = terminal.conversation().map(conversation -> conversation.nextTransaction().value())
                .or(() -> fallback.map(TransId::value));
        attributes(request, model, next, terminal.conversation().isPresent(), terminal.conversation(),
                current.map(TerminalScreen::version).orElse(terminal.screenVersion()));
        return view(current.map(TerminalScreen::screen).orElse(null), model);
    }

    private void attributes(HttpServletRequest request, Model model, Optional<String> nextTransaction,
                            boolean conversation, Optional<TerminalConversation> reference, long screenVersion) {
        model.addAttribute("action", nextTransaction.map(value -> request.getContextPath() + "/cics/" + value)
                .orElse(""));
        model.addAttribute("bmsAssets", request.getContextPath() + "/cobol/bms");
        model.addAttribute("conversation", conversation);
        // 次の画面の送信に使う冪等キーと会話の参照。同じ画面を二度送っても同じキーになる
        model.addAttribute("idempotencyKey", idempotencyKey().value());
        reference.ifPresent(next -> {
            model.addAttribute("conversationId", next.id().value());
            model.addAttribute("conversationVersion", next.version());
        });
        model.addAttribute("screenVersion", screenVersion);
        model.addAttribute("terminalEvents", request.getContextPath() + "/cics/terminal/events");
        model.addAttribute("terminalScreen", request.getContextPath() + "/cics/terminal");
    }

    private String view(CicsTerminalScreen screen, Model model) {
        if (screen instanceof CicsTerminalScreen.MapScreen map) {
            model.addAttribute("screen", views.create(map.snapshot()));
            return "cobol/bms/screen";
        }
        model.addAttribute("text", screen instanceof CicsTerminalScreen.TextScreen text ? text.text() : "");
        return "cobol/bms/text";
    }

    /**
     * 端末の会話を書き換える。lease が task の間に切れていれば書き換えられず、端末は古い会話を指したままになるので、
     * 成功した応答を返さずに失敗させる (次の要求は古い版として 409 になる)。
     */
    private void requireConversationChanged(TerminalLease lease, Optional<TerminalConversation> conversation) {
        if (!terminals.setConversation(lease, conversation, clock.instant())) {
            throw new IllegalStateException("the terminal lease expired while the task was running");
        }
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public String idempotencyConflict(HttpServletResponse response, Model model) {
        // 同じ画面の送信がまだ動いているか、同じキーで違う内容が送られた。会話はそのまま残す
        return error(response, model, HttpServletResponse.SC_CONFLICT,
                "This screen was already sent. Wait for the reply or start the transaction again.");
    }

    @ExceptionHandler(dev.cobolonjava.cics.ConversationConflictException.class)
    public String conflict(HttpServletResponse response, Model model) {
        return error(response, model, HttpServletResponse.SC_CONFLICT,
                "The screen is out of date. Start the transaction again.");
    }

    @ExceptionHandler(dev.cobolonjava.cics.TransactionNotAuthorizedException.class)
    public String notAuthorized(HttpServletResponse response, Model model) {
        // user ID や権限の構成は応答に出さない
        return error(response, model, HttpServletResponse.SC_FORBIDDEN,
                "You are not authorized to run the transaction.");
    }

    @ExceptionHandler(TerminalInUseException.class)
    public String terminalInUse(HttpServletResponse response, Model model) {
        return error(response, model, HttpServletResponse.SC_CONFLICT, "The terminal is in use by another user.");
    }

    @ExceptionHandler(UnknownTransactionException.class)
    public String unknown(HttpServletResponse response, Model model) {
        return error(response, model, HttpServletResponse.SC_NOT_FOUND, "The transaction is not defined.");
    }

    @ExceptionHandler(DisabledTransactionException.class)
    public String disabled(HttpServletResponse response, Model model) {
        return error(response, model, HttpServletResponse.SC_FORBIDDEN, "The transaction is disabled.");
    }

    @ExceptionHandler({CicsInputLimitException.class, IllegalArgumentException.class})
    public String invalid(HttpServletResponse response, Model model) {
        // 入力の内容は応答にもログにも出さない
        return error(response, model, HttpServletResponse.SC_BAD_REQUEST, "The request is not valid.");
    }

    @ExceptionHandler(CicsAbend.class)
    public String abend(CicsAbend abend, HttpServletResponse response, Model model) {
        return error(response, model, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "The transaction ended abnormally with abend code " + abend.code().value() + ".");
    }

    @ExceptionHandler(RuntimeException.class)
    public String failure(HttpServletResponse response, Model model) {
        return error(response, model, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "The transaction could not be completed.");
    }

    private static String error(HttpServletResponse response, Model model, int status, String message) {
        response.setStatus(status);
        model.addAttribute("status", status);
        model.addAttribute("message", message);
        return "cobol/bms/error";
    }

    /** {@code bms.} の field は 1 つの値だけを受ける。同じ名前を重ねた送信は形が壊れている。 */
    private static Map<String, String> singleValues(MultiValueMap<String, String> form) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : form.entrySet()) {
            if (entry.getKey().startsWith("bms.")) {
                if (entry.getValue().size() != 1) {
                    throw new IllegalArgumentException("BMS field is sent more than once: " + entry.getKey());
                }
                out.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        return out;
    }

    /**
     * HTTP session の端末。固定の端末名があればそれを登録し、無ければ、登録が残っていて owner が同じなら期限を延ばし、
     * 無ければ (初回、期限切れ、別の JVM の 1 つの JVM の登録、利用者が替わった) 新しく登録する。端末の期限は HTTP session の
     * 失効の時間に合わせる。
     */
    private String terminalOf(HttpSession session, String owner, Instant now) {
        int seconds = session.getMaxInactiveInterval();
        Instant expiresAt = now.plus(seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_TERMINAL_LIFETIME);
        Optional<String> fixed = names.terminalFor(owner);
        if (fixed.isPresent()) {
            // 固定の端末名。同じ利用者の別の session は同じ端末を使い、別の利用者が使っていれば動かさない
            if (!terminals.registerNamed(fixed.orElseThrow(), owner, expiresAt, now)) {
                throw new TerminalInUseException();
            }
            session.setAttribute(TERMINAL, fixed.orElseThrow());
            return fixed.orElseThrow();
        }
        String terminal = (String) session.getAttribute(TERMINAL);
        if (terminal != null && terminals.touch(terminal, owner, expiresAt, now)) {
            return terminal;
        }
        terminal = terminals.register(owner, expiresAt, now);
        session.setAttribute(TERMINAL, terminal);
        return terminal;
    }


    private static IdempotencyKey idempotencyKey() {
        return new IdempotencyKey("web-" + UUID.randomUUID().toString().replace("-", ""));
    }
}
