package dev.cobolonjava.spring.boot4.bms;

import dev.cobolonjava.cics.CicsAbend;
import dev.cobolonjava.cics.CicsInputLimitException;
import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskCoordinator;
import dev.cobolonjava.cics.CicsTaskReply;
import dev.cobolonjava.cics.CicsTaskRequest;
import dev.cobolonjava.cics.CicsTerminalScreen;
import dev.cobolonjava.cics.ConversationConflictException;
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
import java.io.Serializable;
import java.security.Principal;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ブラウザから CICS の疑似会話を動かす入口 (設計 77 §4.2、設計 81 §5、暫定判断 P-134)。
 *
 * <p>GET は開始の画面を返すだけで task を動かさない。task は CSRF で守った POST で動かす。
 * 会話の COMMAREA と直前の画面は server の会話ストアから読み、client から受け取らない。
 * client の session に置くのは会話の ID と版だけである。
 */
@Controller
public class CicsBrowserController {

    /** HTTP session に置く会話の参照。Spring Session で外へ保存できるよう直列化できる形にする。 */
    public record BrowserConversation(String id, long version, String nextTransaction) implements Serializable {
    }

    static final String CONVERSATION = "dev.cobolonjava.cics.browser.conversation";
    static final String TERMINAL = "dev.cobolonjava.cics.browser.terminal";
    /** 画面の form が運ぶ冪等キーと会話の参照 (暫定判断 P-142)。 */
    static final String KEY_PARAMETER = "idempotencyKey";
    static final String CONVERSATION_ID_PARAMETER = "conversationId";
    static final String CONVERSATION_VERSION_PARAMETER = "conversationVersion";
    /** IMMEDIATE で続ける task の上限。業務の無限の連鎖で要求を返さなくなるのを防ぐ。 */
    static final int MAX_IMMEDIATE = 8;
    private static final String BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final CicsTaskCoordinator coordinator;
    private final ConversationStorePort conversations;
    private final BmsScreenViewFactory views;
    private final BmsTerminalInputBinder binder;
    private final Clock clock;
    private final AtomicInteger terminals = new AtomicInteger();

    public CicsBrowserController(CicsTaskCoordinator coordinator, ConversationStorePort conversations,
                                 BmsScreenViewFactory views, BmsTerminalInputBinder binder, Clock clock) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.views = Objects.requireNonNull(views, "views");
        this.binder = Objects.requireNonNull(binder, "binder");
        this.clock = Objects.requireNonNull(clock, "clock");
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

        Optional<ConversationReference> reference = Optional.empty();
        CicsPayload payload = CicsPayload.empty();
        String sentKeyValue = form.getFirst(KEY_PARAMETER);
        Optional<IdempotencyKey> sentKey = sentKeyValue == null || sentKeyValue.isBlank()
                ? Optional.empty() : Optional.of(new IdempotencyKey(sentKeyValue));
        BrowserConversation current = (BrowserConversation) session.getAttribute(CONVERSATION);
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
                payload = conversations.load(id, clock.instant())
                        .filter(loaded -> loaded.version() == version)
                        .map(ConversationEnvelope::payload)
                        .orElse(CicsPayload.empty());
            }
        } else if (current != null) {
            if (!current.nextTransaction().equals(transaction.value())) {
                // 端末が待っている TRANSID と違う。古い tab からの送信とみなして動かさない
                session.removeAttribute(CONVERSATION);
                return error(response, model, HttpServletResponse.SC_CONFLICT,
                        "The screen is out of date. Start the transaction again.");
            }
            ConversationId id = new ConversationId(current.id());
            ConversationEnvelope envelope = conversations.load(id, clock.instant())
                    .filter(loaded -> loaded.version() == current.version())
                    .orElse(null);
            if (envelope == null) {
                session.removeAttribute(CONVERSATION);
                return error(response, model, HttpServletResponse.SC_CONFLICT,
                        "The screen is out of date. Start the transaction again.");
            }
            reference = Optional.of(new ConversationReference(id, current.version()));
            payload = envelope.payload();
        }

        CicsTaskReply reply = coordinator.launch(new CicsTaskRequest(transaction.value(), owner, payload,
                reference, sentKey.orElseGet(CicsBrowserController::idempotencyKey), input,
                Optional.of(terminalOf(session)), userIdOf(owner)));
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
                    Optional.empty(), Optional.of(terminalOf(session)), userIdOf(owner)));
        }

        String nextTransaction = reply.nextConversation().map(next -> next.nextTransaction().value())
                .orElse(transaction.value());
        reply.nextConversation().ifPresentOrElse(
                next -> session.setAttribute(CONVERSATION,
                        new BrowserConversation(next.id().value(), next.version(), next.nextTransaction().value())),
                () -> session.removeAttribute(CONVERSATION));

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

    @ExceptionHandler(ConversationConflictException.class)
    public String conflict(HttpServletRequest request, HttpServletResponse response, Model model) {
        clearConversation(request);
        return error(response, model, HttpServletResponse.SC_CONFLICT,
                "The screen is out of date. Start the transaction again.");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public String idempotencyConflict(HttpServletResponse response, Model model) {
        // 同じ画面の送信がまだ動いているか、同じキーで違う内容が送られた。会話はそのまま残す
        return error(response, model, HttpServletResponse.SC_CONFLICT,
                "This screen was already sent. Wait for the reply or start the transaction again.");
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
    public String abend(CicsAbend abend, HttpServletRequest request, HttpServletResponse response, Model model) {
        clearConversation(request);
        return error(response, model, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "The transaction ended abnormally with abend code " + abend.code().value() + ".");
    }

    @ExceptionHandler(RuntimeException.class)
    public String failure(HttpServletRequest request, HttpServletResponse response, Model model) {
        clearConversation(request);
        return error(response, model, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "The transaction could not be completed.");
    }

    private static String error(HttpServletResponse response, Model model, int status, String message) {
        response.setStatus(status);
        model.addAttribute("status", status);
        model.addAttribute("message", message);
        return "cobol/bms/error";
    }

    private static void clearConversation(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(CONVERSATION);
        }
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

    /** 端末の名前は HTTP session ごとに振る。W と base36 の 3 文字で、同じ JVM の中で重ならない範囲に限る。 */
    private String terminalOf(HttpSession session) {
        String terminal = (String) session.getAttribute(TERMINAL);
        if (terminal == null) {
            int number = terminals.getAndIncrement();
            if (number >= 36 * 36 * 36) {
                throw new IllegalStateException("browser terminal IDs are exhausted in this JVM");
            }
            terminal = "W" + BASE36.charAt(number / 1296) + BASE36.charAt(number / 36 % 36) + BASE36.charAt(number % 36);
            session.setAttribute(TERMINAL, terminal);
        }
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
