package dev.cobolonjava.pli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.db2.Db2Execution;
import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2TaskRuntime;
import dev.cobolonjava.db2.ResourceLeaseId;
import dev.cobolonjava.db2.RollbackReason;
import dev.cobolonjava.db2.SqlBindings;
import dev.cobolonjava.db2.SqlExecutorPort;
import dev.cobolonjava.db2.SqlHostVariable;
import dev.cobolonjava.db2.SqlOperation;
import dev.cobolonjava.db2.SqlOutcome;
import dev.cobolonjava.db2.SqlPlan;
import dev.cobolonjava.db2.SqlValueCodec;
import dev.cobolonjava.db2.UnitOfWork;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.db2.UnitOfWorkPort;
import dev.cobolonjava.db2.UnitOfWorkState;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bank-of-Z の BNKSTMT を原文のまま<b>動かす</b>。
 *
 * <p>翻訳できることしか測っていなかったので、{@code IF ... THEN DO; ... END;} が上限まで
 * 回って止まることにも、合計行を作る {@code PUT STRING} が読み飛ばされていたことにも
 * 気付けなかった。Db2 は台本どおりに行を返す模擬で、DATECARD と SORTCODE は
 * {@code BNKSTMT.jcl} の埋め込みデータと同じものを割り当てる。
 */
class BankOfZStatementTest {

    private static final Path SOURCE = Path.of("..", "reference", "Bank-of-Z-main", "src",
            "base", "batch", "pli", "BNKSTMT.pli");

    @TempDir
    Path temporary;

    /** カーソルごとに FETCH で返す行。尽きたら SQLCODE 100。 */
    private final Map<String, Deque<List<Object>>> cursors = new HashMap<>();
    /** 単一行の SELECT が返す行。無ければ SQLCODE 100。 */
    private List<Object> customer;

    @BeforeEach
    void requireBankOfZ() {
        Assumptions.assumeTrue(Files.isRegularFile(SOURCE), "Bank-of-Z is not checked out");
    }

    @Test
    @DisplayName("口座が無ければ見出しと完了の行だけを出す")
    void runsToCompletionWithoutAccounts() throws Exception {
        List<String> lines = run();

        // PRINT ファイルの list-directed: 項目は tab 位置 25, 49, 73 に揃い、PAGE_COUNT
        // (FIXED BIN(15)) は幅 9 の欄に右寄せになる
        assertEquals(List.of(
                "BNKSTMT - BANK MONTHLY STATEMENT PROGRAM",
                "==========================================",
                " ",
                String.format("%-48s%s", "SORT CODE FROM PARAMETER: ", "123456"),
                String.format("%-48s%s", "REPORTING MONTH FROM DATECARD: ", "202606"),
                String.format("%-24s%-24s%-24s%s", "STATEMENT PERIOD: ", "20260601", " TO ",
                        "20260630"),
                "INITIALIZING DB2 CONNECTION...",
                "DB2 CONNECTION ESTABLISHED VIA DSN RUN",
                String.format("%-48s%s", "PROCESSING ACCOUNTS FOR SORT CODE: ", "123456"),
                "TERMINATING DB2 CONNECTION...",
                "DB2 CONNECTION TERMINATED",
                " ",
                "BNKSTMT COMPLETED SUCCESSFULLY",
                String.format("%-48s%9s", "TOTAL STATEMENTS GENERATED: ", "0")),
                lines);
    }

    @Test
    @DisplayName("口座 1 件と取引 2 件で、明細と合計行を作る")
    void printsAStatementWithTransactionsAndSummary() throws Exception {
        cursors.put("ACCT_CURSOR", rows(List.of("ACCT", "0000000001", "123456", "00000001",
                "CURRENT", new BigDecimal("1.50"), "2020-01-01", 500, "2026-05-31",
                "2026-06-30", new BigDecimal("1234.56"), new BigDecimal("1234.56"))));
        customer = List.of("CUST", "123456", "0000000001", "Mr", "John", "Smith", 19800101,
                "555-0100", "1 High Street", "", "Leeds", "LS1 1AA", "UK", "ACTIVE",
                20200101, 700, 20260101);
        cursors.put("TRAN_CURSOR", rows(
                List.of("PRTR", "123456", "00000001", "2026-06-05", "093015", "REF000000001",
                        "CR", "SALARY", new BigDecimal("2000.00")),
                List.of("PRTR", "123456", "00000001", "2026-06-10", "140500", "REF000000002",
                        "DEB", "GROCERIES", new BigDecimal("-45.25"))));

        List<String> lines = run();

        // 空でない行を、右の空白と改ページ文字を落として並べる。左の空白 (字下げと中央寄せ) は残す
        List<String> content = lines.stream()
                .map(line -> line.replace(PrintFile.FORM_FEED, "").stripTrailing())
                .filter(line -> !line.isEmpty()).toList();
        assertEquals(List.of(
                "BNKSTMT - BANK MONTHLY STATEMENT PROGRAM",
                "==========================================",
                String.format("%-48s%s", "SORT CODE FROM PARAMETER: ", "123456"),
                String.format("%-48s%s", "REPORTING MONTH FROM DATECARD: ", "202606"),
                String.format("%-24s%-24s%-24s%s", "STATEMENT PERIOD: ", "20260601", " TO ",
                        "20260630"),
                "INITIALIZING DB2 CONNECTION...",
                "DB2 CONNECTION ESTABLISHED VIA DSN RUN",
                String.format("%-48s%s", "PROCESSING ACCOUNTS FOR SORT CODE: ", "123456"),
                // TRIM(HV_ACCT_NUMBER) は文字列なので tab 位置 49 から
                String.format("%-48s%s", "GENERATING STATEMENT FOR ACCOUNT: ", "00000001"),
                // CENTRE(x, 132) は余りの 1 桁を右に置く
                " ".repeat(61) + "BANK OF Z",
                " ".repeat(53) + "MONTHLY ACCOUNT STATEMENT",
                " ".repeat(50) + "=".repeat(32),
                "STATEMENT DATE: 2026-06-30",
                "STATEMENT PERIOD: 20260601 TO 20260630",
                "PAGE: 1",
                "CUSTOMER INFORMATION:",
                "  NAME: Mr John Smith",
                "  ADDRESS: 1 High Street",
                "           Leeds, LS1 1AA",
                "           UK",
                "  PHONE: 555-0100",
                "ACCOUNT INFORMATION:",
                "  ACCOUNT NUMBER: 123456-00000001",
                "  ACCOUNT TYPE: CURRENT",
                // TRIM(CHAR(FIXED DEC(4,2))) は小数 2 桁を保つ
                "  INTEREST RATE: 1.50%",
                "  OVERDRAFT LIMIT: $500",
                "TRANSACTION HISTORY:",
                "DATE       TIME    TYPE  REFERENCE    DESCRIPTION                      AMOUNT",
                "-".repeat(86),
                // 金額は PUT STRING ... (F(10,2)) を TRIM したもの。借方は ABS を取って負号を足す
                String.format("%s %s %-3s   %s %-30s %s", "2026-06-05", "09:30:15", "CR",
                        "REF000000001", "SALARY", "2000.00"),
                String.format("%s %s %-3s   %s %-30s %s", "2026-06-10", "14:05:00", "DEB",
                        "REF000000002", "GROCERIES", "-45.25"),
                "=".repeat(32),
                "STATEMENT SUMMARY:",
                "=".repeat(32),
                // 期首 = 利用可能残高 + 借方 - 貸方 = 1234.56 + 45.25 - 2000.00
                "  OPENING BALANCE:        $   -720.19",
                "  TOTAL CREDITS:          $   2000.00",
                "  TOTAL DEBITS:           $     45.25",
                "  CLOSING BALANCE:        $   1234.56",
                "  AVAILABLE BALANCE:      $   1234.56",
                "  TRANSACTION COUNT:          2",
                " ".repeat(54) + "*** END OF STATEMENT ***",
                " ".repeat(48) + "Thank you for banking with Bank of Z",
                "TERMINATING DB2 CONNECTION...",
                "DB2 CONNECTION TERMINATED",
                "BNKSTMT COMPLETED SUCCESSFULLY",
                String.format("%-48s%9s", "TOTAL STATEMENTS GENERATED: ", "1")),
                content);

        // REPORT_LINE は CHAR(132)。SYSPRINT の DD は DCB を書いていないので LINESIZE は既定の
        // 120 であり、残りの 12 桁 (空白) は次の行へ送られる
        int banner = lines.indexOf(String.format("%-120s", " ".repeat(61) + "BANK OF Z"));
        assertTrue(banner > 0, "the centred title is written as a 120-column line");
        assertEquals(" ".repeat(12), lines.get(banner + 1));

        // 改ページは 2 回: 61 行目を書くときの ENDPAGE と、明細の終わりの PUT PAGE
        List<Integer> pageThrows = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(PrintFile.FORM_FEED)) pageThrows.add(i);
        }
        assertEquals(2, pageThrows.size(), () -> "page throws at " + pageThrows);
        assertEquals(PrintFile.PAGESIZE, pageThrows.get(0));
        // PUT PAGE のあとの PUT SKIP は新しいページの 2 行目に書く
        assertEquals("TERMINATING DB2 CONNECTION...", lines.get(pageThrows.get(1) + 1));
    }

    @SafeVarargs
    private static Deque<List<Object>> rows(List<Object>... rows) {
        return new ArrayDeque<>(Arrays.asList(rows));
    }

    private List<String> run() throws Exception {
        PliCompiler.Result result = PliCompiler.standard().compile("BNKSTMT.pli",
                Files.readString(SOURCE, StandardCharsets.UTF_8));
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        GeneratedLoader loader = new GeneratedLoader();
        CobolProgram program = (CobolProgram) loader.define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        FakeUnit unit = new FakeUnit();
        UnitOfWorkPort units = new UnitOfWorkPort() {
            public Db2ExecutionProfile profile() { return Db2ExecutionProfile.SPRING_MANAGED; }
            public UnitOfWork begin(UnitOfWorkOptions options) { return unit; }
            public void close() { }
        };
        Db2Execution execution = new Db2Execution(new Db2TaskRuntime(new UnitOfWorkOptions(
                Db2ExecutionProfile.SPRING_MANAGED, Duration.ofSeconds(5), false, false),
                units, new ScriptedSql()));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .cobolProgram("BNKSTMT", program.programSignature(), () -> program).build();
        // DD * の埋め込みデータは 80 桁の固定長 (既定の属性) である
        Path sortCode = Files.write(temporary.resolve("SORTCODE"),
                CodePages.DEFAULT.encode(String.format("%-80s", "123456")));
        Path dateCard = Files.write(temporary.resolve("DATECARD"),
                CodePages.DEFAULT.encode(String.format("%-80s", "202606")));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CobolSession session = CobolRuntime.builder(catalog).classLoader(loader)
                .dataSets(() -> new DataSetCatalog(temporary)
                        .assign("SORTCODE", sortCode).assign("DATECARD", dateCard))
                .build()
                .openSession(output, RuntimeServices.builder()
                        .service(Db2Execution.class, execution).build())) {
            execution.bind(session);
            session.runMain("BNKSTMT");
        }
        return output.toString(StandardCharsets.UTF_8).lines().toList();
    }

    /** 台本どおりに行を返す Db2。INTO の host variable へ、列の値を記載順に書く。 */
    private final class ScriptedSql implements SqlExecutorPort {
        private final SqlValueCodec codec = new SqlValueCodec();

        public Db2ExecutionProfile profile() { return Db2ExecutionProfile.SPRING_MANAGED; }

        public SqlOutcome execute(SqlPlan plan, SqlBindings bindings, CobolSession session,
                                  UnitOfWork unit) {
            List<Object> row;
            if (plan.operation() == SqlOperation.FETCH_CURSOR) {
                Deque<List<Object>> pending = cursors.entrySet().stream()
                        .filter(entry -> plan.normalizedSql().contains(entry.getKey()))
                        .map(Map.Entry::getValue).findFirst().orElse(new ArrayDeque<>());
                row = pending.poll();
            } else if (plan.operation() == SqlOperation.SELECT_ONE) {
                row = customer;
            } else {
                return SqlOutcome.success(0);
            }
            if (row == null) {
                return new SqlOutcome(100, "02000", 0, List.of(), false);
            }
            List<SqlHostVariable> outputs = bindings.values().stream()
                    .filter(value -> value.mode().isOutput()).toList();
            assertEquals(row.size(), outputs.size(), plan.normalizedSql());
            for (int i = 0; i < row.size(); i++) {
                codec.applyOutput(outputs.get(i), codec.encodeOutput(outputs.get(i), row.get(i)));
            }
            return SqlOutcome.success(1);
        }
    }

    private static final class FakeUnit implements UnitOfWork {
        UnitOfWorkState state = UnitOfWorkState.ACTIVE;
        public Db2ExecutionProfile profile() { return Db2ExecutionProfile.SPRING_MANAGED; }
        public ResourceLeaseId resourceLeaseId() { return new ResourceLeaseId("bnkstmt"); }
        public UnitOfWorkState state() { return state; }
        public void commit() { state = UnitOfWorkState.COMMITTED; }
        public void rollback(RollbackReason reason) { state = UnitOfWorkState.ROLLED_BACK; }
    }

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(BankOfZStatementTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
