package dev.cobolonjava.cics;

/** 初期programと後続のCICS制御を実行し、最終RETURNへ正規化するport。 */
@FunctionalInterface
public interface CicsTaskProgramPort {

    TaskCompletion execute(
            CicsTransactionDefinition definition,
            CicsPayload input,
            CicsTaskContext task,
            SyncpointPort syncpoints);
}
