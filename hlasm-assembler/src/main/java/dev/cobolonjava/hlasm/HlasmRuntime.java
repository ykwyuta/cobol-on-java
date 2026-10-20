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

    /** 生成クラスに固定した機械語を実行する。翻訳元のライブラリは実行時に不要。 */
    public static int executeEncoded(String name, String encodedText, int entryOffset,
                                     ProgramContext context, DataView[] arguments) {
        return execute(decodeModule(name, encodedText, entryOffset, ""), context, arguments);
    }

    /**
     * 生成クラスに固定した機械語を、番地定数の直し方と一緒に実行する。
     *
     * @param relocations {@link #encodeRelocations} の綴り
     * @param loader      生成クラスを読み込んだもの。{@code V(名前)} の呼び先は同じところから引く
     */
    public static int executeEncoded(String name, String encodedText, int entryOffset, String relocations,
                                     ProgramContext context, DataView[] arguments, ClassLoader loader) {
        return execute(decodeModule(name, encodedText, entryOffset, relocations), context, arguments,
                loader);
    }

    /** 生成クラスが持つ綴りから制御節を作り直す。 */
    public static ObjectModule decodeModule(String name, String encodedText, int entryOffset,
                                            String relocations) {
        byte[] text = java.util.Base64.getDecoder().decode(encodedText);
        List<ObjectModule.Relocation> list = new ArrayList<>();
        for (String entry : relocations.isEmpty() ? new String[0] : relocations.split(";")) {
            String[] parts = entry.split(",", -1);
            list.add(new ObjectModule.Relocation(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    parts[2].isEmpty() ? null : parts[2]));
        }
        return new ObjectModule(name, text, entryOffset, java.util.Map.of(), java.util.Map.of(), List.of(),
                list);
    }

    /** 番地定数の直し方を生成クラスへ持たせる綴り。{@code 変位,長さ,外部の名前;...} である。 */
    static String encodeRelocations(ObjectModule module) {
        StringBuilder out = new StringBuilder();
        for (ObjectModule.Relocation relocation : module.relocations()) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(relocation.offset()).append(',').append(relocation.length()).append(',')
                    .append(relocation.external() == null ? "" : relocation.external());
        }
        return out.toString();
    }

    public static int execute(ObjectModule module, ProgramContext context, DataView[] arguments) {
        return execute(module, context, arguments, null);
    }

    /**
     * 実行する。{@code V(名前)} の呼び先は {@code loader} から引いて同じ番地空間に載せる
     * ({@link ModuleLinker})。
     */
    public static int execute(ObjectModule module, ProgramContext context, DataView[] arguments,
                              ClassLoader loader) {
        byte[] saveArea = new byte[SAVE_AREA_BYTES];
        byte[] parameterList = new byte[Math.max(4, arguments.length * 4)];

        AddressSpace.Builder builder = AddressSpace.builder();
        ModuleLinker.Placed main = new ModuleLinker(context, loader, builder).link(module);
        int textBase = main.base();
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

    /** 署名が受ける引数の数の上限。 */
    static final int ARGUMENT_LIMIT = 255;

    /** 入口の引数は参照渡しであり、実際の範囲は呼ぶ側が決める。 */
    public static ProgramSignature signature(String fileName, String source) {
        Assembler.Result assembled = Assembler.assemble(fileName, source);
        if (!assembled.succeeded()) {
            return null;
        }
        // 引数の個数も長さも、原文からは分からない。R1 の表を何個読むかを決めるのは
        // プログラム自身である。だから署名は「個数を問わない」形にする。以前は空の並びにしており、
        // それは「引数 0 個」と読まれて、COBOL の CALL 'BUMP' USING X が署名の不一致で止まっていた。
        // 上限の 255 は、この処理系が置く割り切りである (実機の引数の表に決まった上限は無い)
        return signatureForModule(assembled.module().name());
    }

    /** 組立て済みモジュールの署名。 */
    public static ProgramSignature signatureForModule(String name) {
        List<ProgramParameter> parameters = new ArrayList<>();
        for (int i = 1; i <= ARGUMENT_LIMIT; i++) {
            parameters.add(new ProgramParameter("ARG" + i, 0, Short.MAX_VALUE,
                    ProgramParameter.Presence.OPTIONAL, ProgramParameter.PassingMode.REFERENCE,
                    ProgramParameter.Direction.INOUT, "hlasm-r1-v1"));
        }
        return ProgramSignature.of(name, parameters);
    }

    public static ProcedureManifest procedureManifest(String fileName, String source) {
        Assembler.Result assembled = Assembler.assemble(fileName, source);
        return assembled.succeeded()
                ? procedureManifestForModule(assembled.module().name()) : null;
    }

    /** 組立て済みモジュールの手続き情報。 */
    public static ProcedureManifest procedureManifestForModule(String name) {
        return ProcedureManifest.of(name, List.of());
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
