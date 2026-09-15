package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsTaskBoundary;
import dev.cobolonjava.cics.CicsTaskBoundaryFactory;
import dev.cobolonjava.cics.CicsTaskContext;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2TaskRuntime;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.db2.jdbc.Db2NativeConnectionProvider;
import dev.cobolonjava.db2.jdbc.DriverManagedSqlExecutor;
import dev.cobolonjava.db2.jdbc.DriverManagedUnitOfWorkPort;
import dev.cobolonjava.db2.jdbc.DriverManagedUnitOfWorks;
import java.util.Objects;

/**
 * DB2_DRIVER_MANAGED_HOLD の STRICT の task 境界 (設計 77 §4.6・§5.4、暫定判断 P-143)。
 *
 * <p>task ごとに native lease を 1 本専有する UOW を持つ。この UOW は Spring の transaction に束ねられないので、
 * 会話の表と冪等キーの結果は {@link JdbcConversationStore#writesOn} で同じ lease の connection に出し、業務の SQL と
 * 一緒に commit する。claim と予約は従来どおり DataSource の別の transaction で確定する。
 *
 * <p>lease の connection と DataSource は同じ database を指していなければならない。JDBC の情報だけでは同一かを
 * 確かめきれないので、構成する利用者が保証する (別なら task の終わりに会話が見つからず NOT_COMMITTED になる)。
 */
public final class DriverManagedStrictTaskBoundaryFactory implements CicsTaskBoundaryFactory {

    private final Db2NativeConnectionProvider connections;
    private final JdbcConversationStore store;

    public DriverManagedStrictTaskBoundaryFactory(Db2NativeConnectionProvider connections,
                                                  JdbcConversationStore store) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public CicsTaskBoundary open(CicsTaskContext task, CicsTransactionDefinition definition) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(definition, "definition");
        // lease は最初の SQL か task の終わりの会話の更新まで取らない (Db2TaskRuntime の遅延 UOW)
        Db2TaskRuntime runtime = new Db2TaskRuntime(new UnitOfWorkOptions(Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD,
                definition.taskTimeout(), false, true), new DriverManagedUnitOfWorkPort(connections),
                new DriverManagedSqlExecutor());
        return new StrictTaskBoundary(runtime,
                unit -> store.writesOn(DriverManagedUnitOfWorks.connection(unit)), store);
    }
}
