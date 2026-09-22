package demo;

import dev.cobolonjava.cics.CicsEnvironment;
import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskContext;
import dev.cobolonjava.cics.CicsTaskId;
import dev.cobolonjava.cics.CicsTaskServices;
import dev.cobolonjava.cics.CicsTerminalScreen;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CobolCicsTaskProgram;
import dev.cobolonjava.cics.SyncpointAction;
import dev.cobolonjava.cics.SyncpointPort;
import dev.cobolonjava.cics.TaskCompletion;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.cics.bms.BmsAid;
import dev.cobolonjava.cics.bms.BmsMapsetCatalog;
import dev.cobolonjava.cics.bms.BmsModel;
import dev.cobolonjava.cics.bms.BmsParser;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import dev.cobolonjava.cics.bms.BmsTerminalInput;
import dev.cobolonjava.db2.Db2Execution;
import dev.cobolonjava.db2.Db2ExecutionProfile;
import dev.cobolonjava.db2.Db2TaskRuntime;
import dev.cobolonjava.db2.RollbackReason;
import dev.cobolonjava.db2.UnitOfWorkOptions;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.interop.RuntimeServices;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.spring.boot4.db2.SpringManagedSqlExecutor;
import dev.cobolonjava.spring.boot4.db2.SpringManagedUnitOfWorkPort;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * デモ #009 の端末。BMS の画面を文字の格子として描き、打ち込んだ 1 行を 3270 の
 * 入力に見立てて疑似会話を回す。
 *
 * <p>ここが受け持つのは<b>端末とタスクの起動</b>だけである。画面の組み立て
 * (SEND MAP)、入力の分解 (RECEIVE MAP)、H2 への SQL は、すべて翻訳した
 * {@code TODOAPP.cbl} の中で起きる。
 */
public final class TodoTerminal {

    /** 画面に色を付ける。端末が色を解さなければ --no-color を渡す。 */
    private static boolean color = true;

    private TodoTerminal() {
    }

    public static void main(String[] args) throws IOException {
        Path mapSource = Path.of("demo/009/TODOSET.bms");
        Deque<String> scripted = new ArrayDeque<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--map" -> mapSource = Path.of(args[++i]);
                case "--no-color" -> color = false;
                case "--demo" -> scripted.addAll(List.of(
                        "ADD write the design note",
                        "ADD measure with CCVS85",
                        "DONE 1",
                        "DEL 2",
                        "OPEN 1",
                        "PF3"));
                default -> throw new IllegalArgumentException("unknown option: " + args[i]);
            }
        }

        banner();
        DataSource dataSource = database();
        BmsModel.Mapset mapset = BmsParser.parse(
                Files.readString(mapSource, StandardCharsets.ISO_8859_1));
        CicsEnvironment environment = CicsEnvironment.unconfigured()
                .withMapsets(BmsMapsetCatalog.of(List.of(mapset)));

        ProgramCatalog catalog = ProgramCatalog.builder()
                .revision("demo-009-r1")
                .cobolProgram("TODOAPP", () -> instantiate("cobol.generated.TODOAPP"))
                .build();
        CobolCicsTaskProgram program = new CobolCicsTaskProgram(
                CobolRuntime.builder(catalog).classLoader(TodoTerminal.class.getClassLoader())
                        .build(), 8, environment);
        CicsTransactionDefinition definition = new CicsTransactionDefinition(
                TransId.of("TODO"), ProgramId.of("TODOAPP"), Duration.ofSeconds(30),
                32, 0, 0, 0, true);

        JdbcTransactionManager transactions = new JdbcTransactionManager(dataSource);
        SpringManagedSqlExecutor executor = new SpringManagedSqlExecutor(dataSource);

        BufferedReader console = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Optional<BmsScreenSnapshot> onScreen = Optional.empty();
        Optional<BmsTerminalInput> input = Optional.empty();
        byte[] commarea = new byte[0];
        Optional<TransId> next = Optional.of(TransId.of("TODO"));
        int taskNumber = 0;

        while (next.isPresent()) {
            taskNumber++;
            CicsTaskContext task = new CicsTaskContext(
                    new CicsTaskId("task_todo_" + taskNumber), next.get(), "demo-operator",
                    Instant.now(), OptionalInt.of(taskNumber), Optional.of(ZoneId.of("UTC")),
                    input, onScreen, Optional.of("T001"), Optional.of("DEMOUSER"));

            TaskCompletion completion = runTask(program, definition, commarea, task,
                    dataSource, transactions, executor);

            commarea = completion.payload().commarea();
            next = completion.nextTransaction();
            CicsTerminalScreen screen = completion.screen().orElse(null);
            if (screen instanceof CicsTerminalScreen.MapScreen map) {
                onScreen = Optional.of(map.snapshot());
                draw(map.snapshot());
            } else if (screen instanceof CicsTerminalScreen.TextScreen text) {
                onScreen = Optional.empty();
                System.out.println();
                System.out.println("  " + text.text().strip());
                System.out.println();
            }
            if (next.isEmpty()) {
                break;
            }
            input = read(console, scripted, onScreen);
            if (input.isEmpty()) {
                System.out.println("[terminal] disconnected.");
                break;
            }
        }
        System.out.println("[terminal] the TODO conversation ended after "
                + taskNumber + " task(s).");
    }

    /**
     * 一つの CICS タスクを動かす。
     *
     * <p>タスクごとに Db2 の作業単位を開き、正常に終われば確定、例外で終われば取り消す。
     * {@link CicsTaskServices} を実装しているので、翻訳した COBOL の {@code EXEC SQL} は
     * この作業単位へ届く (設計 77 §4.6)。
     */
    private static TaskCompletion runTask(CobolCicsTaskProgram program,
                                          CicsTransactionDefinition definition,
                                          byte[] commarea,
                                          CicsTaskContext task,
                                          DataSource dataSource,
                                          JdbcTransactionManager transactions,
                                          SpringManagedSqlExecutor executor) {
        UnitOfWorkOptions options = new UnitOfWorkOptions(
                Db2ExecutionProfile.SPRING_MANAGED, Duration.ofSeconds(10), false, false);
        Db2TaskRuntime db2 = new Db2TaskRuntime(options,
                new SpringManagedUnitOfWorkPort(dataSource, transactions), executor);
        TaskBoundary boundary = new TaskBoundary(db2);
        try {
            TaskCompletion completion = program.execute(
                    definition, CicsPayload.ofCommarea(commarea), task, boundary);
            // 暗黙の同期点。task が正常に返ったので、業務の更新を確定する
            db2.complete();
            return completion;
        } catch (RuntimeException | Error failure) {
            db2.abort(new RollbackReason(RollbackReason.Kind.CICS_ABEND, "TASK-ABORT"));
            throw failure;
        }
    }

    /** タスクの Db2 作業単位を COBOL の session へ見せる境界。 */
    private record TaskBoundary(Db2TaskRuntime db2, Db2Execution execution)
            implements SyncpointPort, CicsTaskServices {

        TaskBoundary(Db2TaskRuntime db2) {
            this(db2, new Db2Execution(db2));
        }

        @Override
        public void contribute(RuntimeServices.Builder services) {
            services.service(Db2Execution.class, execution);
        }

        @Override
        public void bind(CobolSession session) {
            execution.bind(session);
        }

        @Override
        public void syncpoint(SyncpointAction action, CicsTaskContext task) {
            if (action == SyncpointAction.COMMIT) {
                db2.commit();
            } else {
                db2.rollback(new RollbackReason(
                        RollbackReason.Kind.EXPLICIT, "SYNCPOINT-ROLLBACK"));
            }
        }
    }

    /** H2 を起こし、空の TODO 表を作る。 */
    private static DataSource database() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:tododb;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table TODO ("
                + " TODO_ID   int          not null primary key,"
                + " DONE_FLAG char(1)      not null,"
                + " TODO_TEXT varchar(60)  not null)");
        jdbc.update("insert into TODO values (1, 'N', 'read the BMS map TODOSET.bms')");
        jdbc.update("insert into TODO values (2, 'Y', 'build the project with mvn')");
        System.out.println("[H2] table TODO created with 2 rows.");
        return dataSource;
    }

    /** 画面を読み、打ち込んだ 1 行を端末入力にする。 */
    private static Optional<BmsTerminalInput> read(BufferedReader console,
                                                   Deque<String> scripted,
                                                   Optional<BmsScreenSnapshot> onScreen)
            throws IOException {
        String line;
        if (!scripted.isEmpty()) {
            line = scripted.removeFirst();
            System.out.println("COMMAND ==> " + line);
        } else {
            System.out.print("COMMAND ==> ");
            System.out.flush();
            line = console.readLine();
            if (line == null) {
                return Optional.empty();
            }
        }
        String typed = line.strip();
        String upper = typed.toUpperCase(Locale.ROOT);
        // 打ち込んだ 1 行を AID キーと読むか、CMD field の中身と読むかを決める
        BmsAid aid = switch (upper) {
            case "PF3", "F3", "QUIT", "EXIT" -> BmsAid.PF3;
            case "CLEAR" -> BmsAid.CLEAR;
            default -> BmsAid.ENTER;
        };
        if (aid != BmsAid.ENTER || typed.isEmpty()) {
            // AID だけを送る。変更した field が無いので RECEIVE MAP は MAPFAIL になる
            return Optional.of(new BmsTerminalInput(aid, -1, List.of()));
        }
        int cursor = onScreen.map(screen -> screen.cursorOffset()).orElse(-1);
        return Optional.of(new BmsTerminalInput(aid, cursor,
                List.of(new BmsTerminalInput.FieldInput("CMD", 1, typed))));
    }

    /** 画面の記録を 24x80 の格子に置き直して描く。 */
    private static void draw(BmsScreenSnapshot screen) {
        int rows = screen.rows();
        int columns = screen.columns();
        char[][] glyphs = new char[rows][columns];
        String[][] paint = new String[rows][columns];
        for (char[] row : glyphs) {
            Arrays.fill(row, ' ');
        }
        for (BmsScreenSnapshot.FieldState field : screen.fields()) {
            // POS は属性 byte の位置である。文字はその次の桁から始まる
            int start = (field.position().row() - 1) * columns + field.position().column() - 1;
            boolean dark = field.attributes().contains(BmsModel.BasicAttribute.DRK);
            String data = dark ? "" : field.data();
            String ansi = ansiOf(field);
            for (int i = 0; i < field.length(); i++) {
                int offset = start + 1 + i;
                if (offset >= rows * columns) {
                    break;
                }
                glyphs[offset / columns][offset % columns] =
                        i < data.length() ? data.charAt(i) : ' ';
                paint[offset / columns][offset % columns] = ansi;
            }
        }
        String rule = "-".repeat(columns);
        System.out.println();
        System.out.println("    +" + rule + "+");
        for (int row = 0; row < rows; row++) {
            StringBuilder out = new StringBuilder();
            String current = null;
            for (int column = 0; column < columns; column++) {
                String wanted = paint[row][column];
                if (color && !java.util.Objects.equals(current, wanted)) {
                    out.append(wanted == null ? "\u001b[0m" : wanted);
                    current = wanted;
                }
                out.append(glyphs[row][column]);
            }
            if (color && current != null) {
                out.append("\u001b[0m");
            }
            System.out.printf("%2d  |%s|%n", row + 1, out);
        }
        System.out.println("    +" + rule + "+");
        if (screen.cursorOffset() >= 0) {
            System.out.printf("    cursor at row %d column %d%s%n",
                    screen.cursorOffset() / columns + 1, screen.cursorOffset() % columns + 1,
                    screen.alarm() ? "   (alarm)" : "");
        }
    }

    /** BMS の色を端末の色にする。色を持たない field は既定の色で描く。 */
    private static String ansiOf(BmsScreenSnapshot.FieldState field) {
        return field.color().map(value -> switch (value) {
            case BLUE -> "\u001b[94m";
            case RED -> "\u001b[91m";
            case PINK -> "\u001b[95m";
            case GREEN -> "\u001b[92m";
            case TURQUOISE -> "\u001b[96m";
            case YELLOW -> "\u001b[93m";
            case NEUTRAL, DEFAULT -> "\u001b[97m";
        }).orElse(null);
    }

    private static CobolProgram instantiate(String className) {
        try {
            return (CobolProgram) Class.forName(className)
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot load " + className
                    + " -- compile TODOAPP.cbl first", failure);
        }
    }

    private static void banner() {
        System.out.println("==================================================");
        System.out.println(" [BMS + COBOL + H2] cobol-on-java DEMO #009       ");
        System.out.println("==================================================");
        System.out.println(" Type a command on the COMMAND line, for example:");
        System.out.println("   ADD buy milk | DONE 1 | OPEN 1 | DEL 1 | LIST");
        System.out.println(" PF3 ends the conversation, CLEAR redisplays.");
        System.out.println("==================================================");
    }
}
