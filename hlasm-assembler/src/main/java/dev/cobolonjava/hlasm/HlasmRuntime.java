package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.interop.ProgramParameter;
import dev.cobolonjava.runtime.interop.ProgramSignature;
import dev.cobolonjava.runtime.procedure.ProcedureManifest;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import java.util.ArrayList;
import java.util.List;

/**
 * 生成クラスから呼ばれる HLASM の実行時。
 *
 * <p>入口の状態は OS の標準リンケージに合わせる。これが調査レポートで「既存ポートに着地する
 * 良い知らせ」と書いたところである。{@code R1} が指す番地の並びが {@code DataView[]} に対応し、
 * 決定 0002 のバイト志向の呼出し契約とそのまま噛み合う。
 *
 * <table>
 *   <caption>入口でのレジスタ</caption>
 *   <tr><td>R1</td><td>引数表の番地。4 バイトの番地が並び、最後の番地は上位ビットが 1</td></tr>
 *   <tr><td>R13</td><td>呼ぶ側の 72 バイトの退避域</td></tr>
 *   <tr><td>R14</td><td>戻り番地。ここへ分岐したら実行を終える</td></tr>
 *   <tr><td>R15</td><td>入口の番地。戻るときは戻りコード</td></tr>
 * </table>
 */
public final class HlasmRuntime {

    /** 呼ぶ側が用意する退避域の大きさ。 */
    public static final int SAVE_AREA_BYTES = 72;

    private HlasmRuntime() {
    }

    /** 組み立ててから実行する。戻り値は R15 である。 */
    public static int execute(String fileName, String source, ProgramContext context,
                              DataView[] arguments) {
        Assembler.Result assembled = Assembler.assemble(fileName, source);
        if (!assembled.succeeded()) {
            throw new HlasmExecutionException(assembled.diagnostics().get(0).toString());
        }
        return execute(assembled.module(), context, arguments);
    }

    public static int execute(ObjectModule module, ProgramContext context, DataView[] arguments) {
        // context は増分 3 (OS のサービス) で使う。今はどの命令も外へ触れない
        byte[] text = module.text();
        byte[] saveArea = new byte[SAVE_AREA_BYTES];
        byte[] parameterList = new byte[Math.max(4, arguments.length * 4)];

        AddressSpace.Builder builder = AddressSpace.builder();
        int textBase = builder.place(module.name(), text);
        int saveBase = builder.place("SAVEAREA", saveArea);
        int listBase = builder.place("PARMLIST", parameterList);
        // 引数は呼ぶ側の記憶域そのものを指す。同じ記憶域を指す引数が 2 つあれば、
        // 番地空間でも同じ番地に重なる (決定 0002 のエイリアシング)
        int[] argumentAddress = new int[arguments.length];
        for (int k = 0; k < arguments.length; k++) {
            DataView view = arguments[k];
            int base = builder.place("ARG" + (k + 1), view.storage().array());
            argumentAddress[k] = base + view.offset();
        }
        AddressSpace memory = builder.build();

        for (int k = 0; k < arguments.length; k++) {
            int address = argumentAddress[k];
            // 最後の番地は上位ビットを立てる。これが引数表の終わりの印である
            if (k == arguments.length - 1) {
                address |= 0x80000000;
            }
            memory.putInt(listBase + k * 4, address);
        }

        Cpu cpu = new Cpu(memory);
        cpu.setRegister(1, listBase);
        cpu.setRegister(13, saveBase);
        cpu.setRegister(14, Cpu.RETURN_SENTINEL);
        cpu.setRegister(15, textBase + module.entryOffset());
        try {
            return cpu.run(textBase + module.entryOffset());
        } catch (MachineException failure) {
            // 割込みは観測できる結果なので、実行時の例外として包む。
            // RunawayProgramException は包まずに通す。測る側がそれと分かる必要がある
            throw new HlasmExecutionException(module.name() + ": " + failure.getMessage(), failure);
        }
    }

    /** 入口の引数は参照渡しであり、実際の範囲は呼ぶ側が決める。 */
    public static ProgramSignature signature(String fileName, String source) {
        Assembler.Result assembled = Assembler.assemble(fileName, source);
        if (!assembled.succeeded()) {
            return null;
        }
        // 引数の個数も長さも、原文からは分からない。R1 の表を何個読むかを決めるのは
        // プログラム自身である。だから署名は「個数を問わない」形にする
        List<ProgramParameter> parameters = new ArrayList<>();
        return ProgramSignature.of(assembled.module().name(), parameters);
    }

    public static ProcedureManifest procedureManifest(String fileName, String source) {
        Assembler.Result assembled = Assembler.assemble(fileName, source);
        return assembled.succeeded()
                ? ProcedureManifest.of(assembled.module().name(), List.of()) : null;
    }

    /** 組み立てまたは実行が続けられなくなったこと。 */
    public static final class HlasmExecutionException extends RuntimeException {

        public HlasmExecutionException(String message) {
            super(message);
        }

        public HlasmExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
