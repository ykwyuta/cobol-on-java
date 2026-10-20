package dev.cobolonjava.hlasm;

import dev.cobolonjava.runtime.program.ProgramContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 制御節を番地空間へ載せ、番地定数を直す (バインダーと program fetch に当たる)。
 *
 * <p>{@code V(名前)} は名前を引いて、その制御節も同じ番地空間に載せる。引くのは COBOL・PL/I の
 * {@code CALL} と同じプログラムの解決 ({@link ProgramContext#resolve}) であり、同じ読み込み器に
 * 入った HLASM の生成クラスが見つかれば、その機械語を取り出す ({@link HlasmModuleProvider})。
 *
 * <p>名前は<b>載せるときに全部</b>解決する。z/OS のバインダーもリンクの時に解決するからである。
 * 解決できない名前は、実機ならバインダーの警告のうえで 0 番地へ分岐して異常終了になるが、
 * ここでは載せる時点で断る (近い振る舞いで走らせるより、どこで足りなかったかが残る)。
 *
 * <p>COBOL・PL/I の副プログラムを {@code V(名前)} で呼ぶことは、まだできない。呼ぶ側の番地空間の
 * 引数表を {@code DataView} の並びへ写す長さが分からないためであり、断る。
 */
final class ModuleLinker {

    /** 載せた制御節。{@code text} は番地空間の区画の実体である。 */
    record Placed(ObjectModule module, int base, byte[] text) {

        int entry() {
            return base + module.entryOffset();
        }
    }

    private final ProgramContext context;
    private final ClassLoader loader;
    private final AddressSpace.Builder builder;
    private final Map<String, Placed> placed = new LinkedHashMap<>();

    ModuleLinker(ProgramContext context, ClassLoader loader, AddressSpace.Builder builder) {
        this.context = context;
        this.loader = loader;
        this.builder = builder;
    }

    /** 主の制御節を載せ、そこから辿れる外部の名前をすべて載せて番地を直す。 */
    Placed link(ObjectModule main) {
        Placed first = place(main);
        Deque<Placed> pending = new ArrayDeque<>();
        pending.add(first);
        while (!pending.isEmpty()) {
            Placed current = pending.poll();
            for (String name : current.module().externals()) {
                if (!placed.containsKey(name)) {
                    pending.add(place(find(name)));
                }
            }
        }
        for (Placed each : placed.values()) {
            relocate(each);
        }
        return first;
    }

    private Placed place(ObjectModule module) {
        byte[] text = module.text();
        int base = builder.place(module.name(), text);
        Placed result = new Placed(module, base, text);
        placed.put(module.name(), result);
        return result;
    }

    private ObjectModule find(String name) {
        if (context == null || loader == null) {
            throw new HlasmRuntime.HlasmExecutionException("unresolved external reference: " + name);
        }
        ProgramContext.Loaded loaded;
        try {
            loaded = context.resolve(name, loader);
        } catch (RuntimeException failure) {
            throw new HlasmRuntime.HlasmExecutionException("unresolved external reference: " + name,
                    failure);
        }
        if (!(loaded.program() instanceof HlasmModuleProvider provider)) {
            throw new HlasmRuntime.HlasmExecutionException("calling " + name
                    + " through a V-type constant is not supported yet; it is not an HLASM program");
        }
        ObjectModule module = provider.hlasmModule();
        if (!module.name().equals(name)) {
            throw new HlasmRuntime.HlasmExecutionException("the program found for " + name
                    + " defines the control section " + module.name());
        }
        return module;
    }

    private void relocate(Placed each) {
        byte[] text = each.text();
        for (ObjectModule.Relocation relocation : each.module().relocations()) {
            // V(名前) は名前の制御節の先頭 (CSECT の名前が指す番地)、A(記号) は自分の節の先頭を足す
            int base = relocation.external() == null ? each.base()
                    : placed.get(relocation.external()).base();
            int at = relocation.offset();
            int length = relocation.length();
            long value = 0;
            for (int k = 0; k < length; k++) {
                value = value << 8 | (text[at + k] & 0xFF);
            }
            long result = (value + base) & 0xFFFFFFFFL;
            if (length == 3 && result > 0xFFFFFF) {
                throw new HlasmRuntime.HlasmExecutionException(String.format(
                        "%s: the 3-byte address constant at offset %X cannot hold %X",
                        each.module().name(), at, result));
            }
            for (int k = length - 1; k >= 0; k--) {
                text[at + k] = (byte) result;
                result >>= 8;
            }
        }
    }
}
