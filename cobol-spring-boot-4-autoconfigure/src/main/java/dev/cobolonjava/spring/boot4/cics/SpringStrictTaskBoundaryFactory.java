package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsTaskBoundary;
import dev.cobolonjava.cics.CicsTaskBoundaryFactory;
import dev.cobolonjava.cics.CicsTaskContext;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2ProfileMismatchException;
import dev.cobolonjava.db2.Db2TaskRuntime;
import dev.cobolonjava.db2.SqlExecutorPort;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.db2.UnitOfWorkPort;
import dev.cobolonjava.spring.boot4.db2.SpringManagedSqlExecutor;
import dev.cobolonjava.spring.boot4.db2.SpringManagedUnitOfWorkPort;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * SPRING_MANAGED の STRICT の task 境界 (設計 77 §4.6・§5.4、暫定判断 P-143)。
 *
 * <p>task ごとに SPRING_MANAGED の Db2 の UOW を持つ。会話の表は {@link JdbcConversationStore#writes()} で更新するので、
 * Spring の transaction に束ねられた同じ connection に入る。そのため会話の表と業務の SQL は同じ DataSource の
 * instance でなければならず、違えば起動時と task の開始時に断る。
 */
public final class SpringStrictTaskBoundaryFactory implements CicsTaskBoundaryFactory {

    private final Supplier<UnitOfWorkPort> unitsOfWork;
    private final SqlExecutorPort sqlExecutor;
    private final JdbcConversationStore store;

    /**
     * @param unitsOfWork task ごとに新しい UOW port を返す (SPRING_MANAGED の port は prototype の bean)
     */
    public SpringStrictTaskBoundaryFactory(Supplier<UnitOfWorkPort> unitsOfWork, SqlExecutorPort sqlExecutor,
                                           JdbcConversationStore store) {
        this.unitsOfWork = Objects.requireNonNull(unitsOfWork, "unitsOfWork");
        this.sqlExecutor = Objects.requireNonNull(sqlExecutor, "sqlExecutor");
        this.store = Objects.requireNonNull(store, "store");
        if (sqlExecutor.profile() != Db2ExecutionProfile.SPRING_MANAGED) {
            throw new Db2ProfileMismatchException("STRICT conversation store requires the SPRING_MANAGED Db2 profile");
        }
        if (sqlExecutor instanceof SpringManagedSqlExecutor spring && spring.dataSource() != store.dataSource()) {
            throw new Db2ProfileMismatchException(
                    "STRICT conversation store and COBOL Db2 SQL must use the identical DataSource instance");
        }
    }

    @Override
    public CicsTaskBoundary open(CicsTaskContext task, CicsTransactionDefinition definition) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(definition, "definition");
        UnitOfWorkPort port = Objects.requireNonNull(unitsOfWork.get(), "UOW port");
        if (port instanceof SpringManagedUnitOfWorkPort spring && spring.dataSource() != store.dataSource()) {
            port.close();
            throw new Db2ProfileMismatchException(
                    "STRICT conversation store and the Db2 UOW must use the identical DataSource instance");
        }
        Db2TaskRuntime runtime = new Db2TaskRuntime(new UnitOfWorkOptions(Db2ExecutionProfile.SPRING_MANAGED,
                definition.taskTimeout(), false, false), port, sqlExecutor);
        return new StrictTaskBoundary(runtime, unit -> store.writes(), store);
    }
}
