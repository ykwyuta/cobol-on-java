package dev.cobolonjava.spring.boot4.bms;

import dev.cobolonjava.cics.CicsAbend;
import dev.cobolonjava.cics.CicsInputLimitException;
import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskCoordinator;
import dev.cobolonjava.cics.CicsTaskPolicy;
import dev.cobolonjava.cics.CicsTaskReply;
import dev.cobolonjava.cics.CicsTaskRequest;
import dev.cobolonjava.cics.CicsTerminalRegistryPort;
import dev.cobolonjava.cics.CicsTerminalRegistryPort.TerminalConversation;
import dev.cobolonjava.cics.CicsTerminalRegistryPort.TerminalLease;
import dev.cobolonjava.cics.CicsTerminalScreen;
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
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ブラウザから CICS の疑似会話を動かす入口 (設計 77 §4.2、設計 81 §5、設計 83 §4、暫定判断 P-134・P-144)。
 *
 * <p>GET は開始の画面を返すだけで task を動かさない。task は CSRF で守った POST で動かす。
 * 会話の COMMAREA と直前の画面は server の会話ストアから読み、client から受け取らない。
 * HTTP session に置くのは端末の名前だけで、端末の疑似会話の参照は端末の登録 ({@link CicsTerminalRegistryPort}) に置く。
 * task の前に端末を lease するので、1 つの端末で task は同時に 1 つになる。
 */
@Controller
public class CicsBrowserController {

    static final String TERMINAL = "dev.cobolonjava.cics.browser.terminal";
    /** 画面の form が運ぶ冪等キーと会話の参照 (暫定判断 P-142)。 */
    static final String KEY_PARAMETER = "idempotencyKey";
    static final String CONVERSATION_ID_PARAMETER = "conversationId";
    static final String CONVERSATION_VERSION_PARAMETER = "conversationVersion";
    /** IMMEDIATE で続ける task の上限。業務の無限の連鎖で要求を返さなくなるのを防ぐ。 */
    static final int MAX_IMMEDIATE = 8;
    /** HTTP session が失効の時間を持たないときの端末の期限。 */
    static final Duration DEFAULT_TERMINAL_LIFETIME = Duration.ofMinutes(30);

    private final CicsTaskCoordinator coordinator;
    private final ConversationStorePort conversations;
    private final CicsTerminalRegistryPort terminals;
    private final BmsScreenViewFactory views;
    private final BmsTerminalInputBinder binder;
    private final Clock clock;
    private final Duration terminalLease;

    /**
     * @param policy 端末の lease の長さは会話の lease ({@link CicsTaskPolicy#leaseDuration}) と同じにする。
     *               会話の lease は task の期限と IMMEDIATE の連鎖より長いことを利用者が保証する
     */
    public CicsBrowserController(CicsTaskCoordinator coordinator, ConversationStorePort conversations,
                                 CicsTerminalRegistryPort terminals, BmsScreenViewFactory views,
                                 BmsTerminalInputBinder binder, Clock clock, CicsTaskPolicy policy) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.terminals = Objects.requireNonNull(terminals, "terminals");
        this.views = Objects.requireNonNull(views, "views");
        this.binder = Objects.requireNonNull(binder, "binder");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.terminalLease = Objects.requireNonNull(policy, "policy").leaseDuration();
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

        Instant now = clock.instant();
        String terminalId = terminalOf(session, owner, now);
        TerminalLease lease = terminals.lease(terminalId, owner, terminalLease, now).orElse(null);
        if (lease == null) {
            // 同じ端末で task が動いている。3270 の入力禁止と同じく、この送信は動かさない
            return error(response, model, HttpServletResponse.SC_CONFLICT,
                    "The terminal is busy. Wait for the reply.");
        }
        try {
            return run(transaction, owner, input, sentKey, form, terminalId, lease, request, response, model);
        } catch (RuntimeException failure) {
            // task を動かす前に断ったもの (冪等キーの衝突、未定義・無効の transaction、入力の形) は端末の会話を残す。
            // 会話の衝突、ABEND、確定の失敗は端末の会話を外し、次は開始からにする
            boolean keepsConversation = failure instanceof IdempotencyConflictException
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
                       Optional<IdempotencyKey> sentKey, MultiValueMap<String, String> form, String terminalId,
                       TerminalLease lease, HttpServletRequest request, HttpServletResponse response, Model model) {
        Instant now = clock.instant();
        Optional<ConversationReference> reference = Optional.empty();
        CicsPayload payload = CicsPayload.empty();
        Optional<TerminalConversation> current = terminals.find(terminalId, now)
                .flatMap(CicsTerminalRegistryPort.Terminal::conversation);
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
                Optional.of(terminalId), userIdOf(owner)));
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
                    Optional.empty(), Optional.of(terminalId), userIdOf(owner)));
        }

        String nextTransaction = reply.nextConversation().map(next -> next.nextTransaction().value())
                .orElse(transaction.value());
        requireConversationChanged(lease, reply.nextConversation()
                .map(next -> new TerminalConversation(next.id(), next.version(), next.nextTransaction())));

        String action = request.getContextPath() + "/cics/" + nextTransaction;
        model.addAttribute("action", action);
        model.addAttribute("bmsAssets", request.getContextPath() + "/cobol/bms");
        model.addAttribute("conversation", reply.nextConversation().isPresent());
        // 次の画面の送信に使う冪等キーと会話の参照。同じ画面を二度送っても同じキーになる
        model.addAttribute("idempotencyKey", idempotencyKey().value());
        reply.nextConversation().ifPresent(next -> {
            model.addAttribute("conversationId", next.id().value());
            model.addAttribute("conversationVersion", next.version());
        });
        CicsTerminalScreen screen = reply.screen().orElse(null);
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
     * HTTP session の端末。登録が残っていて owner が同じなら期限を延ばし、無ければ (初回、期限切れ、別の JVM の
     * 1 つの JVM の登録、利用者が替わった) 新しく登録する。端末の期限は HTTP session の失効の時間に合わせる。
     */
    private String terminalOf(HttpSession session, String owner, Instant now) {
        int seconds = session.getMaxInactiveInterval();
        Instant expiresAt = now.plus(seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_TERMINAL_LIFETIME);
        String terminal = (String) session.getAttribute(TERMINAL);
        if (terminal != null && terminals.touch(terminal, owner, expiresAt, now)) {
            return terminal;
        }
        terminal = terminals.register(owner, expiresAt, now);
        session.setAttribute(TERMINAL, terminal);
        return terminal;
    }

    /** CICS の user ID の形 (8 文字まで) に収まる principal 名だけを user ID にする。推測で切り詰めない。 */
    private static Optional<String> userIdOf(String principal) {
        String upper = principal.toUpperCase(Locale.ROOT);
        return upper.matches("[A-Z0-9@#$]{1,8}") ? Optional.of(upper) : Optional.empty();
    }

    private static IdempotencyKey idempotencyKey() {
        return new IdempotencyKey("web-" + UUID.randomUUID().toString().replace("-", ""));
    }
}
