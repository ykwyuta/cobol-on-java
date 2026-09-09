package dev.cobolonjava.cics;

/** 生成COBOLとframework adapterの間で交換するCICS制御コマンド。 */
public sealed interface CicsCommand
        permits LinkCommand, XctlCommand, ReturnCommand, SyncpointCommand {
}
