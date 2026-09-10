package dev.cobolonjava.db2.jdbc;

import java.sql.SQLException;

/** Spring transaction managerへ登録しない専用Db2 connection provider。 */
@FunctionalInterface
public interface Db2NativeConnectionProvider {

    Db2NativeConnectionLease acquire() throws SQLException;
}
