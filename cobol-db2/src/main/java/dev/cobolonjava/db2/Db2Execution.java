package dev.cobolonjava.db2;

import dev.cobolonjava.runtime.interop.CobolSession;
import java.util.Objects;

/**
 * 生成 COBOL の EXEC SQL が task の Db2 実行へ届くための session service (設計 77 §5.1)。
 *
 * <p>{@link SqlExecutorPort} は SQL を発行した session を要求する。session は service を登録して
 * から開くので、開いたあとに {@link #bind} で結び付ける。
 */
public final class Db2Execution {

    private final Db2TaskRuntime runtime;
    private CobolSession session;

    public Db2Execution(Db2TaskRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public synchronized void bind(CobolSession value) {
        Objects.requireNonNull(value, "session");
        if (session != null) {
            throw new IllegalStateException("Db2 execution is already bound to a session");
        }
        session = value;
    }

    public Db2TaskRuntime runtime() {
        return runtime;
    }

    public synchronized CobolSession session() {
        if (session == null) {
            throw new IllegalStateException("Db2 execution is not bound to a session");
        }
        return session;
    }
}
