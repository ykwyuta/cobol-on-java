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
        byte[] text = java.util.Base64.getDecoder().decode(encodedText);
        ObjectModule module = new ObjectModule(name, text, entryOffset,
                java.util.Map.of(), java.util.Map.of(), List.of());
        return execute(module, context, arguments);
    }

    /** 生成クラスが持つ機械語を戻す。{@code initialStorage()} がこれを返す。 */
    public static byte[] decode(String encodedText) {
        return java.util.Base64.getDecoder().decode(encodedText);
    }

    /**
     * 生成クラスから動かす。
     *
     * <p>機械語は<b>プログラムの記憶域そのもの</b>に置く。ランタイムは副プログラムの記憶域を
     * 呼び出しをまたいで持ち続けるので、モジュールの中の {@code DS} / {@code DC} の値も
     * 呼び出しをまたいで残る。ホストで載せたままのモジュールが値を持ち続けるのと同じである。
     * 以前は呼ばれるたびに機械語を写し直していたので、モジュールの中の作業域が毎回初期値へ
     * 戻っていた。
     *
     * @param relocations 載せた番地を足す欄。{@code 位置:長さ;...}
     * @param externals   外の記号の番地を入れる欄。{@code 位置:長さ:名前;...}
     * @param loader      このプログラムを読み込んだもの。外の記号をここから引く
     */
    public static void executeLoaded(String name, dev.cobolonjava.runtime.storage.Storage storage,
                                     String encodedText, int entryOffset, String relocations,
                                     String externals, ProgramContext context,
                                     DataView[] arguments, ClassLoader loader) {
        List<ObjectModule.Relocation> fields = new ArrayList<>();
        for (String one : relocations.isEmpty() ? new String[0] : relocations.split(";")) {
            String[] parts = one.split(":");
            fields.add(new ObjectModule.Relocation(Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1])));
        }
        List<ObjectModule.External> outside = new ArrayList<>();
        for (String one : externals.isEmpty() ? new String[0] : externals.split(";")) {
            String[] parts = one.split(":");
            outside.add(new ObjectModule.External(Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]), parts[2]));
        }
        byte[] text = storage.array();
        if (text.length == 0 && !encodedText.isEmpty()) {
            // 呼ぶ側が記憶域を用意しなかった (試験や古い呼び方)。その場かぎりの写しで動かす。
            // モジュールの中の値は呼び出しをまたいで残らない
            text = decode(encodedText);
        }
        int returnCode = run(name, text, entryOffset, fields, outside, context, arguments,
                loader);
        if (context != null) {
            // R15 は RETURN-CODE になる。ホストでも、呼ばれた側が R15 に置いた値を呼んだ
            // COBOL が RETURN-CODE として読む。以前は捨てていた
            context.setReturnCode(returnCode);
        }
    }

    public static int execute(ObjectModule module, ProgramContext context, DataView[] arguments) {
        return run(module.name(), module.text(), module.entryOffset(), module.relocations(),
                module.externals(), context, arguments, HlasmRuntime.class.getClassLoader());
    }

    /** 実行単位ごとの番地空間。呼ぶ側と呼ばれる側が同じ番地を見る。 */
    private static final java.util.Map<ProgramContext, AddressSpace> SPACES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static int run(String name, byte[] text, int entryOffset,
                           List<ObjectModule.Relocation> relocations,
                           List<ObjectModule.External> externals, ProgramContext context,
                           DataView[] arguments, ClassLoader loader) {
        AddressSpace memory = context == null ? AddressSpace.empty()
                : SPACES.computeIfAbsent(context, ignored -> AddressSpace.empty());
        byte[] saveArea = new byte[SAVE_AREA_BYTES];
        byte[] parameterList = new byte[Math.max(4, arguments.length * 4)];

        int textBase = memory.place(name, text);
        if (memory.firstRelocation(text)) {
            // 番地定数は節の先頭からの変位で組み立ててある。載せた番地を足して番地にする
            for (ObjectModule.Relocation field : relocations) {
                patch(text, field.offset(), field.length(),
                        read(text, field.offset(), field.length()) + textBase);
            }
            for (ObjectModule.External field : externals) {
                patch(text, field.offset(), field.length(),
                        memory.externalAddress(field.name()));
            }
        }
        int saveBase = memory.place("SAVEAREA", saveArea);
        int listBase = memory.place("PARMLIST", parameterList);
        try {
            // 引数は呼ぶ側の記憶域そのものを指す。同じ記憶域を指す引数が 2 つあれば、
            // 番地空間でも同じ番地に重なる (決定 0002 のエイリアシング)
            for (int k = 0; k < arguments.length; k++) {
                DataView view = arguments[k];
                int address = view == null ? 0
                        : memory.place("ARG" + (k + 1), view.storage().array()) + view.offset();
                // 最後の番地は上位ビットを立てる。これが引数表の終わりの印である
                if (k == arguments.length - 1) {
                    address |= 0x80000000;
                }
                memory.putInt(listBase + k * 4, address);
            }

            Cpu cpu = new Cpu(memory);
            if (context != null) {
                cpu.setServices(new SystemServices(context, memory, loader));
            }
            cpu.setRegister(1, listBase);
            cpu.setRegister(13, saveBase);
            cpu.setRegister(14, Cpu.RETURN_SENTINEL);
            cpu.setRegister(15, textBase + entryOffset);
            return cpu.run(textBase + entryOffset);
        } catch (MachineException failure) {
            // 割込みは観測できる結果なので、実行時の例外として包む。
            // RunawayProgramException は包まずに通す。測る側がそれと分かる必要がある
            throw new HlasmExecutionException(name + ": " + failure.getMessage(), failure);
        } finally {
            memory.release(parameterList);
            memory.release(saveArea);
        }
    }

    private static int read(byte[] text, int offset, int length) {
        int value = 0;
        // 8 byte の欄は下位 4 byte が番地である
        for (int k = Math.max(offset, offset + length - 4); k < offset + length; k++) {
            value = value << 8 | (text[k] & 0xFF);
        }
        return value;
    }

    private static void patch(byte[] text, int offset, int length, int value) {
        for (int k = offset + length - 1; k >= Math.max(offset, offset + length - 4); k--) {
            text[k] = (byte) value;
            value >>>= 8;
        }
    }

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
