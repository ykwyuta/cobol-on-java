package dev.cobolonjava.db2;

import dev.cobolonjava.runtime.interop.CobolSession;

/** Spring JDBCまたはnative lease adapterが実装するSQL実行port。 */
public interface SqlExecutorPort {

    Db2ExecutionProfile profile();

    SqlOutcome execute(SqlPlan plan, SqlBindings bindings,
                       CobolSession session, UnitOfWork unitOfWork);
}
