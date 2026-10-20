package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.file.DataSet;
import dev.cobolonjava.runtime.file.FileStatus;
import dev.cobolonjava.runtime.file.OpenMode;
import dev.cobolonjava.runtime.file.Organization;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 順編成の出力 ({@code OPEN} / {@code PUT} / {@code CLOSE}) を DD 名のファイルへつなぐ OS のサービス。
 *
 * <p>マクロの展開と制御ブロックの形は {@link SystemMacros} が決める。IBM のマクロの原文は参照していない。
 * 振る舞いは DFSMS Macro Instructions for Data Sets の説明に従う。
 * <ul>
 *   <li>{@code OPEN} (SVC 19) は R1 の表の DCB を開き、R15 に 0 を返す。開けなければ 8 を返し、DCB は
 *       開かない</li>
 *   <li>{@code PUT} は OPEN が DCB に置いた入口へ分岐して入る。移動方式 (MACRF=PM) では R0 の番地から
 *       LRECL バイトを 1 レコードとして書く。開く前の DCB では入口が 0 なので、0 番地へ分岐して止まる</li>
 *   <li>{@code CLOSE} (SVC 20) は書いたレコードをファイルへ流し込み、DCB を閉じた状態に戻す</li>
 *   <li>タスクの終わりに開いたままの DCB は閉じる (z/OS もタスクの終了で閉じる)</li>
 * </ul>
 */
final class DataManagement implements Cpu.Supervisor {

    /** {@code OPEN} の SVC の番号。 */
    static final int SVC_OPEN = 19;
    /** {@code CLOSE} の SVC の番号。 */
    static final int SVC_CLOSE = 20;

    private final ProgramContext context;
    /** {@code PUT} の入口の番地。番地空間に取っておいた区画の先頭である。 */
    private final int putGate;
    /** 開いている DCB の番地と、そのファイル。 */
    private final Map<Integer, Open> open = new LinkedHashMap<>();

    private record Open(DataSet file, int recordLength) {
    }

    DataManagement(ProgramContext context, int putGate) {
        this.context = context;
        this.putGate = putGate;
    }

    @Override
    public boolean call(int number, Cpu cpu) {
        if (number != SVC_OPEN && number != SVC_CLOSE) {
            return false;
        }
        int list = cpu.register(1) & 0x7FFFFFFF;
        int result = 0;
        for (int at = list; ; at += 4) {
            int entry = cpu.memory().getInt(at);
            int option = (entry >>> 24) & 0x7F;
            int dcb = entry & 0x00FFFFFF;
            if (dcb != 0) {
                result = Math.max(result, number == SVC_OPEN ? open(cpu, dcb, option) : close(cpu, dcb));
            }
            if (entry < 0) {
                break;
            }
        }
        cpu.setRegister(15, result);
        return true;
    }

    private int open(Cpu cpu, int dcb, int option) {
        AddressSpace memory = cpu.memory();
        if (open.containsKey(dcb)) {
            // すでに開いている DCB をもう一度開くのは誤りである。黙って開き直さない
            return 8;
        }
        OpenMode mode = switch (option) {
            case SystemMacros.OPTION_OUTPUT -> OpenMode.OUTPUT;
            case SystemMacros.OPTION_EXTEND -> OpenMode.EXTEND;
            default -> throw new MachineException(MachineException.OPERATION,
                    "OPEN option " + option + " is not supported yet");
        };
        if (context == null) {
            throw new MachineException(MachineException.OPERATION,
                    "OPEN needs a program context that knows the DD names");
        }
        String ddName = context.codePage().decode(memory.read(dcb + SystemMacros.DCB_DDNAME, 8)).trim();
        int recordLength = memory.getShort(dcb + SystemMacros.DCB_LRECL) & 0xFFFF;
        DataSet file = context.file("HLASM DCB " + ddName, ddName, Organization.SEQUENTIAL,
                RecordFormat.FIXED, recordLength);
        String status = file.open(mode, false);
        if (!FileStatus.OK.equals(status)) {
            return 8;
        }
        open.put(dcb, new Open(file, recordLength));
        memory.set(dcb + SystemMacros.DCB_OFLGS,
                (byte) (memory.get(dcb + SystemMacros.DCB_OFLGS) | SystemMacros.OFLGS_OPEN));
        memory.putInt(dcb + SystemMacros.DCB_ACCESS, putGate);
        return 0;
    }

    private int close(Cpu cpu, int dcb) {
        Open entry = open.remove(dcb);
        if (entry == null) {
            // 開いていない DCB を閉じても何もしない (DFSMS の説明でも誤りにならない)
            return 0;
        }
        entry.file().close();
        AddressSpace memory = cpu.memory();
        memory.set(dcb + SystemMacros.DCB_OFLGS,
                (byte) (memory.get(dcb + SystemMacros.DCB_OFLGS) & ~SystemMacros.OFLGS_OPEN));
        memory.putInt(dcb + SystemMacros.DCB_ACCESS, 0);
        return 0;
    }

    @Override
    public boolean gate(int address, Cpu cpu) {
        if (address != putGate) {
            return false;
        }
        int dcb = cpu.register(1) & 0x7FFFFFFF;
        Open entry = open.get(dcb);
        if (entry == null) {
            throw new MachineException(MachineException.OPERATION, "PUT to a DCB that is not open");
        }
        byte[] record = cpu.memory().read(cpu.register(0) & 0x7FFFFFFF, entry.recordLength());
        String status = entry.file().write(record);
        if (!FileStatus.OK.equals(status)) {
            throw new MachineException(MachineException.OPERATION,
                    "PUT failed with file status " + status);
        }
        cpu.branchTo(cpu.register(14));
        return true;
    }

    /** タスクの終わり。開いたままの DCB を閉じ、書いたレコードを流し込む。 */
    void closeAll() {
        for (Open entry : open.values()) {
            entry.file().close();
        }
        open.clear();
    }
}
