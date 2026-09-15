package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 一時データのキューの trigger level で起こす task (設計 83 §6)。
 *
 * @param queue       trigger level に達したキュー
 * @param transaction キューの定義の TRANSID
 * @param owner       task を動かす owner。ATIFACILITY(FILE) では region の構成の owner 名
 * @param userId      定義の USERID か、region の既定の user ID
 */
public record CicsTransientDataTrigger(String queue, TransId transaction, String owner, Optional<String> userId) {

    public CicsTransientDataTrigger {
        Objects.requireNonNull(queue, "queue");
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(userId, "userId");
    }

    /**
     * coordinator で trigger の task を起こす launcher。ATIFACILITY(FILE) の task は端末と COMMAREA を持たない。
     * task が ABEND すれば例外が返り、trigger は次の QZERO まで次の task を起こさない。
     */
    public static Consumer<CicsTransientDataTrigger> launching(Supplier<CicsTaskCoordinator> coordinator) {
        return trigger -> coordinator.get().launch(new CicsTaskRequest(trigger.transaction().value(), trigger.owner(),
                CicsPayload.empty(), Optional.empty(), new IdempotencyKey("ati-" + UUID.randomUUID()),
                Optional.empty(), Optional.empty(), trigger.userId()));
    }
}
