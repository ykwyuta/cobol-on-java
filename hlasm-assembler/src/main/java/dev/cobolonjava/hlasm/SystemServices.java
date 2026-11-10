package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.file.DataSet;
import dev.cobolonjava.runtime.file.FileStatus;
import dev.cobolonjava.runtime.file.OpenMode;
import dev.cobolonjava.runtime.file.Organization;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.program.ProgramReturn;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.ArrayList;
import java.util.List;

/**
 * 機械語の外にあるもの。外のプログラムの呼び出しと、OS のサービス (SVC)。
 *
 * <p>どちらも実行単位の入口 ({@link ProgramContext}) を通す。プログラムは COBOL・PL/I と
 * 同じ名前空間から引き、ファイルは COBOL と同じ DD の割当てから引く。HLASM の副プログラムが
 * 書いたファイルを COBOL が読める、という当たり前を保つためである。
 *
 * <p>サービスの分け方は Zeus (同じ権利者の処理系) の {@code hlasm/data_management.rs} と
 * {@code hlasm/services.rs} を見て決めた。
 */
final class SystemServices implements Cpu.Services {

    private final ProgramContext context;
    private final AddressSpace memory;
    private final ClassLoader loader;

    SystemServices(ProgramContext context, AddressSpace memory, ClassLoader loader) {
        this.context = context;
        this.memory = memory;
        this.loader = loader;
    }

    /**
     * 外の記号の入口へ分岐した ({@code L 15,=V(名前)} / {@code BASR 14,15})。
     *
     * <p>標準リンケージである。R1 が引数の番地の表を指し、最後の番地の上位ビットが立っている。
     * 番地は呼ぶ側の記憶域を指すので、その記憶域の上の領域として渡す。<b>長さは分からない</b>
     * (機械語には書かれていない) ので、記憶域の終わりまでを渡す。受ける側の宣言が長さを決める。
     *
     * <p>戻りコードは R15 に置く。COBOL の RETURN-CODE と同じ場所から読む。
     */
    @Override
    public void call(String name, Cpu cpu) {
        List<DataView> arguments = new ArrayList<>();
        int list = cpu.register(1) & 0x7FFFFFFF;
        // R1 が記憶域を指していなければ、引数は無い
        while (arguments.size() < HlasmRuntime.ARGUMENT_LIMIT && memory.regionAt(list) != null
                && memory.regionAt(list + 3) != null) {
            int word = memory.getInt(list);
            int address = word & 0x7FFFFFFF;
            AddressSpace.Region region = memory.regionAt(address);
            if (region == null) {
                // 番地 0 は渡さなかった引数 (OMITTED)。それ以外は表ではなかったと見る
                if (address != 0) {
                    arguments.clear();
                    break;
                }
                arguments.add(null);
            } else {
                int offset = address - region.base();
                arguments.add(Storage.wrap(region.bytes())
                        .view(offset, region.length() - offset));
            }
            if (word < 0) {
                break;
            }
            list += 4;
        }
        ProgramContext.Loaded target = context.resolve(name, loader);
        context.setReturnCode(0);
        context.enterCall(name, target.storage(), target.program().storageMap(),
                target.program());
        try {
            target.program().run(target.storage(), context,
                    arguments.toArray(new DataView[0]));
        } catch (ProgramReturn returned) {
            // 呼ばれた側が戻っただけである
        }
        context.leave();
        cpu.setRegister(15, context.returnCode());
    }

    // --- SVC ---

    /** DCB の欄の位置。{@link Assembler#dataControlBlock} が置いた場所である。 */
    private static final int DCB_EODAD = 33;
    private static final int DCB_RECFM = 36;
    private static final int DCB_DDNAME = 40;
    private static final int DCB_OPEN_FLAGS = 48;
    private static final int DCB_LRECL = 82;
    /** 開いている印。DCBOFLGS の DCBOFOPN である。 */
    private static final int DCB_OPENED = 0x10;

    @Override
    public void supervisorCall(int number, Cpu cpu) {
        switch (number) {
            case 19 -> open(cpu);
            case 20 -> close(cpu);
            case Assembler.SVC_GET -> get(cpu);
            case Assembler.SVC_PUT -> put(cpu);
            case Assembler.SVC_GETMAIN -> {
                // 得た記憶域は 0 で埋まっている。実機は中身を決めていないが、決めていない値に
                // 頼るプログラムは正しくないので、どちらでも同じ結果になる
                int length = cpu.register(0) & 0x00FFFFFF;
                cpu.setRegister(1, memory.place("GETMAIN", new byte[Math.max(length, 1)]));
                cpu.setRegister(15, 0);
            }
            case Assembler.SVC_FREEMAIN -> {
                AddressSpace.Region region = memory.regionAt(cpu.register(1) & 0x7FFFFFFF);
                if (region == null || !"GETMAIN".equals(region.name())) {
                    // GETMAIN で得ていない番地を返そうとした。実機は S90A などで異常終了する
                    throw new MachineException(MachineException.ADDRESSING,
                            "FREEMAIN of storage that GETMAIN did not obtain");
                }
                memory.release(region.bytes());
                cpu.setRegister(15, 0);
            }
            case Assembler.SVC_POST -> memory.putInt(cpu.register(1) & 0x7FFFFFFF,
                    0x40000000 | (cpu.register(0) & 0x3FFFFFFF));
            default -> throw new MachineException(MachineException.OPERATION,
                    "SVC " + number + " is not supported yet");
        }
    }

    private String ddName(int dcb) {
        return context.codePage().decode(memory.read(dcb + DCB_DDNAME, 8)).trim();
    }

    private DataSet file(int dcb) {
        String dd = ddName(dcb);
        boolean variable = (memory.get(dcb + DCB_RECFM) & 0xC0) == 0x40;
        int length = memory.getShort(dcb + DCB_LRECL) & 0xFFFF;
        // 名前は DD 名にする。同じ DD を開いた別のモジュールと同じファイルを見る
        return context.file(dd, dd, Organization.SEQUENTIAL,
                variable ? RecordFormat.VARIABLE : RecordFormat.FIXED, length);
    }

    /** OPEN。R1 が DCB、R0 が開き方 (0 INPUT、1 OUTPUT、2 EXTEND)。 */
    private void open(Cpu cpu) {
        int dcb = cpu.register(1) & 0x7FFFFFFF;
        OpenMode mode = switch (cpu.register(0)) {
            case 1 -> OpenMode.OUTPUT;
            case 2 -> OpenMode.EXTEND;
            default -> OpenMode.INPUT;
        };
        String status = file(dcb).open(mode, false);
        if (FileStatus.succeeded(status)) {
            memory.set(dcb + DCB_OPEN_FLAGS,
                    (byte) (memory.get(dcb + DCB_OPEN_FLAGS) | DCB_OPENED));
            cpu.setRegister(15, 0);
        } else {
            // 開けなかった DCB は開いた印が立たない。プログラムはそれを調べる
            cpu.setRegister(15, 8);
        }
    }

    private void close(Cpu cpu) {
        int dcb = cpu.register(1) & 0x7FFFFFFF;
        file(dcb).close();
        memory.set(dcb + DCB_OPEN_FLAGS,
                (byte) (memory.get(dcb + DCB_OPEN_FLAGS) & ~DCB_OPENED));
        cpu.setRegister(15, 0);
    }

    /** PUT。R1 が DCB、R0 がレコードの領域 (移動モード)。 */
    private void put(Cpu cpu) {
        int dcb = cpu.register(1) & 0x7FFFFFFF;
        int area = cpu.register(0) & 0x7FFFFFFF;
        if (area == 0) {
            throw new MachineException(MachineException.OPERATION,
                    "PUT in locate mode is not supported yet");
        }
        boolean variable = (memory.get(dcb + DCB_RECFM) & 0xC0) == 0x40;
        // 可変長は領域の頭の RDW (長さ 2 byte + 予備 2 byte) が長さを決め、RDW は書かない
        int length = variable ? (memory.getShort(area) & 0xFFFF) - 4
                : memory.getShort(dcb + DCB_LRECL) & 0xFFFF;
        byte[] record = memory.read(variable ? area + 4 : area, Math.max(0, length));
        String status = file(dcb).write(record);
        if (!FileStatus.succeeded(status)) {
            throw new MachineException(MachineException.OPERATION,
                    "PUT to " + ddName(dcb) + " failed with file status " + status);
        }
        cpu.setRegister(15, 0);
    }

    /** GET。R1 が DCB、R0 が受ける領域。終わりなら DCB の EODAD へ飛ぶ。 */
    private void get(Cpu cpu) {
        int dcb = cpu.register(1) & 0x7FFFFFFF;
        int area = cpu.register(0) & 0x7FFFFFFF;
        if (area == 0) {
            throw new MachineException(MachineException.OPERATION,
                    "GET in locate mode is not supported yet");
        }
        boolean variable = (memory.get(dcb + DCB_RECFM) & 0xC0) == 0x40;
        int length = memory.getShort(dcb + DCB_LRECL) & 0xFFFF;
        DataSet file = file(dcb);
        byte[] record = new byte[variable ? length - 4 : length];
        String status = file.read(record);
        if (FileStatus.AT_END.equals(status)) {
            int exit = (memory.getInt(dcb + DCB_EODAD - 1)) & 0x00FFFFFF;
            if (exit == 0) {
                throw new MachineException(MachineException.OPERATION,
                        "end of data on " + ddName(dcb) + " and the DCB has no EODAD");
            }
            cpu.branchTo(exit);
            return;
        }
        if (!FileStatus.succeeded(status)) {
            throw new MachineException(MachineException.OPERATION,
                    "GET from " + ddName(dcb) + " failed with file status " + status);
        }
        if (variable) {
            int actual = file instanceof dev.cobolonjava.runtime.file.SequentialDataSet sequential
                    ? sequential.lastLength() : record.length;
            memory.putShort(area, actual + 4);
            memory.putShort(area + 2, 0);
            memory.write(area + 4, java.util.Arrays.copyOf(record, actual));
        } else {
            memory.write(area, record);
        }
        cpu.setRegister(15, 0);
    }
}
