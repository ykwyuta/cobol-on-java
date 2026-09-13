package dev.cobolonjava.cics;

/** 選択済みUOW profileへCICS SYNCPOINTを委譲する最小境界。 */
@FunctionalInterface
public interface SyncpointPort {

    void syncpoint(SyncpointAction action, CicsTaskContext task);
}
