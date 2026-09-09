package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.interop.ProgramId;
import java.util.Objects;

/** 同じtask/UOWのままprogramを置換し、呼出元へ戻らない。 */
public record XctlCommand(ProgramId target, CicsPayload payload) implements CicsCommand {

    public XctlCommand {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(payload, "payload");
    }
}
