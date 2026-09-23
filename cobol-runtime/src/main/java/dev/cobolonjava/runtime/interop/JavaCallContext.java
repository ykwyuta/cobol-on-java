package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.program.Ops;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import java.util.Objects;

/** 登録済み Java サブルーチンへ公開する、範囲を限定した実行コンテキスト。 */
public final class JavaCallContext {

    private final ProgramId programId;
    private final ProgramContext context;
    private final ClassLoader loader;

    JavaCallContext(ProgramId programId, ProgramContext context, ClassLoader loader) {
        this.programId = Objects.requireNonNull(programId, "programId");
        this.context = Objects.requireNonNull(context, "context");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public ProgramId programId() {
        return programId;
    }

    public CodePage codePage() {
        return context.codePage();
    }

    /** 実行単位の時計。地方時はこの時計の時間帯である。 */
    public java.time.Clock clock() {
        return context.clock();
    }

    public int returnCode() {
        return context.returnCode();
    }

    public void setReturnCode(int value) {
        context.setReturnCode(value);
    }

    /**
     * 呼んだ側の実行単位で、POINTER の値 (AddressSpace の番号、P-150) が指す記憶域の位置。
     * この実行単位で振った番号でなければ {@code null}。POINTER を引数に受け取るサブルーチン
     * (PL/I の DL/I 呼出しの PCB など) が、指す先を引くのに使う。
     */
    public dev.cobolonjava.runtime.program.AddressSpace.Location locate(int address) {
        return dev.cobolonjava.runtime.program.AddressSpace.of(context).locate(address);
    }

    /** 同じ実行単位から、登録済み COBOL または Java プログラムへ再入する。 */
    public void call(String name, DataView... arguments) {
        Ops.call(context, ProgramId.of(name).value(), loader, arguments);
    }
}
