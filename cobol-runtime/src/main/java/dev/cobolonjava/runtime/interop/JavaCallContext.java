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

    /** 同じ実行単位から、登録済み COBOL または Java プログラムへ再入する。 */
    public void call(String name, DataView... arguments) {
        Ops.call(context, ProgramId.of(name).value(), loader, arguments);
    }
}
