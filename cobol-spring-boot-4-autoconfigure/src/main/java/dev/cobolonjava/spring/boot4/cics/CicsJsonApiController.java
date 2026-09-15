package dev.cobolonjava.spring.boot4.cics;

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
import dev.cobolonjava.cics.bms.BmsAid;
import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import dev.cobolonjava.cics.bms.BmsTerminalInput;
import java.security.Principal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON client から CICS の task を動かす入口 (設計 77 §4.2、暫定判断 P-135)。
 *
 * <p>ブラウザの入口と同じ規則に従う。会話を続ける要求の COMMAREA と直前の画面は会話ストアから読み、
 * client から受けない。会話の owner は認証した principal であり、coordinator が claim のときに照合する。
 */
@RestController
public class CicsJsonApiController {

    /** IMMEDIATE で続ける task の上限。 */
    static final int MAX_IMMEDIATE = 8;
    /** COMMAREA (32767 byte) を base64 にした長さ。これを越える文字列は decode しない。 */
    private static final int MAX_BASE64 = (32767 + 2) / 3 * 4;
    private static final int MAX_FIELDS = 1920;

    private final CicsTaskCoordinator coordinator;
    private final ConversationStorePort conversations;
    private final Clock clock;

    public CicsJsonApiController(CicsTaskCoordinator coordinator, ConversationStorePort conversations, Clock clock) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @PostMapping(path = "/api/cics/{transid}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> launch(@PathVariable("transid") String transid,
                                    @RequestBody(required = false) CicsApiRequest body, Principal principal) {
        if (principal == null) {
            return problem(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        TransId transaction = TransId.of(transid);
        String owner = principal.getName();

        Optional<ConversationReference> reference = Optional.empty();
        CicsPayload payload;
        if (body.conversation() != null) {
            if (body.commarea() != null || (body.containers() != null && !body.containers().isEmpty())) {
                // 続ける会話の COMMAREA は server が持っている。client の値で上書きさせない
                throw new IllegalArgumentException("a continued conversation must not carry COMMAREA or containers");
            }
            if (body.conversation().id() == null || body.conversation().version() == null) {
                throw new IllegalArgumentException("conversation requires id and version");
            }
            ConversationId id = new ConversationId(body.conversation().id());
            long version = body.conversation().version();
            ConversationEnvelope envelope = conversations.load(id, clock.instant())
                    .filter(loaded -> loaded.version() == version)
                    .orElse(null);
            reference = Optional.of(new ConversationReference(id, version));
            if (envelope != null) {
                payload = envelope.payload();
            } else if (body.idempotencyKey() != null) {
                // 同じ冪等キーの再送なら会話はもう進んでいる。coordinator が覚えた結果を返すか、版の衝突で断る。
                // 続ける会話の要約は payload を含まないので、空で渡してよい (暫定判断 P-142)
                payload = CicsPayload.empty();
            } else {
                return problem(HttpStatus.CONFLICT, "The conversation is out of date.");
            }
        } else {
            payload = new CicsPayload(decode(body.commarea()), containersOf(body.containers()));
        }

        IdempotencyKey key = new IdempotencyKey(body.idempotencyKey() != null
                ? body.idempotencyKey() : "api-" + UUID.randomUUID().toString().replace("-", ""));
        CicsTaskReply reply = coordinator.launch(new CicsTaskRequest(transaction.value(), owner, payload,
                reference, key, terminalOf(body.terminal()), Optional.empty(), userIdOf(owner)));
        for (int step = 0; reply.immediateNext(); step++) {
            if (step >= MAX_IMMEDIATE) {
                throw new IllegalStateException("RETURN IMMEDIATE chain exceeded " + MAX_IMMEDIATE + " tasks");
            }
            ConversationEnvelope next = reply.nextConversation().orElseThrow();
            // client の冪等キーから連鎖の段ごとのキーを作る。再送でも同じキーになり、各段の覚えた結果が返る
            IdempotencyKey stepKey = body.idempotencyKey() != null
                    ? new IdempotencyKey(body.idempotencyKey() + "." + (step + 1))
                    : new IdempotencyKey("api-" + UUID.randomUUID().toString().replace("-", ""));
            reply = coordinator.launch(new CicsTaskRequest(next.nextTransaction().value(), owner, next.payload(),
                    Optional.of(new ConversationReference(next.id(), next.version())), stepKey,
                    Optional.empty(), Optional.empty(), userIdOf(owner)));
        }
        return ResponseEntity.ok(replyOf(reply));
    }

    @ExceptionHandler(ConversationConflictException.class)
    public ResponseEntity<ProblemDetail> conflict() {
        return problem(HttpStatus.CONFLICT, "The conversation is out of date.");
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> idempotencyConflict() {
        return problem(HttpStatus.CONFLICT, "A request with this idempotency key is in progress or differs.");
    }

    @ExceptionHandler(UnknownTransactionException.class)
    public ResponseEntity<ProblemDetail> unknown() {
        return problem(HttpStatus.NOT_FOUND, "The transaction is not defined.");
    }

    @ExceptionHandler(DisabledTransactionException.class)
    public ResponseEntity<ProblemDetail> disabled() {
        return problem(HttpStatus.FORBIDDEN, "The transaction is disabled.");
    }

    @ExceptionHandler({CicsInputLimitException.class, IllegalArgumentException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ProblemDetail> invalid() {
        // 入力の内容は応答にもログにも出さない
        return problem(HttpStatus.BAD_REQUEST, "The request is not valid.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> unsupportedMediaType() {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The request must be application/json.");
    }

    @ExceptionHandler(CicsAbend.class)
    public ResponseEntity<ProblemDetail> abend(CicsAbend abend) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "The transaction ended abnormally.");
        Objects.requireNonNull(response.getBody()).setProperty("abendCode", abend.code().value());
        return response;
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ProblemDetail> failure() {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "The transaction could not be completed.");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    private static byte[] decode(String base64) {
        if (base64 == null) {
            return new byte[0];
        }
        if (base64.length() > MAX_BASE64) {
            throw new IllegalArgumentException("base64 value is too long");
        }
        // 不正な文字や詰め物は推測で読み飛ばさず断る
        return Base64.getDecoder().decode(base64);
    }

    private static Map<String, byte[]> containersOf(Map<String, String> containers) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (containers != null) {
            containers.forEach((name, value) -> out.put(name, decode(Objects.requireNonNull(value, "container"))));
        }
        return out;
    }

    private static Optional<BmsTerminalInput> terminalOf(CicsApiRequest.Terminal terminal) {
        if (terminal == null) {
            return Optional.empty();
        }
        BmsAid aid = aidOf(terminal.aid());
        int cursor = terminal.cursor() == null ? -1 : terminal.cursor();
        if (cursor < -1) {
            throw new IllegalArgumentException("cursor position must be -1 or greater");
        }
        List<BmsTerminalInput.FieldInput> fields = new ArrayList<>();
        if (terminal.fields() != null && aid != BmsAid.CLEAR
                && aid != BmsAid.PA1 && aid != BmsAid.PA2 && aid != BmsAid.PA3) {
            if (terminal.fields().size() > MAX_FIELDS) {
                throw new IllegalArgumentException("too many BMS fields");
            }
            for (CicsApiRequest.Field field : terminal.fields()) {
                if (field == null || field.name() == null || !field.name().matches("[A-Za-z0-9@#$]{1,30}")
                        || field.value() == null || field.value().codePoints().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("malformed BMS field");
                }
                fields.add(new BmsTerminalInput.FieldInput(field.name().toUpperCase(Locale.ROOT),
                        field.occurrence() == null ? 1 : field.occurrence(), field.value()));
            }
        }
        return Optional.of(new BmsTerminalInput(aid, cursor, fields));
    }

    private static BmsAid aidOf(String aid) {
        if (aid == null || aid.isBlank()) {
            throw new IllegalArgumentException("AID is required");
        }
        String normalized = aid.strip().toUpperCase(Locale.ROOT);
        for (BmsAid candidate : BmsAid.values()) {
            if (candidate != BmsAid.NULL
                    && (candidate.name().equals(normalized) || candidate.cobolName().equals(normalized))) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unsupported AID");
    }

    /** CICS の user ID の形 (8 文字まで) に収まる principal 名だけを user ID にする。推測で切り詰めない。 */
    private static Optional<String> userIdOf(String principal) {
        String upper = principal.toUpperCase(Locale.ROOT);
        return upper.matches("[A-Z0-9@#$]{1,8}") ? Optional.of(upper) : Optional.empty();
    }

    private static CicsApiReply replyOf(CicsTaskReply reply) {
        Base64.Encoder encoder = Base64.getEncoder();
        Map<String, String> containers = new LinkedHashMap<>();
        reply.payload().containers().forEach((name, value) -> containers.put(name, encoder.encodeToString(value)));
        CicsApiReply.Conversation conversation = reply.nextConversation()
                .map(next -> new CicsApiReply.Conversation(next.id().value(), next.version(),
                        next.nextTransaction().value()))
                .orElse(null);
        return new CicsApiReply(reply.taskId().value(), reply.transactionId().value(),
                encoder.encodeToString(reply.payload().commarea()), containers, conversation,
                reply.screen().map(CicsJsonApiController::screenOf).orElse(null));
    }

    private static CicsApiReply.Screen screenOf(CicsTerminalScreen screen) {
        if (screen instanceof CicsTerminalScreen.TextScreen text) {
            return new CicsApiReply.Screen("text", null, null, null, null, null, text.alarm(),
                    text.keyboardRestored(), text.text(), List.of());
        }
        BmsScreenSnapshot snapshot = ((CicsTerminalScreen.MapScreen) screen).snapshot();
        List<CicsApiReply.Field> fields = new ArrayList<>();
        for (BmsScreenSnapshot.FieldState field : snapshot.fields()) {
            boolean dark = field.attributes().contains(BasicAttribute.DRK);
            fields.add(new CicsApiReply.Field(field.name().orElse(null), field.occurrence(),
                    field.position().row(), field.position().column(), field.length(),
                    field.attributes().stream().map(Enum::name).sorted().toList(),
                    field.color().map(Enum::name).orElse(null),
                    field.highlight().map(Enum::name).orElse(null),
                    // DRK の値は client へ返さない
                    dark ? null : field.data(), field.modified()));
        }
        return new CicsApiReply.Screen("map", snapshot.mapset(), snapshot.map(), snapshot.rows(),
                snapshot.columns(), snapshot.cursorOffset(), snapshot.alarm(), snapshot.keyboardRestored(),
                null, fields);
    }
}
