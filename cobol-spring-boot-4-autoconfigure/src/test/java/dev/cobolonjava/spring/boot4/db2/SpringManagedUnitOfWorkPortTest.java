package dev.cobolonjava.spring.boot4.db2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2ProfileMismatchException;
import dev.cobolonjava.db2.RollbackReason;
import dev.cobolonjava.db2.UnitOfWork;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.db2.UnitOfWorkState;
import dev.cobolonjava.db2.UnitOfWorkStateException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.DefaultTransactionDefinition;

@Tag("V1")
class SpringManagedUnitOfWorkPortTest {

    @Test
    @DisplayName("JdbcTemplate更新をSpring UOWのcommitとrollbackへ参加させる")
    void commitsAndRollsBackJdbcWork() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = prepare(dataSource);
        SpringManagedUnitOfWorkPort port = new SpringManagedUnitOfWorkPort(
                dataSource, new JdbcTransactionManager(dataSource));

        UnitOfWork committed = port.begin(options(false));
        jdbc.update("insert into event_log(id, payload) values (1, 'commit')");
        committed.commit();

        assertEquals(UnitOfWorkState.COMMITTED, committed.state());
        assertEquals(1, count(jdbc));

        UnitOfWork rolledBack = port.begin(options(false));
        jdbc.update("insert into event_log(id, payload) values (2, 'rollback')");
        rolledBack.rollback(new RollbackReason(RollbackReason.Kind.EXPLICIT, "TEST"));

        assertEquals(UnitOfWorkState.ROLLED_BACK, rolledBack.state());
        assertEquals(1, count(jdbc));
        port.close();
        assertThrows(UnitOfWorkStateException.class, () -> port.begin(options(false)));
    }

    @Test
    @DisplayName("task UOWは外側transactionをsuspendするREQUIRES_NEWになる")
    void suspendsOuterTransactionWithRequiresNew() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = prepare(dataSource);
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);
        SpringManagedUnitOfWorkPort port = new SpringManagedUnitOfWorkPort(dataSource, manager);
        TransactionStatus outer = manager.getTransaction(new DefaultTransactionDefinition());
        jdbc.update("insert into event_log(id, payload) values (1, 'outer')");

        UnitOfWork inner = port.begin(options(false));
        jdbc.update("insert into event_log(id, payload) values (2, 'inner')");
        inner.commit();
        manager.rollback(outer);

        assertEquals(1, count(jdbc));
        assertEquals("inner", jdbc.queryForObject(
                "select payload from event_log where id = 2", String.class));
        port.close();
    }

    @Test
    @DisplayName("明示完了されないactive UOWをport closeでrollbackする")
    void rollsBackActiveWorkOnClose() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = prepare(dataSource);
        SpringManagedUnitOfWorkPort port = new SpringManagedUnitOfWorkPort(
                dataSource, new DataSourceTransactionManager(dataSource));
        UnitOfWork unit = port.begin(options(false));
        jdbc.update("insert into event_log(id, payload) values (1, 'cleanup')");

        port.close();

        assertEquals(UnitOfWorkState.ROLLED_BACK, unit.state());
        assertEquals(0, count(jdbc));
    }

    @Test
    @DisplayName("active UOWの重複開始とWITH HOLD profile混在を開始前に拒否する")
    void rejectsOverlappingAndDriverManagedWork() {
        DataSource dataSource = dataSource();
        SpringManagedUnitOfWorkPort port = new SpringManagedUnitOfWorkPort(
                dataSource, new JdbcTransactionManager(dataSource));
        UnitOfWork active = port.begin(options(false));

        assertThrows(UnitOfWorkStateException.class, () -> port.begin(options(false)));
        active.rollback(RollbackReason.cleanup());
        assertThrows(Db2ProfileMismatchException.class, () -> port.begin(
                new UnitOfWorkOptions(Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD,
                        Duration.ofSeconds(5), false, true)));
        port.close();
    }

    @Test
    @DisplayName("JDBC manager以外と異なるDataSourceのmanagerを構築時に拒否する")
    void rejectsUnsupportedOrMismatchedTransactionManager() {
        DataSource dataSource = dataSource();
        PlatformTransactionManager unsupported = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void commit(TransactionStatus status) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void rollback(TransactionStatus status) {
                throw new UnsupportedOperationException();
            }
        };

        assertThrows(Db2ProfileMismatchException.class,
                () -> new SpringManagedUnitOfWorkPort(dataSource, unsupported));
        assertThrows(Db2ProfileMismatchException.class,
                () -> new SpringManagedUnitOfWorkPort(
                        dataSource, new JdbcTransactionManager(dataSource())));
    }

    @Test
    @DisplayName("UOW名、REQUIRES_NEW、read-only、秒へ切上げたtimeoutをSpringへ渡す")
    void mapsUnitOfWorkOptionsToSpringDefinition() {
        DataSource dataSource = dataSource();
        CapturingTransactionManager manager = new CapturingTransactionManager(dataSource);
        SpringManagedUnitOfWorkPort port = new SpringManagedUnitOfWorkPort(dataSource, manager);

        UnitOfWork unit = port.begin(options(true));

        TransactionDefinition definition = manager.definition.get();
        assertEquals("cobol-db2-uow-1", definition.getName());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                definition.getPropagationBehavior());
        assertEquals(true, definition.isReadOnly());
        assertEquals(2, definition.getTimeout());
        unit.rollback(RollbackReason.cleanup());
        port.close();
    }

    @Test
    @DisplayName("参加処理がrollback-onlyにしたUOWをcommit済みと誤認しない")
    void reportsRollbackOnlyCompletion() {
        DataSource dataSource = dataSource();
        JdbcTransactionManager manager = new JdbcTransactionManager(dataSource);
        SpringManagedUnitOfWorkPort port = new SpringManagedUnitOfWorkPort(dataSource, manager);
        UnitOfWork unit = port.begin(options(false));
        DefaultTransactionDefinition participating = new DefaultTransactionDefinition();
        participating.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        TransactionStatus joined = manager.getTransaction(participating);
        joined.setRollbackOnly();
        manager.commit(joined);

        assertThrows(UnexpectedRollbackException.class, unit::commit);
        assertEquals(UnitOfWorkState.ROLLED_BACK, unit.state());
        port.close();
    }

    @Test
    @DisplayName("task所有thread以外からのUOW操作を拒否する")
    void rejectsCrossThreadUse() throws InterruptedException {
        DataSource dataSource = dataSource();
        SpringManagedUnitOfWorkPort port = new SpringManagedUnitOfWorkPort(
                dataSource, new JdbcTransactionManager(dataSource));
        UnitOfWork unit = port.begin(options(false));
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                unit.rollback(RollbackReason.cleanup());
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
        thread.join();

        assertInstanceOf(UnitOfWorkStateException.class, failure.get());
        unit.rollback(RollbackReason.cleanup());
        port.close();
    }

    @Test
    @DisplayName("commit前に登録資源を閉じ、close失敗時は更新をrollbackする")
    void closesRegisteredResourcesAndRollsBackOnCleanupFailure() {
        DataSource dataSource = dataSource();
        JdbcTemplate jdbc = prepare(dataSource);
        SpringManagedUnitOfWorkPort port = new SpringManagedUnitOfWorkPort(
                dataSource, new JdbcTransactionManager(dataSource));
        SpringJdbcUnitOfWork unit = (SpringJdbcUnitOfWork) port.begin(options(false));
        AtomicInteger closed = new AtomicInteger();
        unit.registerResource(closed::incrementAndGet);
        unit.registerResource(() -> {
            closed.incrementAndGet();
            throw new IllegalStateException("cursor close failed");
        });
        jdbc.update("insert into event_log(id, payload) values (1, 'rollback')");

        IllegalStateException failure = assertThrows(IllegalStateException.class, unit::commit);

        assertEquals("failed to close Spring UOW resource", failure.getMessage());
        assertEquals(2, closed.get());
        assertEquals(UnitOfWorkState.ROLLED_BACK, unit.state());
        assertEquals(0, count(jdbc));
        port.close();
    }

    private static UnitOfWorkOptions options(boolean readOnly) {
        return new UnitOfWorkOptions(Db2ExecutionProfile.SPRING_MANAGED,
                Duration.ofMillis(1500), readOnly, false);
    }

    private static JdbcTemplate prepare(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table event_log(id int primary key, payload varchar(32))");
        return jdbc;
    }

    private static int count(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select count(*) from event_log", Integer.class);
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static final class CapturingTransactionManager
            extends DataSourceTransactionManager {

        private final AtomicReference<TransactionDefinition> definition =
                new AtomicReference<>();

        private CapturingTransactionManager(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            this.definition.set(definition);
            super.doBegin(transaction, definition);
        }
    }
}
