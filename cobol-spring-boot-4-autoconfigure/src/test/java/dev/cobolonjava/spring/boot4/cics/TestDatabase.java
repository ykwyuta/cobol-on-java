package dev.cobolonjava.spring.boot4.cics;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * STRICT の置き場の試験が使う database (暫定判断 P-143・P-144)。表は {@link JdbcConversationStore#SCHEMA} の DDL で作る。
 *
 * <p>既定は H2 の memory database である。実 Db2 の試験 ({@code Db2StrictStoresIntegrationTest}) は、試験ごとに新しい schema を
 * 作り、終わったら表と schema を消す。Db2 の lock timeout の既定は待ち続けるので、試験の connection は 10 秒で打ち切る。
 */
final class TestDatabase implements AutoCloseable {

    private final String url;
    private final Properties credentials;
    private final DataSource dataSource;
    private final Runnable cleanup;

    private TestDatabase(String url, Properties credentials, DataSource dataSource, Runnable cleanup) {
        this.url = url;
        this.credentials = credentials;
        this.dataSource = dataSource;
        this.cleanup = cleanup;
    }

    static TestDatabase h2(String name) {
        String url = "jdbc:h2:mem:" + name + "-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        DataSource dataSource = new DriverManagerDataSource(url);
        createTables(dataSource);
        return new TestDatabase(url, new Properties(), dataSource, () -> { });
    }

    /** 環境変数 DB2_HOST / DB2_PORT / DB2_DATABASE / DB2_USER / DB2_PASSWORD の Db2 に、使い捨ての schema を作る。 */
    static TestDatabase db2() {
        String schema = "CJT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        String url = "jdbc:db2://" + value("DB2_HOST", "localhost") + ":" + value("DB2_PORT", "50000") + "/"
                + value("DB2_DATABASE", "COBOLDB") + ":currentSchema=" + schema + ";";
        Properties credentials = new Properties();
        credentials.setProperty("user", required("DB2_USER"));
        credentials.setProperty("password", required("DB2_PASSWORD"));
        DataSource dataSource = new LockTimeoutDataSource(new DriverManagerDataSource(url, credentials));
        createTables(dataSource);
        return new TestDatabase(url, credentials, dataSource, () -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            for (String table : jdbc.queryForList("SELECT TABNAME FROM SYSCAT.TABLES WHERE TABSCHEMA = ?",
                    String.class, schema)) {
                jdbc.execute("DROP TABLE " + schema + "." + table.strip());
            }
            jdbc.execute("DROP SCHEMA " + schema + " RESTRICT");
        });
    }

    private static void createTables(DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource(JdbcConversationStore.SCHEMA)).execute(dataSource);
    }

    /** DriverManager で task 専用の connection を作る provider のための URL。 */
    String url() {
        return url;
    }

    /** DriverManager の connection の資格情報。H2 では空。 */
    Properties credentials() {
        Properties copy = new Properties();
        copy.putAll(credentials);
        return copy;
    }

    DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        cleanup.run();
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String name) {
        return Objects.requireNonNull(System.getenv(name), name + " is required for the Db2 integration test");
    }

    /** Db2 の lock 待ちで試験が止まらないよう、connection ごとに lock timeout を置く。 */
    private static final class LockTimeoutDataSource extends DelegatingDataSource {

        private LockTimeoutDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return limited(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return limited(super.getConnection(username, password));
        }

        private static Connection limited(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET CURRENT LOCK TIMEOUT 10");
            }
            return connection;
        }
    }
}
