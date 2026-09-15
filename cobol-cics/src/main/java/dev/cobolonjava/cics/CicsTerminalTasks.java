package dev.cobolonjava.cics;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 端末で task を起こす共通の手順 (設計 83 §5・§6)。START TERMID と ATIFACILITY(TERMINAL) が使う。
 *
 * <p>端末を principal facility (EIBTRMID) にして coordinator で task を起こし、RETURN IMMEDIATE なら端末の入力なしで
 * 次の task を続ける (ブラウザの入口と同じく {@value #MAX_IMMEDIATE} 回まで)。端末の lease は呼び手が持つ。
 */
public final class CicsTerminalTasks {

    /** IMMEDIATE で続ける task の上限。業務の無限の連鎖で端末を返さなくなるのを防ぐ。 */
    public static final int MAX_IMMEDIATE = 8;
    private static final Pattern USER = Pattern.compile("[A-Z0-9@#$]{1,8}");

    private CicsTerminalTasks() {
    }

    /**
     * 端末で起こした task の結果。
     *
     * @param next   IMMEDIATE の連鎖を終えたあとの端末の次の疑似会話
     * @param screen 連鎖の中で最後に端末へ送った画面
     */
    public record Outcome(Optional<ConversationEnvelope> next, Optional<CicsTerminalScreen> screen) {

        public Outcome {
            Objects.requireNonNull(next, "next");
            Objects.requireNonNull(screen, "screen");
        }

        /** 端末を持たない task の結果。 */
        public static Outcome none() {
            return new Outcome(Optional.empty(), Optional.empty());
        }
    }

    /** CICS の user ID の形 (8 文字まで) に収まる principal 名だけを user ID にする。推測で切り詰めない。 */
    public static Optional<String> userIdOf(String principal) {
        String upper = Objects.requireNonNull(principal, "principal").toUpperCase(Locale.ROOT);
        return USER.matcher(upper).matches() ? Optional.of(upper) : Optional.empty();
    }

    /**
     * 端末で task を起こし、IMMEDIATE の連鎖を終えたあとの端末の次の疑似会話と、最後に送った画面を返す。
     *
     * @param start     START で起こす task なら RETRIEVE が読むデータ
     * @param keyPrefix 冪等キーの接頭。task ごとに新しいキーを作る
     */
    public static Outcome run(CicsTaskCoordinator coordinator, TransId transaction, String owner,
                              Optional<String> userId, String terminalId, Optional<CicsStartData> start,
                              String keyPrefix) {
        Objects.requireNonNull(coordinator, "coordinator");
        Objects.requireNonNull(terminalId, "terminalId");
        CicsTaskReply reply = coordinator.launch(new CicsTaskRequest(transaction.value(), owner,
                start.map(CicsStartData::payload).orElse(CicsPayload.empty()),
                Optional.empty(), key(keyPrefix), Optional.empty(), Optional.of(terminalId), userId, start));
        Optional<CicsTerminalScreen> screen = reply.screen();
        for (int step = 0; reply.immediateNext(); step++) {
            if (step >= MAX_IMMEDIATE) {
                throw new IllegalStateException("RETURN IMMEDIATE chain exceeded " + MAX_IMMEDIATE + " tasks");
            }
            ConversationEnvelope next = reply.nextConversation().orElseThrow();
            reply = coordinator.launch(new CicsTaskRequest(next.nextTransaction().value(), owner, next.payload(),
                    Optional.of(new ConversationReference(next.id(), next.version())), key(keyPrefix),
                    Optional.empty(), Optional.of(terminalId), userId));
            if (reply.screen().isPresent()) {
                screen = reply.screen();
            }
        }
        return new Outcome(reply.nextConversation(), screen);
    }

    private static IdempotencyKey key(String prefix) {
        return new IdempotencyKey(prefix + "-" + UUID.randomUUID());
    }
}
