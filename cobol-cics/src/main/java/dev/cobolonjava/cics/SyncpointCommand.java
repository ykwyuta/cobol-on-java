package dev.cobolonjava.cics;

import java.util.Objects;

/** 現在のUOWを完了し、次の資源アクセスまで新UOWを開始しない。 */
public record SyncpointCommand(SyncpointAction action) implements CicsCommand {

    public SyncpointCommand {
        Objects.requireNonNull(action, "action");
    }
}
