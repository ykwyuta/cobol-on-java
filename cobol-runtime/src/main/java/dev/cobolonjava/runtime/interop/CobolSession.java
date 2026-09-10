package dev.cobolonjava.runtime.interop;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.program.ProgramControlTransfer;
import dev.cobolonjava.runtime.program.ProgramReturn;
import dev.cobolonjava.runtime.program.ProgramStop;
import dev.cobolonjava.runtime.procedure.NonLocalProcedureTransferException;
import dev.cobolonjava.runtime.procedure.ProcedureDescriptor;
import dev.cobolonjava.runtime.procedure.ProcedureKind;
import dev.cobolonjava.runtime.procedure.ProcedureManifest;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.Objects;

/** 一つの COBOL 実行単位。可変状態をセッション間で共有しない。 */
public final class CobolSession implements AutoCloseable {

    private final ProgramContext context;
    private final ClassLoader classLoader;
    private final CatalogRevision catalogRevision;
    private Thread owner;
    private boolean terminated;
    private boolean failed;
    private boolean closed;

    CobolSession(ProgramContext context, ClassLoader classLoader,
                 CatalogRevision catalogRevision) {
        this.context = Objects.requireNonNull(context, "context");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.catalogRevision = Objects.requireNonNull(catalogRevision, "catalogRevision");
    }

    public CatalogRevision catalogRevision() {
        return catalogRevision;
    }

    public int returnCode() {
        checkUsable();
        return context.returnCode();
    }

    public void setReturnCode(int value) {
        checkUsable();
        context.setReturnCode(value);
    }

    /** COBOL から副プログラムとして呼ばれた場合と同じ入口で起動する。 */
    public CobolCallResult call(String name, DataView... arguments) {
        return invoke(ProgramId.of(name), true, arguments);
    }

    /** 主プログラムとして起動する。 */
    public CobolCallResult runMain(String name, DataView... arguments) {
        return invoke(ProgramId.of(name), false, arguments);
    }

    /**
     * programを起動せず、このsessionの固定catalog / class loaderで解決できるか確認する。
     */
    public boolean isProgramResolvable(String name) {
        checkUsable();
        return context.isProgramResolvable(name, classLoader);
    }

    /**
     * manifestで検証済みの通常SECTIONを合成的なPERFORMとして直接起動する。
     * 対象SECTION自身のhookは通らず、その内側の明示的PERFORMは通常どおりhookを通る。
     */
    public CobolCallResult invokeProcedure(
            ProcedureDescriptor descriptor, DataView... arguments) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(arguments, "arguments");
        if (descriptor.id().kind() != ProcedureKind.SECTION || descriptor.declarative()) {
            throw new IllegalArgumentException(
                    "only a normal SECTION can be directly invoked: " + descriptor.id());
        }
        if (!descriptor.directInvocationEligible()) {
            throw new NonLocalProcedureTransferException(
                    descriptor.id(), descriptor.ineligibilityReason());
        }
        checkUsable();
        ProgramId id = descriptor.id().programId();
        ProgramContext.Loaded loaded = context.resolve(id.value(), classLoader);
        loaded.validateArguments(arguments);
        CobolProgram program = loaded.program();
        ProcedureManifest embeddedManifest = program.procedureManifest();
        if (embeddedManifest == null) {
            throw new IllegalStateException(
                    "direct SECTION invocation requires metadata embedded in " + id.value());
        }
        boolean exactDescriptor = embeddedManifest.programId().equals(id)
                && embeddedManifest.procedures().stream().anyMatch(descriptor::equals);
        if (!exactDescriptor) {
            throw new IllegalStateException(
                    "requested SECTION descriptor disagrees with generated class metadata: "
                            + descriptor.id());
        }
        Storage storage = loaded.storage();
        boolean normalBoundary = false;
        Termination termination = Termination.RETURNED;
        context.enterMain(id.value(), storage, program.storageMap(), program);
        try {
            program.performProcedureRange(descriptor.firstParagraph(),
                    descriptor.lastParagraph(), storage, context, arguments);
            normalBoundary = true;
        } catch (ProgramReturn returned) {
            normalBoundary = true;
        } catch (ProgramStop stopped) {
            normalBoundary = true;
            termination = Termination.STOP_RUN;
            terminated = true;
        } catch (ProgramControlTransfer transfer) {
            normalBoundary = true;
            throw transfer;
        } catch (RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            if (normalBoundary) {
                context.leave();
            }
        }
        return new CobolCallResult(id, termination, context.returnCode(), storage);
    }

    private CobolCallResult invoke(ProgramId id, boolean called, DataView[] arguments) {
        Objects.requireNonNull(arguments, "arguments");
        checkUsable();
        ProgramContext.Loaded loaded = context.resolve(id.value(), classLoader);
        loaded.validateArguments(arguments);
        CobolProgram program = loaded.program();
        Storage storage = loaded.storage();
        boolean normalBoundary = false;
        Termination termination = Termination.RETURNED;
        if (called) {
            context.enterCall(id.value(), storage, program.storageMap(), program);
        } else {
            context.enterMain(id.value(), storage, program.storageMap(), program);
        }
        try {
            program.run(storage, context, arguments);
            normalBoundary = true;
        } catch (ProgramReturn returned) {
            normalBoundary = true;
        } catch (ProgramStop stopped) {
            normalBoundary = true;
            termination = Termination.STOP_RUN;
            terminated = true;
        } catch (ProgramControlTransfer transfer) {
            normalBoundary = true;
            throw transfer;
        } catch (RuntimeException | Error e) {
            failed = true;
            throw e;
        } finally {
            if (normalBoundary) {
                context.leave();
            }
        }
        return new CobolCallResult(id, termination, context.returnCode(), storage);
    }

    /** 読み込み済みインスタンスと WORKING-STORAGE を忘れる。 */
    public void cancel(String name) {
        checkUsable();
        context.forget(ProgramId.of(name).value());
    }

    /** 現在の WORKING-STORAGE。未解決なら、この呼び出しでインスタンスを生成する。 */
    public Storage workingStorage(String name) {
        checkUsable();
        return context.resolve(ProgramId.of(name).value(), classLoader).storage();
    }

    public boolean terminated() {
        return terminated;
    }

    public boolean failed() {
        return failed;
    }

    /** subsystem adapterが外部資源へ触れる前にsession状態とthread所有を検査する。 */
    public void verifyUsable() {
        checkUsable();
    }

    private synchronized void checkUsable() {
        if (closed) {
            throw new CobolSessionStateException("COBOL session is closed");
        }
        if (terminated) {
            throw new CobolSessionStateException("COBOL session ended by STOP RUN");
        }
        if (failed) {
            throw new CobolSessionStateException("COBOL session ended by an execution failure");
        }
        Thread current = Thread.currentThread();
        if (owner == null) {
            owner = current;
        } else if (owner != current) {
            throw new CobolSessionStateException(
                    "COBOL session belongs to thread " + owner.getName());
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        if (owner != null && owner != Thread.currentThread()) {
            throw new CobolSessionStateException(
                    "COBOL session must be closed by its owner thread " + owner.getName());
        }
        context.closeFiles();
        closed = true;
    }
}
