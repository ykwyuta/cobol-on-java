package dev.cobolonjava.verify.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.job.JobRunner;
import dev.cobolonjava.job.jcl.Jcl;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.RecordFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * IMS のバッチを原文から JCL で流す (設計 78 §7.1、暫定判断 P-152〜P-155)。
 *
 * <p>COBOL を翻訳し、{@code //IMS} のライブラリに PSB と DBD の原文を置き、{@code EXEC PGM=DFSRRC00} で
 * 読み込みのプログラムと報告のプログラムを 2 段で流す。段の間はデータベースのデータセットである。
 */
@Tag("V1")
class ImsBatchEndToEndTest {

    @TempDir
    Path directory;

    private static final class CompiledLoader extends ClassLoader {

        private final Map<String, byte[]> classes = new HashMap<>();

        private CompiledLoader() {
            super(ImsBatchEndToEndTest.class.getClassLoader());
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
            CobolCompiler.Result result = CobolCompiler.standard().compile("PGM" + i + ".cbl", sources[i]);
            assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
            result.programs().forEach(p -> loader.classes.put(p.className(), p.classFile()));
        }
        return loader;
    }

    // ---- 資産 ----

    /** 顧客の DB。HDAM なので読み込みでキーの順を求めない。 */
    private static final String[] DBD = {
        "         DBD   NAME=BANKDB,ACCESS=(HDAM,OSAM),RMNAME=(DFSHDC40,5,10)",
        "         DATASET DD1=BANKDD,DEVICE=3390",
        "         SEGM  NAME=CUST,PARENT=0,BYTES=10,RULES=(LLL,HERE)",
        "         FIELD NAME=(CUSTNO,SEQ,U),BYTES=4,START=1,TYPE=C",
        "         FIELD NAME=CITY,BYTES=6,START=5,TYPE=C",
        "         DBDGEN",
        "         FINISH",
        "         END",
    };

    private static final String[] LOAD_PSB = {
        "         PCB   TYPE=DB,DBDNAME=BANKDB,PROCOPT=L,KEYLEN=4",
        "         SENSEG NAME=CUST,PARENT=0",
        "         PSBGEN PSBNAME=BANKLPSB,LANG=COBOL",
        "         END",
    };

    private static final String[] REPORT_PSB = {
        "         PCB   TYPE=DB,DBDNAME=BANKDB,PROCOPT=G,KEYLEN=4",
        "         SENSEG NAME=CUST,PARENT=0",
        "         PSBGEN PSBNAME=BANKRPSB,LANG=COBOL",
        "         END",
    };

    /** 入力を読み、1 件ずつ ISRT する。 */
    private static final String LOAD = source(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. BANKLOAD.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT CUST-FILE ASSIGN TO CUSTIN.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  CUST-FILE.",
            "01  CUST-REC PIC X(10).",
            "WORKING-STORAGE SECTION.",
            "77  ISRT PIC X(4) VALUE 'ISRT'.",
            "77  GU PIC X(4) VALUE 'GU  '.",
            "77  WS-DONE PIC X VALUE 'N'.",
            "77  WS-COUNT PIC 9(4) VALUE 0.",
            "01  CUST-SSA.",
            "    05 FILLER PIC X(8) VALUE 'CUST'.",
            "    05 FILLER PIC X VALUE ' '.",
            "01  BAD-SSA PIC X(11) VALUE 'CUST    *D '.",
            "LINKAGE SECTION.",
            "01  DBPCB.",
            "    05 DBDNAME PIC X(8).",
            "    05 SEGLEVEL PIC XX.",
            "    05 DBSTAT PIC XX.",
            "    05 FILLER PIC X(28).",
            "PROCEDURE DIVISION.",
            "    ENTRY 'DLITCBL' USING DBPCB.",
            "MAIN-START.",
            "    OPEN INPUT CUST-FILE.",
            "    PERFORM UNTIL WS-DONE = 'Y'",
            "        READ CUST-FILE",
            "            AT END MOVE 'Y' TO WS-DONE",
            "            NOT AT END PERFORM ONE-CUSTOMER",
            "        END-READ",
            "    END-PERFORM.",
            "    CLOSE CUST-FILE.",
            "    GOBACK.",
            "ONE-CUSTOMER.",
            "    CALL 'CBLTDLI' USING ISRT DBPCB CUST-REC CUST-SSA.",
            "    IF DBSTAT NOT = SPACES",
            "        MOVE 16 TO RETURN-CODE",
            "    END-IF.",
            "    ADD 1 TO WS-COUNT.",
            // 2 件目を入れたあと、対応していないコマンドコードの SSA で呼んで止まる (異常終了の試験だけ)
            "    IF WS-COUNT = 2 AND CUST-REC(5:6) = 'ABEND '",
            "        CALL 'CBLTDLI' USING GU DBPCB CUST-REC BAD-SSA",
            "    END-IF.");

    /** GN で全件を辿り、1 件ずつ書く。 */
    private static final String REPORT = source(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. BANKRPT.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT RPT-FILE ASSIGN TO RPTOUT.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  RPT-FILE.",
            "01  RPT-REC PIC X(10).",
            "WORKING-STORAGE SECTION.",
            "77  GN PIC X(4) VALUE 'GN  '.",
            "01  CUST-SEG PIC X(10).",
            "01  CUST-SSA.",
            "    05 FILLER PIC X(8) VALUE 'CUST'.",
            "    05 FILLER PIC X VALUE ' '.",
            "LINKAGE SECTION.",
            "01  DBPCB.",
            "    05 DBDNAME PIC X(8).",
            "    05 SEGLEVEL PIC XX.",
            "    05 DBSTAT PIC XX.",
            "    05 FILLER PIC X(28).",
            "PROCEDURE DIVISION.",
            "    ENTRY 'DLITCBL' USING DBPCB.",
            "MAIN-START.",
            "    OPEN OUTPUT RPT-FILE.",
            "    CALL 'CBLTDLI' USING GN DBPCB CUST-SEG CUST-SSA.",
            "    PERFORM UNTIL DBSTAT NOT = SPACES",
            "        WRITE RPT-REC FROM CUST-SEG",
            "        CALL 'CBLTDLI' USING GN DBPCB CUST-SEG CUST-SSA",
            "    END-PERFORM.",
            "    IF DBSTAT NOT = 'GB'",
            "        MOVE 16 TO RETURN-CODE",
            "    END-IF.",
            "    CLOSE RPT-FILE.",
            "    GOBACK.");

    /** BMP で 1 件ごとに CHKP する読み込み。入力の都市が ABEND なら、ISRT のあと CHKP の前に止まる。 */
    private static final String CHECKPOINTED = source(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. BANKBMP.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT CUST-FILE ASSIGN TO CUSTIN.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  CUST-FILE.",
            "01  CUST-REC PIC X(10).",
            "WORKING-STORAGE SECTION.",
            "77  ISRT PIC X(4) VALUE 'ISRT'.",
            "77  CHKP PIC X(4) VALUE 'CHKP'.",
            "77  GU PIC X(4) VALUE 'GU  '.",
            "77  CHKP-ID PIC X(8) VALUE 'BANKCHKP'.",
            "77  WS-DONE PIC X VALUE 'N'.",
            "01  CUST-SSA PIC X(9) VALUE 'CUST     '.",
            "01  BAD-SSA PIC X(11) VALUE 'CUST    *D '.",
            "LINKAGE SECTION.",
            "01  IOPCB.",
            "    05 LTERM PIC X(8).",
            "    05 FILLER PIC XX.",
            "    05 IOSTAT PIC XX.",
            "01  DBPCB.",
            "    05 DBDNAME PIC X(8).",
            "    05 SEGLEVEL PIC XX.",
            "    05 DBSTAT PIC XX.",
            "    05 FILLER PIC X(28).",
            "PROCEDURE DIVISION.",
            "    ENTRY 'DLITCBL' USING IOPCB DBPCB.",
            "MAIN-START.",
            "    OPEN INPUT CUST-FILE.",
            "    PERFORM UNTIL WS-DONE = 'Y'",
            "        READ CUST-FILE",
            "            AT END MOVE 'Y' TO WS-DONE",
            "            NOT AT END PERFORM ONE-CUSTOMER",
            "        END-READ",
            "    END-PERFORM.",
            "    CLOSE CUST-FILE.",
            "    GOBACK.",
            "ONE-CUSTOMER.",
            "    CALL 'CBLTDLI' USING ISRT DBPCB CUST-REC CUST-SSA.",
            "    IF CUST-REC(5:6) = 'ABEND '",
            "        CALL 'CBLTDLI' USING GU DBPCB CUST-REC BAD-SSA",
            "    END-IF.",
            "    CALL 'CBLTDLI' USING CHKP IOPCB CHKP-ID.");

    private static final String[] BMP_JOB = {
        "//BANKBMP  JOB  (ACCT),'IMS BMP'",
        "//LOAD     EXEC PGM=DFSRRC00,PARM='BMP,BANKBMP,BANKBPSB'",
        "//IMS      DD   DSN=IMS.PSBLIB,DISP=SHR",
        "//BANKDD   DD   DSN=BANK.CUSTDB,DISP=(NEW,CATLG)",
        "//CUSTIN   DD   DSN=BANK.CUSTIN,DISP=SHR",
    };

    /** 異常終了した段のあとで、別のジョブとしてデータベースを読む。 */
    private static final String[] REPORT_JOB = {
        "//BANKRPT  JOB  (ACCT),'IMS REPORT'",
        "//REPORT   EXEC PGM=DFSRRC00,PARM='DLI,BANKRPT,BANKRPSB'",
        "//IMS      DD   DSN=IMS.PSBLIB,DISP=SHR",
        "//BANKDD   DD   DSN=BANK.CUSTDB,DISP=SHR",
        "//RPTOUT   DD   DSN=BANK.REPORT,DISP=(NEW,CATLG)",
    };

    private static final String[] BMP_PSB = {
        "         PCB   TYPE=DB,DBDNAME=BANKDB,PROCOPT=A,KEYLEN=4",
        "         SENSEG NAME=CUST,PARENT=0",
        "         PSBGEN PSBNAME=BANKBPSB,LANG=COBOL",
        "         END",
    };

    private static final String[] JOB = {
        "//BANKJOB  JOB  (ACCT),'IMS BATCH'",
        "//LOAD     EXEC PGM=DFSRRC00,PARM='DLI,BANKLOAD,BANKLPSB'",
        "//IMS      DD   DSN=IMS.PSBLIB,DISP=SHR",
        "//BANKDD   DD   DSN=BANK.CUSTDB,DISP=(NEW,CATLG)",
        "//CUSTIN   DD   DSN=BANK.CUSTIN,DISP=SHR",
        "//REPORT   EXEC PGM=DFSRRC00,PARM='DLI,BANKRPT,BANKRPSB'",
        "//IMS      DD   DSN=IMS.PSBLIB,DISP=SHR",
        "//BANKDD   DD   DSN=BANK.CUSTDB,DISP=SHR",
        "//RPTOUT   DD   DSN=BANK.REPORT,DISP=(NEW,CATLG)",
    };

    private void library() {
        member("BANKDB", DBD);
        member("BANKLPSB", LOAD_PSB);
        member("BANKRPSB", REPORT_PSB);
        member("BANKBPSB", BMP_PSB);
    }

    /** 80 桁の札のメンバ。 */
    private void member(String name, String[] cards) {
        StringBuilder text = new StringBuilder();
        for (String card : cards) {
            text.append(card).append(" ".repeat(80 - card.length()));
        }
        try {
            Path library = directory.resolve("IMS.PSBLIB");
            Files.createDirectories(library);
            Path member = library.resolve(name);
            Files.write(member, CodePages.DEFAULT.encode(text.toString()));
            new DataSetAttributes(RecordFormat.FIXED, 80, CodePages.DEFAULT).write(member);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void input(String records) {
        try {
            Path path = directory.resolve("BANK.CUSTIN");
            Files.write(path, CodePages.DEFAULT.encode(records));
            new DataSetAttributes(RecordFormat.FIXED, 10, CodePages.DEFAULT).write(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private JobRunner.Result run(String[] cards) {
        Jcl.Result parsed = Jcl.read(String.join("\n", cards));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        return JobRunner.at(directory.resolve("work"), compiled(LOAD, REPORT, CHECKPOINTED), new ByteArrayOutputStream())
                .withBase(directory)
                .run(parsed.job());
    }

    private byte[] bytesOf(String name) {
        try {
            return Files.readAllBytes(directory.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- 検査 ----

    @Test
    @DisplayName("DFSRRC00 の 2 段で、読み込んだデータベースを次の段が GN で辿って書く")
    void aLoadStepAndAReportStepShareTheDatabase() {
        library();
        input("0002OSAKA 0001TOKYO 0003NAGOYA");

        JobRunner.Result result = run(JOB);

        assertEquals(0, result.step("LOAD").returnCode(), result.toString());
        assertEquals(0, result.step("REPORT").returnCode(), result.toString());
        // HDAM の根はキーの順に置く (P-153)。実機ならランダマイザの順である
        assertEquals("0001TOKYO 0002OSAKA 0003NAGOYA", CodePages.DEFAULT.decode(bytesOf("BANK.REPORT")));
    }

    @Test
    @DisplayName("BMP は I/O PCB を受けて CHKP でき、異常終了した段は最後の CHKP までの更新を残す (P-157、P-158)")
    void aBmpKeepsTheUpdatesUpToItsLastCheckpoint() {
        library();
        input("0002OSAKA 0001ABEND 0003NAGOYA");

        JobRunner.Result result = run(BMP_JOB);
        assertNotEquals(JobRunner.Status.EXECUTED, result.step("LOAD").status(), result.toString());

        JobRunner.Result report = run(REPORT_JOB);
        assertEquals(0, report.step("REPORT").returnCode(), report.toString());
        // 0002 は CHKP で確定し、0001 は CHKP の前に止まったので戻る
        assertEquals("0002OSAKA ", CodePages.DEFAULT.decode(bytesOf("BANK.REPORT")));
    }

    @Test
    @DisplayName("プログラムが異常終了した段は、データベースを書き戻さない")
    void anAbendedStepDoesNotWriteTheDatabase() {
        library();
        input("0002OSAKA 0001ABEND 0003NAGOYA");

        JobRunner.Result result = run(JOB);

        assertNotEquals(JobRunner.Status.EXECUTED, result.step("LOAD").status(), result.toString());
        Path database = directory.resolve("BANK.CUSTDB");
        assertTrue(!Files.exists(database) || bytesOf("BANK.CUSTDB").length == 0, "the database was written");
    }
}
