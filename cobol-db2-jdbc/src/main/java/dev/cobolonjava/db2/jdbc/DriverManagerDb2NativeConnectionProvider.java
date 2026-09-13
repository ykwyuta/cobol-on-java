package dev.cobolonjava.db2.jdbc;

import dev.cobolonjava.db2.ResourceLeaseId;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/** DriverManagerからtask専用の物理connectionを直接生成する非pool provider。 */
public final class DriverManagerDb2NativeConnectionProvider
        implements Db2NativeConnectionProvider {

    private static final AtomicLong LEASE_SEQUENCE = new AtomicLong();

    private final String jdbcUrl;
    private final Properties properties;

    public DriverManagerDb2NativeConnectionProvider(String jdbcUrl, Properties properties) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("JDBC URL must not be blank");
        }
        this.jdbcUrl = jdbcUrl;
        this.properties = copy(Objects.requireNonNull(properties, "properties"));
    }

    public DriverManagerDb2NativeConnectionProvider(
            String jdbcUrl, String user, String password) {
        this(jdbcUrl, credentials(user, password));
    }

    @Override
    public Db2NativeConnectionLease acquire() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl, copy(properties));
        ResourceLeaseId id = new ResourceLeaseId(
                "db2-driver-lease-" + LEASE_SEQUENCE.incrementAndGet());
        return new DirectLease(id, connection);
    }

    private static Properties credentials(String user, String password) {
        Properties values = new Properties();
        values.setProperty("user", Objects.requireNonNull(user, "user"));
        values.setProperty("password", Objects.requireNonNull(password, "password"));
        return values;
    }

    private static Properties copy(Properties source) {
        Properties copy = new Properties();
        source.forEach((key, value) -> copy.put(key, value));
        return copy;
    }

    private static final class DirectLease implements Db2NativeConnectionLease {

        private final ResourceLeaseId id;
        private final Connection connection;
        private boolean released;

        private DirectLease(ResourceLeaseId id, Connection connection) {
            this.id = id;
            this.connection = connection;
        }

        @Override
        public ResourceLeaseId id() {
            return id;
        }

        @Override
        public Connection connection() {
            return connection;
        }

        @Override
        public synchronized void release(LeaseReleaseDisposition disposition)
                throws SQLException {
            Objects.requireNonNull(disposition, "disposition");
            if (released) {
                throw new IllegalStateException("Db2 native connection lease was released twice");
            }
            released = true;
            connection.close();
        }
    }
}
