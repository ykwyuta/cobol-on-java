package dev.cobolonjava.db2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** profile固定、遅延UOW、syncpoint、WITH HOLD拒否の中立契約。 */
@Tag("V1")
class Db2TaskRuntimeTest {

    @Test
    @DisplayName("SQLまでUOWを開始せず同じUOWを共有しcommit後は遅延再開する")
    void lazilyStartsAndRotatesUnitOfWorkAtCommit() {
        FakeUnitOfWorkPort uows = new FakeUnitOfWorkPort(
                Db2ExecutionProfile.SPRING_MANAGED, true);
        FakeSqlExecutor sql = new FakeSqlExecutor(Db2ExecutionProfile.SPRING_MANAGED);

        try (CobolSession session = session();
             Db2TaskRuntime task = new Db2TaskRuntime(options(
                     Db2ExecutionProfile.SPRING_MANAGED, false), uows, sql)) {
            assertEquals(0, uows.units.size());
            task.execute(simpleSelect("S1"), SqlBindings.NONE, session);
            task.execute(simpleSelect("S2"), SqlBindings.NONE, session);
            assertEquals(1, uows.units.size());
            assertSame(sql.units.get(0), sql.units.get(1));

            task.commit();
            assertEquals(UnitOfWorkState.COMMITTED, uows.units.get(0).state());
            task.execute(simpleSelect("S3"), SqlBindings.NONE, session);
            assertEquals(2, uows.units.size());
            task.complete();
            assertEquals(UnitOfWorkState.COMMITTED, uows.units.get(1).state());
            assertEquals(1, uows.closeCount);
            assertThrows(UnitOfWorkStateException.class,
                    () -> task.execute(simpleSelect("S4"), SqlBindings.NONE, session));
        }
    }

    @Test
    @DisplayName("task、UOW port、SQL executorのprofile混在を構築時に拒否する")
    void rejectsMixedProfilesBeforeAnyResourceAccess() {
        UnitOfWorkOptions nativeOptions = options(
                Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD, true);
        FakeUnitOfWorkPort springUow = new FakeUnitOfWorkPort(
                Db2ExecutionProfile.SPRING_MANAGED, true);
        FakeSqlExecutor nativeSql = new FakeSqlExecutor(
                Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD);

        assertThrows(Db2ProfileMismatchException.class,
                () -> new Db2TaskRuntime(nativeOptions, springUow, nativeSql));
        assertThrows(Db2ProfileMismatchException.class,
                () -> new Db2TaskRuntime(nativeOptions,
                        new FakeUnitOfWorkPort(
                                Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD, true),
                        new FakeSqlExecutor(Db2ExecutionProfile.SPRING_MANAGED)));
        assertEquals(0, springUow.units.size());
    }

    @Test
    @DisplayName("静的inventoryと動的OPENの両方で未承認WITH HOLDを拒否する")
    void rejectsUnapprovedHoldCursorBeforeBeginningUow() {
        assertThrows(Db2ProfileMismatchException.class,
                () -> options(Db2ExecutionProfile.SPRING_MANAGED, true));
        FakeUnitOfWorkPort uows = new FakeUnitOfWorkPort(
                Db2ExecutionProfile.SPRING_MANAGED, true);
        FakeSqlExecutor sql = new FakeSqlExecutor(Db2ExecutionProfile.SPRING_MANAGED);
        CursorOptions unverified = new CursorOptions("C1", true,
                CursorHoldStrategy.REJECT_UNVERIFIED, false, false, false, false);

        try (CobolSession session = session();
             Db2TaskRuntime task = new Db2TaskRuntime(options(
                     Db2ExecutionProfile.SPRING_MANAGED, false), uows, sql)) {
            assertThrows(UnverifiedHoldCursorException.class,
                    () -> task.execute(open("OPEN-1", unverified), SqlBindings.NONE, session));
            assertEquals(0, uows.units.size());
            assertEquals(0, sql.units.size());
        }
    }

    @Test
    @DisplayName("native WITH HOLDはcommit後も同じtask leaseを要求する")
    void pinsNativeLeaseAcrossCommits() {
        FakeUnitOfWorkPort stable = new FakeUnitOfWorkPort(
                Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD, true);
        FakeSqlExecutor sql = new FakeSqlExecutor(
                Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD);
        CursorOptions nativeHold = new CursorOptions("HC", true,
                CursorHoldStrategy.DB2_DRIVER_MANAGED_HOLD,
                false, false, false, false);

        try (CobolSession session = session();
             Db2TaskRuntime task = new Db2TaskRuntime(options(
                     Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD, true), stable, sql)) {
            task.execute(open("OPEN-HC", nativeHold), SqlBindings.NONE, session);
            task.commit();
            task.execute(simpleSelect("AFTER-COMMIT"), SqlBindings.NONE, session);
            assertEquals(stable.units.get(0).resourceLeaseId(),
                    stable.units.get(1).resourceLeaseId());
        }

        FakeUnitOfWorkPort changing = new FakeUnitOfWorkPort(
                Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD, false);
        try (CobolSession session = session();
             Db2TaskRuntime task = new Db2TaskRuntime(options(
                     Db2ExecutionProfile.DB2_DRIVER_MANAGED_HOLD, true), changing, sql)) {
            task.execute(open("OPEN-HC", nativeHold), SqlBindings.NONE, session);
            task.commit();
            assertThrows(Db2ProfileMismatchException.class,
                    () -> task.execute(simpleSelect("AFTER-COMMIT"),
                            SqlBindings.NONE, session));
        }
        assertEquals(UnitOfWorkState.ROLLED_BACK, changing.units.get(1).state());
    }

    @Test
    @DisplayName("明示完了なしのcloseはactive UOWをrollbackする")
    void rollsBackActiveUnitOfWorkOnScopeClose() {
        FakeUnitOfWorkPort uows = new FakeUnitOfWorkPort(
                Db2ExecutionProfile.SPRING_MANAGED, true);
        FakeSqlExecutor sql = new FakeSqlExecutor(Db2ExecutionProfile.SPRING_MANAGED);

        try (CobolSession session = session();
             Db2TaskRuntime task = new Db2TaskRuntime(options(
                     Db2ExecutionProfile.SPRING_MANAGED, false), uows, sql)) {
            task.execute(simpleSelect("S1"), SqlBindings.NONE, session);
        }

        assertEquals(UnitOfWorkState.ROLLED_BACK, uows.units.get(0).state());
        assertEquals(1, uows.closeCount);
        assertEquals(RollbackReason.Kind.RESOURCE_CLEANUP,
                uows.units.get(0).rollbackReason.kind());
    }

    @Test
    @DisplayName("DBがUOWをrollbackした結果はadapter状態と一致しなければならない")
    void synchronizesDatabaseDrivenRollbackState() {
        FakeUnitOfWorkPort uows = new FakeUnitOfWorkPort(
                Db2ExecutionProfile.SPRING_MANAGED, true);
        FakeSqlExecutor sql = new FakeSqlExecutor(Db2ExecutionProfile.SPRING_MANAGED);
        sql.databaseRollback = true;

        try (CobolSession session = session();
             Db2TaskRuntime task = new Db2TaskRuntime(options(
                     Db2ExecutionProfile.SPRING_MANAGED, false), uows, sql)) {
            task.execute(simpleSelect("DEADLOCK"), SqlBindings.NONE, session);
            assertEquals(UnitOfWorkState.ROLLED_BACK, uows.units.get(0).state());
            task.execute(simpleSelect("RETRY"), SqlBindings.NONE, session);
            assertEquals(2, uows.units.size());
        }
    }

    @Test
    @DisplayName("adapter例外前のdatabase rollbackも同期して次のUOWを開始できる")
    void synchronizesDatabaseRollbackOnExceptionPath() {
        FakeUnitOfWorkPort uows = new FakeUnitOfWorkPort(
                Db2ExecutionProfile.SPRING_MANAGED, true);
        FakeSqlExecutor sql = new FakeSqlExecutor(Db2ExecutionProfile.SPRING_MANAGED);
        sql.throwAfterDatabaseRollback = true;

        try (CobolSession session = session();
             Db2TaskRuntime task = new Db2TaskRuntime(options(
                     Db2ExecutionProfile.SPRING_MANAGED, false), uows, sql)) {
            assertThrows(IllegalStateException.class,
                    () -> task.execute(simpleSelect("DEADLOCK"), SqlBindings.NONE, session));
            assertEquals(UnitOfWorkState.ROLLED_BACK, uows.units.get(0).state());
            sql.throwAfterDatabaseRollback = false;
            task.execute(simpleSelect("RETRY"), SqlBindings.NONE, session);
            assertEquals(2, uows.units.size());
        }
    }

    @Test
    @DisplayName("portable spoolは意味を保てないcursor能力を受理しない")
    void portableSpoolRejectsNonPortableCursorCapabilities() {
        assertThrows(IllegalArgumentException.class, () -> new CursorOptions(
                "C1", true, CursorHoldStrategy.PORTABLE_SPOOL,
                true, false, false, false));
        assertThrows(IllegalArgumentException.class, () -> new CursorOptions(
                "C1", true, CursorHoldStrategy.PORTABLE_SPOOL,
                false, false, true, false));
    }

    @Test
    @DisplayName("commit失敗を主障害に保ちtask resource解放失敗をsuppressedへ残す")
    void preservesPrimaryFailureWhileClosingTaskResources() {
        FakeUnitOfWorkPort uows = new FakeUnitOfWorkPort(
                Db2ExecutionProfile.SPRING_MANAGED, true);
        uows.failCommit = true;
        uows.failClose = true;
        FakeSqlExecutor sql = new FakeSqlExecutor(Db2ExecutionProfile.SPRING_MANAGED);

        try (CobolSession session = session()) {
            Db2TaskRuntime task = new Db2TaskRuntime(options(
                    Db2ExecutionProfile.SPRING_MANAGED, false), uows, sql);
            task.execute(simpleSelect("S1"), SqlBindings.NONE, session);

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, task::complete);

            assertEquals("commit failed", failure.getMessage());
            assertEquals(1, failure.getSuppressed().length);
            assertEquals("port close failed", failure.getSuppressed()[0].getMessage());
            assertEquals(UnitOfWorkState.ROLLED_BACK, uows.units.get(0).state());
            assertEquals(1, uows.closeCount);
        }
    }

    private static UnitOfWorkOptions options(
            Db2ExecutionProfile profile, boolean requiresHold) {
        return new UnitOfWorkOptions(profile, Duration.ofSeconds(30), false, requiresHold);
    }

    private static SqlPlan simpleSelect(String id) {
        return new SqlPlan(id, "DB2", SqlOperation.SELECT_ONE,
                "SELECT 1 FROM SYSIBM.SYSDUMMY1", CursorOptions.none());
    }

    private static SqlPlan open(String id, CursorOptions cursor) {
        return new SqlPlan(id, "DB2", SqlOperation.OPEN_CURSOR,
                "SELECT VALUE FROM T", cursor);
    }

    private static CobolSession session() {
        return CobolRuntime.builder(ProgramCatalog.builder().build()).build().openSession();
    }

    private static final class FakeUnitOfWorkPort implements UnitOfWorkPort {

        private final Db2ExecutionProfile profile;
        private final boolean stableLease;
        private final List<FakeUnitOfWork> units = new ArrayList<>();
        private int closeCount;
        private boolean failCommit;
        private boolean failClose;

        private FakeUnitOfWorkPort(Db2ExecutionProfile profile, boolean stableLease) {
            this.profile = profile;
            this.stableLease = stableLease;
        }

        @Override
        public Db2ExecutionProfile profile() {
            return profile;
        }

        @Override
        public UnitOfWork begin(UnitOfWorkOptions options) {
            assertEquals(profile, options.profile());
            String lease = stableLease ? "task-lease" : "lease-" + units.size();
            FakeUnitOfWork unit = new FakeUnitOfWork(
                    profile, new ResourceLeaseId(lease), failCommit);
            units.add(unit);
            return unit;
        }

        @Override
        public void close() {
            closeCount++;
            units.stream()
                    .filter(unit -> unit.state() == UnitOfWorkState.ACTIVE)
                    .forEach(unit -> unit.rollback(RollbackReason.cleanup()));
            if (failClose) {
                throw new IllegalStateException("port close failed");
            }
        }
    }

    private static final class FakeUnitOfWork implements UnitOfWork {

        private final Db2ExecutionProfile profile;
        private final ResourceLeaseId resourceLeaseId;
        private final boolean failCommit;
        private UnitOfWorkState state = UnitOfWorkState.ACTIVE;
        private RollbackReason rollbackReason;

        private FakeUnitOfWork(Db2ExecutionProfile profile, ResourceLeaseId resourceLeaseId,
                               boolean failCommit) {
            this.profile = profile;
            this.resourceLeaseId = resourceLeaseId;
            this.failCommit = failCommit;
        }

        @Override
        public Db2ExecutionProfile profile() {
            return profile;
        }

        @Override
        public ResourceLeaseId resourceLeaseId() {
            return resourceLeaseId;
        }

        @Override
        public UnitOfWorkState state() {
            return state;
        }

        @Override
        public void commit() {
            if (failCommit) {
                throw new IllegalStateException("commit failed");
            }
            state = UnitOfWorkState.COMMITTED;
        }

        @Override
        public void rollback(RollbackReason reason) {
            rollbackReason = reason;
            state = UnitOfWorkState.ROLLED_BACK;
        }
    }

    private static final class FakeSqlExecutor implements SqlExecutorPort {

        private final Db2ExecutionProfile profile;
        private final List<UnitOfWork> units = new ArrayList<>();
        private boolean databaseRollback;
        private boolean throwAfterDatabaseRollback;

        private FakeSqlExecutor(Db2ExecutionProfile profile) {
            this.profile = profile;
        }

        @Override
        public Db2ExecutionProfile profile() {
            return profile;
        }

        @Override
        public SqlOutcome execute(SqlPlan plan, SqlBindings bindings,
                                  CobolSession session, UnitOfWork unitOfWork) {
            units.add(unitOfWork);
            if (throwAfterDatabaseRollback) {
                unitOfWork.rollback(new RollbackReason(
                        RollbackReason.Kind.SQL_FAILURE, "DB-ROLLBACK"));
                throw new IllegalStateException("deadlock");
            }
            if (databaseRollback) {
                unitOfWork.rollback(new RollbackReason(
                        RollbackReason.Kind.SQL_FAILURE, "DB-ROLLBACK"));
                return new SqlOutcome(-911, "40001", -1, List.of(), true);
            }
            return SqlOutcome.success(1);
        }
    }
}
