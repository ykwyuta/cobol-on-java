package dev.cobolonjava.spring.boot4.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.SqlExecutorPort;
import dev.cobolonjava.db2.SqlBindings;
import dev.cobolonjava.db2.SqlOutcome;
import dev.cobolonjava.db2.SqlPlan;
import dev.cobolonjava.db2.UnitOfWork;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.db2.UnitOfWorkPort;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.spring.boot4.db2.SpringManagedUnitOfWorkPort;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("V1")
class CobolDb2SpringAutoConfigurationTest {

    @Test
    @DisplayName("BootのAutoConfiguration importsへadapterを登録する")
    void registersAutoConfigurationImport() {
        assertTrue(ImportCandidates.load(AutoConfiguration.class,
                        CobolDb2SpringAutoConfigurationTest.class.getClassLoader())
                .getCandidates()
                .contains(CobolDb2SpringAutoConfiguration.class.getName()));
    }

    @Test
    @DisplayName("単一DataSourceからprototype UOWとsingleton SQL executorを構成する")
    void configuresPrototypeSpringManagedPort() {
        DataSource dataSource = dataSource();
        runner(dataSource).run(context -> {
            assertEquals(1, context.getBeanNamesForType(UnitOfWorkPort.class).length);
            UnitOfWorkPort first = context.getBean(UnitOfWorkPort.class);
            UnitOfWorkPort second = context.getBean(UnitOfWorkPort.class);
            assertNotSame(first, second);
            assertEquals(Db2ExecutionProfile.SPRING_MANAGED, first.profile());
            assertSame(context.getBean(SqlExecutorPort.class),
                    context.getBean(SqlExecutorPort.class));
            first.close();
            second.close();
        });
    }

    @Test
    @DisplayName("driver managed profile指定時はSpring UOWを自動構成しない")
    void backsOffForDriverManagedProfile() {
        DataSource dataSource = dataSource();
        runner(dataSource)
                .withPropertyValues("cobol.db2.profile=DB2_DRIVER_MANAGED_HOLD")
                .run(context -> {
                    assertEquals(0,
                            context.getBeanNamesForType(UnitOfWorkPort.class).length);
                    assertEquals(0,
                            context.getBeanNamesForType(SqlExecutorPort.class).length);
                });
    }

    @Test
    @DisplayName("利用者定義UnitOfWorkPortを自動構成で置換しない")
    void backsOffForUserPort() {
        DataSource dataSource = dataSource();
        UnitOfWorkPort custom = new UnitOfWorkPort() {
            @Override
            public Db2ExecutionProfile profile() {
                return Db2ExecutionProfile.SPRING_MANAGED;
            }

            @Override
            public UnitOfWork begin(UnitOfWorkOptions options) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
            }
        };
        runner(dataSource).withBean(UnitOfWorkPort.class, () -> custom)
                .run(context -> assertSame(custom, context.getBean(UnitOfWorkPort.class)));
    }

    @Test
    @DisplayName("利用者定義SqlExecutorPortを自動構成で置換しない")
    void backsOffForUserSqlExecutor() {
        DataSource dataSource = dataSource();
        SqlExecutorPort custom = new SqlExecutorPort() {
            @Override
            public Db2ExecutionProfile profile() {
                return Db2ExecutionProfile.SPRING_MANAGED;
            }

            @Override
            public SqlOutcome execute(SqlPlan plan, SqlBindings bindings,
                    CobolSession session, UnitOfWork unitOfWork) {
                throw new UnsupportedOperationException();
            }
        };
        runner(dataSource).withBean(SqlExecutorPort.class, () -> custom)
                .run(context -> {
                    assertSame(custom, context.getBean(SqlExecutorPort.class));
                    context.getBean(UnitOfWorkPort.class).close();
                });
    }

    @Test
    @DisplayName("Boot 4.1のDataSourceとtransaction manager自動構成後にportを構成する")
    void configuresAfterBootJdbcAutoConfiguration() {
        String databaseName = UUID.randomUUID().toString();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        CobolDb2SpringAutoConfiguration.class,
                        DataSourceAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + databaseName,
                        "spring.datasource.username=sa",
                        "spring.datasource.password=")
                .run(context -> {
                    assertEquals(1,
                            context.getBeanNamesForType(UnitOfWorkPort.class).length);
                    assertEquals(1,
                            context.getBeanNamesForType(SqlExecutorPort.class).length);
                    context.getBean(UnitOfWorkPort.class).close();
                });
    }

    private static ApplicationContextRunner runner(DataSource dataSource) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        CobolDb2SpringAutoConfiguration.class))
                .withBean(DataSource.class, () -> dataSource)
                .withBean(PlatformTransactionManager.class,
                        () -> new JdbcTransactionManager(dataSource));
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }
}
