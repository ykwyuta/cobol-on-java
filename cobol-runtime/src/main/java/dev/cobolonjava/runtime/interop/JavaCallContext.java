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

    /**
     * 呼んだ側の実行単位を区別する鍵。中身に意味は無く、同じ実行単位なら同じもの ({@code ==}) を返す。
     *
     * <p>MQ の接続 (設計 88) のように、呼び出しをまたいで実行単位ごとに持つ状態を、Java の側で引くのに使う。
     * 実行単位が終われば参照されなくなるので、持つ側は弱い参照の表に置けば後始末が要らない。
     */
    public Object runUnit() {
        return context.registers();
    }

    /**
     * 実行単位の作業単位に資源を加える (設計 88 §4.3)。CICS の SYNCPOINT で確定・取り消しされ、実行単位の終わり
     * (session を閉じるとき) に正常なら確定、異常なら取り消しで終える。
     */
    public void enlist(dev.cobolonjava.runtime.program.RecoverableResource resource) {
        dev.cobolonjava.runtime.program.RecoverableResources.of(context).enlist(resource);
    }

    /** 作業単位から資源を外す。 */
    public void delist(dev.cobolonjava.runtime.program.RecoverableResource resource) {
        dev.cobolonjava.runtime.program.RecoverableResources.of(context).delist(resource);
    }

    /** 同じ実行単位から、登録済み COBOL または Java プログラムへ再入する。 */
    public void call(String name, DataView... arguments) {
        Ops.call(context, ProgramId.of(name).value(), loader, arguments);
    }
}
