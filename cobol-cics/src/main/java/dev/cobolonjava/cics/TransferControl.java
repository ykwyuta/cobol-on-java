package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.interop.ProgramId;
import java.util.Objects;

/** XCTLで次programへstackを増やさず遷移するための内部結果。 */
public record TransferControl(ProgramId target, CicsPayload payload) implements CicsControl {

    public TransferControl {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(payload, "payload");
    }
}
