package dev.cobolonjava.cics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * RUN TRANSID が起こす子の task (暫定判断 P-140)。
 *
 * @param transaction 子の transaction
 * @param channelName RUN の CHANNEL。子はこの名前で現在の channel を開く。書かなければ空
 * @param containers  RUN を出した時点の channel の container の写し
 * @param owner       親の task の owner。子もこの owner で動く
 * @param userId      親の task の user ID
 */
public record CicsAsyncChild(TransId transaction, Optional<String> channelName, Map<String, byte[]> containers,
                             String owner, Optional<String> userId) {

    public CicsAsyncChild {
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(channelName, "channelName");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(userId, "userId");
        Map<String, byte[]> copied = new LinkedHashMap<>();
        Objects.requireNonNull(containers, "containers").forEach((name, value) -> copied.put(name, value.clone()));
        containers = java.util.Collections.unmodifiableMap(copied);
    }
}
