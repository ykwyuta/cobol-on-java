package dev.cobolonjava.cics;

import java.util.Objects;

/** LINK等の完了後、現在programの実行を継続する。 */
public record ContinueControl(CicsPayload payload) implements CicsControl {

    public ContinueControl {
        Objects.requireNonNull(payload, "payload");
    }
}
