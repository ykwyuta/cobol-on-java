package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 一時データのキューの trigger level で起こす task (設計 83 §6)。
 *
 * @param queue       trigger level に達したキュー
 * @param transaction キューの定義の TRANSID
 * @param owner       task を動かす owner。ATIFACILITY(FILE) は region の構成の owner 名、TERMINAL は端末の owner
 * @param userId      FILE は定義の USERID か region の既定の user ID、TERMINAL は端末の owner から作った user ID
 * @param terminalId  ATIFACILITY(TERMINAL) の端末。FILE なら空
 */
public record CicsTransientDataTrigger(String queue, TransId transaction, String owner, Optional<String> userId,
                                       Optional<String> terminalId) {

    public CicsTransientDataTrigger {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(terminalId, "terminalId");
    }

    /**
     * coordinator で trigger の task を起こし、端末の次の疑似会話を返す launcher。
     *
     * <p>ATIFACILITY(FILE) の task は端末と COMMAREA を持たず、会話を返さない。TERMINAL の task は端末で起こし、
     * RETURN IMMEDIATE を続ける ({@link CicsTerminalTasks#run})。task が ABEND すれば例外が返り、trigger は次の QZERO まで
     * 次の task を起こさない。
     */
    public static Function<CicsTransientDataTrigger, CicsTerminalTasks.Outcome> launching(
            Supplier<CicsTaskCoordinator> coordinator) {
        return trigger -> {
            if (trigger.terminalId().isPresent()) {
                return CicsTerminalTasks.run(coordinator.get(), trigger.transaction(), trigger.owner(),
                        trigger.userId(), trigger.terminalId().orElseThrow(), Optional.empty(), "ati");
            }
            coordinator.get().launch(new CicsTaskRequest(trigger.transaction().value(), trigger.owner(),
                    CicsPayload.empty(), Optional.empty(), new IdempotencyKey("ati-" + UUID.randomUUID()),
                    Optional.empty(), Optional.empty(), trigger.userId()));
            return CicsTerminalTasks.Outcome.none();
        };
    }
}
