package dev.cobolonjava.verify.batch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.job.Job;
import dev.cobolonjava.job.JobRunner;
import dev.cobolonjava.job.JobScript;
import dev.cobolonjava.job.jcl.Jcl;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 組み上がった処理系を<b>1 本のバッチとして</b>流す (要件 13 章 P1 の受け入れ基準)。
 *
 * <p>ここまでの検査は層ごとに分かれていた。翻訳系は翻訳したところまで、実行系は
 * 記憶域の中身まで、ジョブ実行は手で書いたプログラムまでである。<b>つなぎ目は
 * どこも測っていなかった</b>。
 *
 * <p>この検査は原文から始める。COBOL を翻訳し、翻訳した組を載せた読み込み器を
 * ジョブ実行へ渡し、JCL で 3 段のステップを流す。段の間はデータセットであり、
 * 2 段目は整列ユーティリティである。最後に<b>出したバイト列</b>を突き合わせる。
 *
 * <p>記述形式は 2 つある。JCL と宣言的形式が<b>同じ結果を返す</b>ことも見る。
 * 要件はそこまでを受け入れ基準に置いている。
 */
@Tag("V1")
class BatchJobEndToEndTest {

    @TempDir
    Path directory;

    /** 翻訳した組を名前で返す読み込み器。ジョブ実行はここからプログラムを引く。 */
    private static final class CompiledLoader extends ClassLoader {

        private final Map<String, byte[]> classes = new HashMap<>();

        private CompiledLoader() {
            super(BatchJobEndToEndTest.class.getClassLoader());
        }

        void add(String name, byte[] classFile) {
            classes.put(name, classFile);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** 固定形式の原文にする。7 桁目から本文を置く。 */
    private static String source(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("       ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static CompiledLoader compiled(String... sources) {
        CompiledLoader loader = new CompiledLoader();
        for (int i = 0; i < sources.length; i++) {
            CobolCompiler.Result result = CobolCompiler.standard()
                    .compile("PGM" + i + ".cbl", sources[i]);
            assertTrue(result.succeeded(),
                    () -> "unexpected diagnostics: " + result.diagnostics());
            result.programs().forEach(p -> loader.add(p.className(), p.classFile()));
        }
        return loader;
    }

    private void write(String name, String content, int length) {
        try {
            Files.write(directory.resolve(name), CodePages.DEFAULT.encode(content));
            Files.write(directory.resolve(name + ".meta"),
                    ("recfm=F\nlrecl=" + length + "\ncodepage=IBM-1047\n")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] bytesOf(String name) {
        try {
            return Files.readAllBytes(directory.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String textOf(String name) {
        return CodePages.DEFAULT.decode(bytesOf(name));
    }

    private JobRunner.Result run(Job job, CompiledLoader loader, Path base) {
        return JobRunner.at(base.resolve("work"), loader, new ByteArrayOutputStream())
                .withBase(base)
                .run(job);
    }

    // ---- 資産 ----

    /** 勤務時間と単価から支給額を出し、次の段へ渡す。 */
    private static final String EXTRACT = source(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. PAYEXT.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT EMP-FILE ASSIGN TO EMPIN.",
            "    SELECT PAY-FILE ASSIGN TO PAYOUT.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  EMP-FILE.",
            "01  EMP-REC.",
            "    05 EMP-NO    PIC X(5).",
            "    05 EMP-NAME  PIC X(15).",
            "    05 EMP-HOURS PIC 9(3).",
            "    05 EMP-RATE  PIC 9(5)V99.",
            "FD  PAY-FILE.",
            "01  PAY-REC.",
            "    05 PAY-NO    PIC X(5).",
            "    05 PAY-NAME  PIC X(15).",
            "    05 PAY-GROSS PIC 9(8).",
            "WORKING-STORAGE SECTION.",
            "01  WS-DONE PIC X VALUE 'N'.",
            "PROCEDURE DIVISION.",
            "MAIN-START.",
            "    OPEN INPUT EMP-FILE.",
            "    OPEN OUTPUT PAY-FILE.",
            "    PERFORM UNTIL WS-DONE = 'Y'",
            "        READ EMP-FILE",
            "            AT END MOVE 'Y' TO WS-DONE",
            "            NOT AT END PERFORM ONE-EMPLOYEE",
            "        END-READ",
            "    END-PERFORM.",
            "    CLOSE EMP-FILE.",
            "    CLOSE PAY-FILE.",
            "    STOP RUN.",
            "ONE-EMPLOYEE.",
            "    MOVE EMP-NO TO PAY-NO.",
            "    MOVE EMP-NAME TO PAY-NAME.",
            "    COMPUTE PAY-GROSS = EMP-HOURS * EMP-RATE * 100.",
            "    WRITE PAY-REC.");

    /** 並べ替えた結果を読んで、紙を作る。 */
    private static final String REPORT = source(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. PAYRPT.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT PAY-FILE ASSIGN TO PAYSRT.",
            "    SELECT RPT-FILE ASSIGN TO RPTOUT.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  PAY-FILE.",
            "01  PAY-REC.",
            "    05 PAY-NO    PIC X(5).",
            "    05 PAY-NAME  PIC X(15).",
            "    05 PAY-GROSS PIC 9(8).",
            "FD  RPT-FILE.",
            "01  RPT-REC     PIC X(40).",
            "WORKING-STORAGE SECTION.",
            "01  WS-DONE  PIC X VALUE 'N'.",
            "01  WS-TOTAL PIC 9(10) VALUE ZERO.",
            "01  WS-AMT   PIC 9(7)V99.",
            "01  WS-LINE.",
            "    05 R-NAME  PIC X(15).",
            "    05 FILLER  PIC X(2) VALUE SPACES.",
            "    05 R-GROSS PIC ZZ,ZZZ,ZZ9.99.",
            "    05 FILLER  PIC X(10) VALUE SPACES.",
            "PROCEDURE DIVISION.",
            "MAIN-START.",
            "    OPEN INPUT PAY-FILE.",
            "    OPEN OUTPUT RPT-FILE.",
            "    PERFORM UNTIL WS-DONE = 'Y'",
            "        READ PAY-FILE",
            "            AT END MOVE 'Y' TO WS-DONE",
            "            NOT AT END PERFORM ONE-LINE",
            "        END-READ",
            "    END-PERFORM.",
            "    PERFORM TOTAL-LINE.",
            "    CLOSE PAY-FILE.",
            "    CLOSE RPT-FILE.",
            "    STOP RUN.",
            "ONE-LINE.",
            "    ADD PAY-GROSS TO WS-TOTAL.",
            "    MOVE PAY-NAME TO R-NAME.",
            "    COMPUTE WS-AMT = PAY-GROSS / 100.",
            "    MOVE WS-AMT TO R-GROSS.",
            "    WRITE RPT-REC FROM WS-LINE.",
            "TOTAL-LINE.",
            "    MOVE 'TOTAL' TO R-NAME.",
            "    COMPUTE WS-AMT = WS-TOTAL / 100.",
            "    MOVE WS-AMT TO R-GROSS.",
            "    WRITE RPT-REC FROM WS-LINE.");

    /** 入力 3 件。1 件 30 バイトである。 */
    private static final String EMPLOYEES =
            "E0001ALICE          0400002500"
            + "E0002BOB            1600001250"
            + "E0003CAROL          0100009999";

    /** 支給額の降順。1 件 28 バイトである。 */
    private static final String SORTED =
            "E0002BOB            00200000"
            + "E0001ALICE          00100000"
            + "E0003CAROL          00099990";

    /** 紙。1 行 40 バイトである。 */
    private static final String PAPER =
            "BOB                   2,000.00          "
            + "ALICE                 1,000.00          "
            + "CAROL                   999.90          "
            + "TOTAL                 3,999.90          ";

    private static final String[] CARDS = {
        "//PAYROLL  JOB  (ACCT),'MONTHLY PAY'",
        "//EXTRACT  EXEC PGM=PAYEXT",
        "//EMPIN    DD   DSN=PAY.EMP,DISP=SHR",
        "//PAYOUT   DD   DSN=PAY.GROSS,DISP=(NEW,CATLG)",
        "//SORTPAY  EXEC PGM=SORT",
        "//SORTIN   DD   DSN=PAY.GROSS,DISP=SHR",
        "//SORTOUT  DD   DSN=PAY.SORTED,DISP=(NEW,CATLG)",
        "//SYSOUT   DD   SYSOUT=*",
        "//SYSIN    DD   *",
        "  SORT FIELDS=(21,8,ZD,D)",
        "//REPORT   EXEC PGM=PAYRPT",
        "//PAYSRT   DD   DSN=PAY.SORTED,DISP=SHR",
        "//RPTOUT   DD   DSN=PAY.REPORT,DISP=(NEW,CATLG)",
    };

    private static final String[] SCRIPT = {
        "JOB PAYROLL",
        "STEP EXTRACT PGM=PAYEXT",
        "  DD EMPIN DSN=PAY.EMP",
        "  DD PAYOUT DSN=PAY.GROSS",
        "STEP SORTPAY PGM=SORT",
        "  DD SORTIN DSN=PAY.GROSS",
        "  DD SORTOUT DSN=PAY.SORTED",
        "  DD SYSOUT SYSOUT",
        "  DD SYSIN DATA",
        "  SORT FIELDS=(21,8,ZD,D)",
        "  END",
        "STEP REPORT PGM=PAYRPT",
        "  DD PAYSRT DSN=PAY.SORTED",
        "  DD RPTOUT DSN=PAY.REPORT",
    };

    /** 紙の行数を数え、呼び出した副プログラムに判定させる。 */
    private static final String CHECK = source(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. PAYCHK.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT RPT-FILE ASSIGN TO RPTIN.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  RPT-FILE.",
            "01  RPT-REC PIC X(40).",
            "WORKING-STORAGE SECTION.",
            "01  WS-DONE  PIC X VALUE 'N'.",
            "01  WS-LINES PIC 9(4) VALUE ZERO.",
            "01  WS-VERDICT PIC 9(4).",
            "PROCEDURE DIVISION.",
            "MAIN-START.",
            "    OPEN INPUT RPT-FILE.",
            "    PERFORM UNTIL WS-DONE = 'Y'",
            "        READ RPT-FILE",
            "            AT END MOVE 'Y' TO WS-DONE",
            "            NOT AT END ADD 1 TO WS-LINES",
            "        END-READ",
            "    END-PERFORM.",
            "    CLOSE RPT-FILE.",
            "    CALL 'PAYSUB' USING WS-LINES WS-VERDICT.",
            "    MOVE WS-VERDICT TO RETURN-CODE.",
            "    STOP RUN.");

    /** 呼ばれる側。行が 1 本も無ければ 8 を返す。 */
    private static final String SUB = source(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. PAYSUB.",
            "DATA DIVISION.",
            "LINKAGE SECTION.",
            "01  LK-LINES   PIC 9(4).",
            "01  LK-VERDICT PIC 9(4).",
            "PROCEDURE DIVISION USING LK-LINES LK-VERDICT.",
            "SUB-START.",
            "    IF LK-LINES = ZERO",
            "        MOVE 8 TO LK-VERDICT",
            "    ELSE",
            "        MOVE 0 TO LK-VERDICT",
            "    END-IF.",
            "    EXIT PROGRAM.");

    private static final String[] CHECKED = {
        "//PAYROLL  JOB  (ACCT),'MONTHLY PAY'",
        "//EXTRACT  EXEC PGM=PAYEXT",
        "//EMPIN    DD   DSN=PAY.EMP,DISP=SHR",
        "//PAYOUT   DD   DSN=PAY.GROSS,DISP=(NEW,CATLG)",
        "//SORTPAY  EXEC PGM=SORT",
        "//SORTIN   DD   DSN=PAY.GROSS,DISP=SHR",
        "//SORTOUT  DD   DSN=PAY.SORTED,DISP=(NEW,CATLG)",
        "//SYSOUT   DD   SYSOUT=*",
        "//SYSIN    DD   *",
        "  SORT FIELDS=(21,8,ZD,D)",
        "//REPORT   EXEC PGM=PAYRPT",
        "//PAYSRT   DD   DSN=PAY.SORTED,DISP=SHR",
        "//RPTOUT   DD   DSN=PAY.REPORT,DISP=(NEW,CATLG)",
        "//CHECK    EXEC PGM=PAYCHK",
        "//RPTIN    DD   DSN=PAY.REPORT,DISP=SHR",
        "//KEEP     EXEC PGM=IEBGENER,COND=(0,LT,CHECK)",
        "//SYSUT1   DD   DSN=PAY.REPORT,DISP=SHR",
        "//SYSUT2   DD   DSN=PAY.ARCHIVE,DISP=(NEW,CATLG)",
        "//SYSPRINT DD   SYSOUT=*",
        "//SYSIN    DD   DUMMY",
    };

    // ---- 検査 ----

    @Test
    @DisplayName("原文から流した 3 段のバッチが、決まったバイト列を出す (FR-130, FR-137)")
    void aThreeStepBatchProducesTheExpectedBytes() {
        write("PAY.EMP", EMPLOYEES, 30);
        Jcl.Result parsed = Jcl.read(String.join("\n", CARDS));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());

        JobRunner.Result result = run(parsed.job(), compiled(EXTRACT, REPORT), directory);

        assertEquals(0, result.returnCode(), result.toString());
        // 段の間のデータセットも、最後の紙も、バイトで決まっている
        assertEquals(SORTED, textOf("PAY.SORTED"));
        assertEquals(PAPER, textOf("PAY.REPORT"));
        assertEquals(4 * 40, bytesOf("PAY.REPORT").length);
    }

    @Test
    @DisplayName("JCL と宣言的形式が同じバイト列を出す (FR-131, FR-132)")
    void bothFrontEndsAgree() {
        Path fromJcl = directory.resolve("jcl");
        Path fromScript = directory.resolve("script");
        seed(fromJcl);
        seed(fromScript);

        Jcl.Result parsed = Jcl.read(String.join("\n", CARDS));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        JobScript.Result script = JobScript.read(String.join("\n", SCRIPT));
        assertTrue(script.succeeded(), () -> script.diagnostics().toString());

        assertEquals(0, run(parsed.job(), compiled(EXTRACT, REPORT), fromJcl).returnCode());
        assertEquals(0, run(script.job(), compiled(EXTRACT, REPORT), fromScript).returnCode());

        // 記述形式は入口が違うだけである。出るものは<b>同じバイト列</b>でなければならない
        for (String name : List.of("PAY.GROSS", "PAY.SORTED", "PAY.REPORT")) {
            assertArrayEquals(bytesOf(fromJcl, name), bytesOf(fromScript, name), name);
        }
        assertEquals(PAPER, CodePages.DEFAULT.decode(bytesOf(fromJcl, "PAY.REPORT")));
    }

    @Test
    @DisplayName("呼び出しと復帰コードが、次のステップの条件に効く (FR-062, FR-136, FR-137)")
    void aCalledSubprogramDrivesTheNextStep() {
        write("PAY.EMP", EMPLOYEES, 30);
        Jcl.Result parsed = Jcl.read(String.join("\n", CHECKED));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());

        JobRunner.Result result =
                run(parsed.job(), compiled(EXTRACT, REPORT, CHECK, SUB), directory);

        // 副プログラムが 0 を返したので、条件つきのステップも流れる
        assertEquals(0, result.step("CHECK").returnCode());
        assertEquals(JobRunner.Status.EXECUTED, result.step("KEEP").status());
        assertArrayEquals(bytesOf("PAY.REPORT"), bytesOf("PAY.ARCHIVE"));
        assertEquals(0, result.returnCode());
    }

    /** PARM を最大の長さで受け、SYSIN の 1 枚を読んで、どちらも DISPLAY する。 */
    private static final String PARMS = source(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. PARMS.",
            "DATA DIVISION.",
            "WORKING-STORAGE SECTION.",
            "01  W-LEN     PIC 9(4).",
            "01  W-CARD    PIC X(8).",
            "LINKAGE SECTION.",
            "01  L-PARM.",
            "    05 L-LEN  PIC S9(4) COMP.",
            "    05 L-TEXT PIC X(100).",
            "PROCEDURE DIVISION USING L-PARM.",
            "    MOVE L-LEN TO W-LEN",
            "    ACCEPT W-CARD",
            "    IF L-LEN > 0",
            "        DISPLAY 'LEN=' W-LEN ' ' L-TEXT(1:L-LEN) ' CARD=' W-CARD",
            "    ELSE",
            "        DISPLAY 'LEN=' W-LEN ' CARD=' W-CARD",
            "    END-IF",
            "    GOBACK.");

    /**
     * ホストの慣わしどおり、PARM を最大の長さで宣言した主プログラムへ短い PARM と PARM なしを
     * 渡し、制御カードを SYSIN で渡す。以前はどちらも呼ぶ前の検査で止まり、ACCEPT は SYSIN を
     * 読まなかった (z/OS probe の CBLPARM と、JCL の probe で見つかった)。
     */
    @Test
    @DisplayName("主プログラムは宣言より短い PARM を受け、ACCEPT はステップの SYSIN を読む (FR-134)")
    void aShortParmAndTheStepsSysinReachTheProgram() {
        Jcl.Result parsed = Jcl.read(String.join("\n", List.of(
                "//PARMJOB  JOB (ACCT),'PARM'",
                "//SHORT    EXEC PGM=PARMS,PARM='SHORT'",
                "//SYSOUT   DD SYSOUT=*",
                "//SYSIN    DD *",
                "CARD1",
                "/*",
                "//NONE     EXEC PGM=PARMS",
                "//SYSOUT   DD SYSOUT=*",
                "//SYSIN    DD *",
                "CARD2",
                "/*")));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        JobRunner.Result result = JobRunner.at(directory.resolve("work"), compiled(PARMS), output)
                .withBase(directory)
                .run(parsed.job());

        assertEquals(0, result.returnCode(), () -> result.steps().toString());
        // W-CARD は 8 桁なので後ろに空白が 3 つ付く
        assertEquals("LEN=0005 SHORT CARD=CARD1   |LEN=0000 CARD=CARD2   |",
                output.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|"));
    }

    private void seed(Path base) {
        try {
            Files.createDirectories(base);
            Files.write(base.resolve("PAY.EMP"), CodePages.DEFAULT.encode(EMPLOYEES));
            Files.write(base.resolve("PAY.EMP.meta"),
                    "recfm=F\nlrecl=30\ncodepage=IBM-1047\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] bytesOf(Path base, String name) {
        try {
            return Files.readAllBytes(base.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
