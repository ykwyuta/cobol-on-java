package dev.cobolonjava.cics;

/** Java例外ではなくtask coordinatorが解釈するCICS制御結果。 */
public sealed interface CicsControl
        permits ContinueControl, TransferControl, TaskCompletion, SyncpointCompletion {
}
