package demo;

import dev.cobolonjava.db2.CursorOptions;
import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2TaskRuntime;
import dev.cobolonjava.db2.SqlBindings;
import dev.cobolonjava.db2.SqlHostVariable;
import dev.cobolonjava.db2.SqlOperation;
import dev.cobolonjava.db2.SqlOutcome;
import dev.cobolonjava.db2.SqlPlan;
import dev.cobolonjava.db2.SqlValueDescriptor;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.data.BinaryDecimal;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.data.TruncMode;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.interop.CobolCallResult;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import dev.cobolonjava.spring.boot4.db2.SpringManagedSqlExecutor;
import dev.cobolonjava.spring.boot4.db2.SpringManagedUnitOfWorkPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

/**
 * Spring Boot 4.x / Spring Framework 7 トランザクション管理下での
 * Db2 SQL 実行と COBOL ビジネスロジック連携デモ。
 */
public class SpringDb2DemoMain {

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println(" [Spring & Db2] cobol-on-java DEMO #008           ");
        System.out.println("==================================================");

        // 1. Spring DataSource と JdbcTemplate の準備 (インメモリ H2 DB)
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:bankdb;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // テーブル作成 & 初期レコード投入
        jdbc.execute("create table account (id int primary key, balance decimal(7,2))");
        jdbc.update("insert into account (id, balance) values (1001, 1500.00)");
        System.out.println("[DB Setup] Initial Account (ID=1001) Balance: 1500.00");

        // 2. Spring トランザクション同期ポート & SQL Executor の初期化
        JdbcTransactionManager txManager = new JdbcTransactionManager(dataSource);
        SpringManagedSqlExecutor sqlExecutor = new SpringManagedSqlExecutor(dataSource);

        UnitOfWorkOptions options = new UnitOfWorkOptions(
                Db2ExecutionProfile.SPRING_MANAGED, Duration.ofSeconds(5), false, false);

        // 3. ProgramCatalog に COBOL クラス (ACC-PROCESS) を登録
        ProgramCatalog catalog = ProgramCatalog.builder()
                .revision("demo-008-r1")
                .cobolProgram("ACC-PROCESS", () -> {
                    try {
                        Class<?> clazz = Class.forName("cobol.generated.ACC_PROCESS");
                        return (dev.cobolonjava.runtime.program.CobolProgram)
                                clazz.getDeclaredConstructor().newInstance();
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to load ACC_PROCESS", e);
                    }
                })
                .build();

        CobolRuntime runtime = CobolRuntime.builder(catalog).build();

        // 4. トランザクション 1: 預金取引 (Deposit $500.00) -> 正常コミット
        System.out.println("\n--- [Transaction 1] Deposit $500.00 (Commit Expected) ---");
        runTransaction(runtime, options, dataSource, txManager, sqlExecutor, jdbc,
                1001, "D", "0050000", false);

        // 5. トランザクション 2: 残高不足取引 ($5000.00 引出) -> ロールバック
        System.out.println("\n--- [Transaction 2] Withdraw $5000.00 (Insufficient Funds -> Rollback) ---");
        runTransaction(runtime, options, dataSource, txManager, sqlExecutor, jdbc,
                1001, "W", "0500000", true);

        // 6. 最終 DB 状態の確認
        Double finalBalance = jdbc.queryForObject(
                "select balance from account where id = 1001", Double.class);
        System.out.println("==================================================");
        System.out.println("[Final Verification]");
        System.out.println("  - Expected Account Balance: 2000.00");
        System.out.println("  - Actual Account Balance  : " + finalBalance);
        System.out.println("==================================================");
    }

    private static void runTransaction(CobolRuntime runtime,
                                       UnitOfWorkOptions options,
                                       DataSource dataSource,
                                       JdbcTransactionManager txManager,
                                       SpringManagedSqlExecutor sqlExecutor,
                                       JdbcTemplate jdbc,
                                       int accountId,
                                       String txType,
                                       String amountZoned,
                                       boolean expectRollback) {
        SpringManagedUnitOfWorkPort uowPort = new SpringManagedUnitOfWorkPort(dataSource, txManager);
        try (CobolSession session = runtime.openSession();
             Db2TaskRuntime task = new Db2TaskRuntime(options, uowPort, sqlExecutor)) {

            // Step A: SELECT balance INTO host variable (digits=7 -> 4 bytes)
            DataView balanceView = Storage.allocate(PackedDecimal.byteLength(7)).whole();
            SqlPlan selectPlan = new SqlPlan("S1", "DB2", SqlOperation.SELECT_ONE,
                    "select balance from account where id = ?", CursorOptions.none());

            SqlHostVariable inId = SqlHostVariable.input(
                    Storage.copyOf(BinaryDecimal.encode(Decimal.of(accountId, 0), 4, 0, TruncMode.BIN)).whole(),
                    new SqlValueDescriptor.BinaryInteger(4, 0, TruncMode.BIN), null);

            SqlHostVariable outBal = SqlHostVariable.output(balanceView,
                    new SqlValueDescriptor.PackedDecimal(7, 2, false), null);

            task.execute(selectPlan, new SqlBindings(List.of(inId, outBal)), session);

            // Double 値を取り出し、COBOL 呼び出し用ストレージへ設定
            Decimal currentBal = PackedDecimal.decode(balanceView.toByteArray(), 2, NumProcMode.NOPFD);
            System.out.println("  [SQL SELECT] Current Balance in DB: " + currentBal);

            // Step B: COBOL プログラム ACC-PROCESS を呼んで残高計算
            // 01 LNK-ACC-ID    PIC 9(4)
            // 01 LNK-TX-TYPE   PIC X(1)
            // 01 LNK-TX-AMOUNT PIC 9(5)V99
            // 01 LNK-NEW-BAL   PIC 9(5)V99
            // 01 LNK-STATUS    PIC X(2)
            Storage idStore = Storage.copyOf(CodePages.IBM_1047.encode(String.format("%04d", accountId)));
            Storage typeStore = Storage.copyOf(CodePages.IBM_1047.encode(txType));
            Storage amtStore = Storage.copyOf(CodePages.IBM_1047.encode(amountZoned));
            // 初期値として現在の残高をセット（例: "0150000"）
            long balRaw = currentBal.signedUnscaled().longValue();
            Storage balStore = Storage.copyOf(CodePages.IBM_1047.encode(String.format("%07d", balRaw)));
            Storage statusStore = Storage.allocate(2);

            CobolCallResult cobolResult = session.call("ACC-PROCESS",
                    idStore.whole(), typeStore.whole(), amtStore.whole(),
                    balStore.whole(), statusStore.whole());

            String status = CodePages.IBM_1047.decode(statusStore.array());
            System.out.println("  [COBOL Result] Status: " + status + ", ReturnCode: " + cobolResult.returnCode());

            if ("OK".equals(status)) {
                // Step C: UPDATE account SET balance = ? WHERE id = ?
                String newBalStr = CodePages.IBM_1047.decode(balStore.array());
                // 例: "0200000" -> 2000.00
                Decimal newBalDecimal = Decimal.parse(
                        newBalStr.substring(0, 5) + "." + newBalStr.substring(5));

                SqlPlan updatePlan = new SqlPlan("U1", "DB2", SqlOperation.UPDATE,
                        "update account set balance = ? where id = ?", CursorOptions.none());

                SqlHostVariable inNewBal = SqlHostVariable.input(
                        Storage.copyOf(PackedDecimal.encode(newBalDecimal, 7, 2, false)).whole(),
                        new SqlValueDescriptor.PackedDecimal(7, 2, false), null);

                task.execute(updatePlan, new SqlBindings(List.of(inNewBal, inId)), session);
                System.out.println("  [SQL UPDATE] Balance updated to: " + newBalDecimal);

                task.commit();
                System.out.println("  [Transaction] COMMIT completed successfully.");
            } else {
                // 残高不足などの場合: ロールバック
                task.rollback(new dev.cobolonjava.db2.RollbackReason(
                        dev.cobolonjava.db2.RollbackReason.Kind.EXPLICIT, "COBOL_INSUFFICIENT_FUNDS"));
                System.out.println("  [Transaction] ROLLBACK triggered due to status: " + status);
            }
            task.complete();
        }
    }
}
