package dev.cobolonjava.db2.jdbc;

import dev.cobolonjava.db2.ResourceLeaseId;
import java.sql.Connection;
import java.sql.SQLException;

/** providerがtaskへ専有させるopaque ID付きJDBC connection lease。 */
public interface Db2NativeConnectionLease {

    ResourceLeaseId id();

    Connection connection();

    void release(LeaseReleaseDisposition disposition) throws SQLException;
}
