package dev.cobolonjava.cics;

import java.util.Objects;
import java.util.Optional;

/** taskを異常終了するABEND command。 */
public record AbendCommand(
        Optional<CicsAbendCode> code,
        boolean cancelHandlers,
        boolean noDump) implements CicsCommand {

    public AbendCommand {
        Objects.requireNonNull(code, "code");
        code.ifPresent(value -> {
            if (value.value().startsWith("A")) {
                throw new IllegalArgumentException(
                        "application ABCODE must not start with reserved letter A");
            }
            if (value.value().indexOf('?') >= 0) {
                throw new IllegalArgumentException(
                        "application ABCODE must not contain question marks");
            }
        });
    }

    public static AbendCommand unspecified(boolean cancelHandlers) {
        return new AbendCommand(Optional.empty(), cancelHandlers, true);
    }

    public static AbendCommand user(
            CicsAbendCode code, boolean cancelHandlers, boolean noDump) {
        return new AbendCommand(Optional.of(Objects.requireNonNull(code, "code")),
                cancelHandlers, noDump);
    }

    public CicsAbendCode effectiveCode() {
        return code.orElseGet(CicsAbendCode::unspecified);
    }

    public boolean dumpRequested() {
        return code.isPresent() && !noDump;
    }
}
