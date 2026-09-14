package dev.cobolonjava.cics;

import dev.cobolonjava.cics.bms.BmsTerminalInput;
import java.util.Objects;
import java.util.Optional;

/**
 * transport検証とBMS decodeを完了した後の中立task入力。
 *
 * @param terminalInput 端末から届いたAID、cursor、変更field。直前の画面との照合はRECEIVE MAPが行う
 * @param terminalId    要求を出した端末の名前 (EIBTRMID)。adapterが利用者の端末ごとに決める
 * @param userId        要求を出した利用者のCICS user ID。adapterが認証から決める
 */
public record CicsTaskRequest(
        String transactionId,
        String owner,
        CicsPayload payload,
        Optional<ConversationReference> conversation,
        IdempotencyKey idempotencyKey,
        Optional<BmsTerminalInput> terminalInput,
        Optional<String> terminalId,
        Optional<String> userId) {

    public CicsTaskRequest {
        Objects.requireNonNull(transactionId, "transactionId");
        owner = requireOwner(owner);
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(terminalInput, "terminalInput");
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(userId, "userId");
    }

    public CicsTaskRequest(String transactionId, String owner, CicsPayload payload,
                           Optional<ConversationReference> conversation, IdempotencyKey idempotencyKey,
                           Optional<BmsTerminalInput> terminalInput, Optional<String> terminalId) {
        this(transactionId, owner, payload, conversation, idempotencyKey, terminalInput, terminalId,
                Optional.empty());
    }

    public CicsTaskRequest(String transactionId, String owner, CicsPayload payload,
                           Optional<ConversationReference> conversation, IdempotencyKey idempotencyKey,
                           Optional<BmsTerminalInput> terminalInput) {
        this(transactionId, owner, payload, conversation, idempotencyKey, terminalInput, Optional.empty());
    }

    public CicsTaskRequest(String transactionId, String owner, CicsPayload payload,
                           Optional<ConversationReference> conversation, IdempotencyKey idempotencyKey) {
        this(transactionId, owner, payload, conversation, idempotencyKey, Optional.empty());
    }

    private static String requireOwner(String value) {
        Objects.requireNonNull(value, "owner");
        value = value.strip();
        if (value.isEmpty() || value.length() > 256
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("owner has an unsupported format");
        }
        return value;
    }
}
