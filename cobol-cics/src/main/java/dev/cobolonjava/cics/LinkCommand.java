package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.interop.ProgramId;
import java.util.Objects;

/** 同じtask/session/UOW内で子programを呼び、呼出元へ戻る。 */
public record LinkCommand(ProgramId target, CicsPayload payload) implements CicsCommand {

    public LinkCommand {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(payload, "payload");
    }
}
