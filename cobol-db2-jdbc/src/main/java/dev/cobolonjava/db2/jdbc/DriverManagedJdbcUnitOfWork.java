package dev.cobolonjava.db2.jdbc;

import dev.cobolonjava.db2.UnitOfWork;
import java.sql.Connection;

/** native SQL executorが同一leaseとcursor lifecycleを利用するための内部契約。 */
interface DriverManagedJdbcUnitOfWork extends UnitOfWork {

    Connection connection();

    int queryTimeoutSeconds();

    void markConnectionUnusable();

    void registerResource(AutoCloseable resource, boolean holdAcrossCommit);

    void unregisterResource(AutoCloseable resource);
}
