package dev.cobolonjava.cics;

import java.util.Objects;

public record SyncpointCompletion(SyncpointAction action) implements CicsControl {

    public SyncpointCompletion {
        Objects.requireNonNull(action, "action");
    }
}
