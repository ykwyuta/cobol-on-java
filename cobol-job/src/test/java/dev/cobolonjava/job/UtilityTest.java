package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ユーティリティの互換実装 (要件 FR-137)。
 *
 * <p>ユーティリティは翻訳された資産ではないが、ジョブから見れば同じプログラムである。
 * DD 名でデータへ触り、復帰コードを立てる。
 */
@Tag("V1")
class UtilityTest {

    @TempDir
    Path directory;

    private ByteArrayOutputStream sink;

    private JobRunner.Result run(String... cards) {
        sink = new ByteArrayOutputStream();
        dev.cobolonjava.job.jcl.Jcl.Result parsed = dev.cobolonjava.job.jcl.Jcl.read(
                String.join("\n", cards));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        return JobRunner.at(directory.resolve("work"), UtilityTest.class.getClassLoader(), sink)
                .withBase(directory)
                .run(parsed.job());
    }

    private String output() {
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    private static byte[] ebcdic(String text) {
        return CodePages.DEFAULT.encode(text);
    }

    private void write(String name, String content, int length) {
        try {
            Files.write(directory.resolve(name), ebcdic(content));
            Files.write(directory.resolve(name + ".meta"),
                    ("recfm=F\nlrecl=" + length + "\ncodepage=IBM-1047\n")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String read(String name) {
        try {
            return CodePages.DEFAULT.decode(Files.readAllBytes(directory.resolve(name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> sidecar(String name) {
        try {
            return Files.readAllLines(directory.resolve(name + ".meta"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- IEFBR14 ----

    @Test
    @DisplayName("IEFBR14 は何もせず 0 を返す (FR-137)")
    void iefbr14DoesNothing() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEFBR14",
                "//DUMMY    DD   DUMMY");

        assertEquals(JobRunner.Status.EXECUTED, result.step("STEP1").status());
        assertEquals(0, result.returnCode());
    }

    // ---- IEBGENER ----

    @Test
    @DisplayName("IEBGENER は SYSUT1 を SYSUT2 へ写す (FR-137)")
    void iebgenerCopiesTheDataSet() {
        write("IN.DAT", "AAAAABBBBB", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=IN.DAT,DISP=SHR",
                "//SYSUT2   DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
                "//SYSPRINT DD   SYSOUT=*");

        assertEquals(0, result.returnCode());
        assertEquals("AAAAABBBBB", read("OUT.DAT"));
        assertEquals(List.of("recfm=F", "lrecl=5", "codepage=IBM-1047"), sidecar("OUT.DAT"));
        assertTrue(output().contains("IEB147I 2 RECORDS COPIED"), output());
    }

    @Test
    @DisplayName("DD が無ければ IEBGENER 自身が失敗する (FR-137)")
    void iebgenerNeedsItsInput() {
        // SYSUT1 に DD を書いていない。割当ての段では何も起きず、
        // 置き場に同じ名前が無いことをユーティリティが見つける
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT2   DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
                "//SYSPRINT DD   SYSOUT=*");

        assertEquals(12, result.returnCode());
        assertTrue(output().contains("SYSUT1 NOT FOUND"), output());
    }

    @Test
    @DisplayName("IEBGENER の制御文は未対応である (FR-137)")
    void iebgenerControlStatementsAreNotSupportedYet() {
        write("IN.DAT", "AAAAA", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=IN.DAT,DISP=SHR",
                "//SYSUT2   DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  GENERATE MAXFLDS=1",
                "/*");

        assertEquals(12, result.returnCode());
        assertTrue(output().contains("NOT SUPPORTED YET"), output());
    }

    // ---- IDCAMS ----

    @Test
    @DisplayName("IDCAMS REPRO は写す (FR-137)")
    void idcamsReproCopies() {
        write("IN.DAT", "AAAAABBBBB", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IDCAMS",
                "//INDD     DD   DSN=IN.DAT,DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  REPRO INFILE(INDD) OUTFILE(OUTDD)",
                "/*");

        assertEquals(0, result.returnCode());
        assertEquals("AAAAABBBBB", read("OUT.DAT"));
        assertTrue(output().contains("NUMBER OF RECORDS PROCESSED WAS 2"), output());
    }

    @Test
    @DisplayName("IDCAMS REPRO はデータセット名でも指せる (FR-137)")
    void idcamsReproTakesDataSetNames() {
        write("IN.DAT", "AAAAA", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IDCAMS",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  REPRO INDATASET(IN.DAT) OUTDATASET(OUT.DAT)",
                "/*");

        assertEquals(0, result.returnCode());
        assertEquals("AAAAA", read("OUT.DAT"));
    }

    @Test
    @DisplayName("IDCAMS DELETE は消す (FR-137)")
    void idcamsDeleteRemovesTheDataSet() {
        write("OLD.DAT", "AAAAA", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IDCAMS",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  DELETE OLD.DAT",
                "/*");

        assertEquals(0, result.returnCode());
        assertFalse(Files.exists(directory.resolve("OLD.DAT")));
        assertFalse(Files.exists(directory.resolve("OLD.DAT.meta")));
        assertTrue(output().contains("DELETED"), output());
    }

    @Test
    @DisplayName("ないものを DELETE すれば 8 になる (FR-137)")
    void deletingWhatIsNotThereGivesEight() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IDCAMS",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  DELETE NOSUCH.DAT",
                "/*");

        assertEquals(8, result.returnCode());
        assertTrue(output().contains("NOT FOUND"), output());
    }

    @Test
    @DisplayName("失敗しても後続の制御文は動く (FR-137)")
    void laterStatementsStillRun() {
        write("IN.DAT", "AAAAA", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IDCAMS",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  DELETE OUT.DAT",
                "  REPRO INDATASET(IN.DAT) OUTDATASET(OUT.DAT)",
                "/*");

        // 消せなかったので 8。しかし写しは行われている
        assertEquals(8, result.returnCode());
        assertEquals("AAAAA", read("OUT.DAT"));
    }

    @Test
    @DisplayName("IDCAMS DEFINE CLUSTER は空のデータセットを作る (FR-137)")
    void idcamsDefineCreatesAnEmptyDataSet() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IDCAMS",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  DEFINE CLUSTER (NAME(NEW.KSDS) -",
                "                  RECORDSIZE(80 120) -",
                "                  INDEXED KEYS(5 0))",
                "/*");

        assertEquals(0, result.returnCode());
        assertEquals("", read("NEW.KSDS"));
        assertEquals(List.of("recfm=F", "lrecl=120", "codepage=IBM-1047"), sidecar("NEW.KSDS"));
    }

    @Test
    @DisplayName("同じ名前の DEFINE は誤りである (FR-137)")
    void aDuplicateDefineFails() {
        write("NEW.KSDS", "", 80);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IDCAMS",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  DEFINE CLUSTER (NAME(NEW.KSDS) RECORDSIZE(80 80))",
                "/*");

        assertEquals(12, result.returnCode());
        assertTrue(output().contains("DUPLICATE DATA SET NAME"), output());
    }

    @Test
    @DisplayName("IDCAMS LISTCAT は一覧を出す (FR-137)")
    void idcamsListcatLists() {
        write("A.DAT", "AAAAA", 5);
        write("B.DAT", "BBBBB", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IDCAMS",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  LISTCAT",
                "/*");

        assertEquals(0, result.returnCode());
        assertTrue(output().contains("NONVSAM ------- A.DAT"), output());
        assertTrue(output().contains("NONVSAM ------- B.DAT"), output());
    }

    @Test
    @DisplayName("LISTCAT ENTRIES はないものを 4 で知らせる (FR-137)")
    void listcatReportsMissingEntries() {
        write("A.DAT", "AAAAA", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IDCAMS",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  LISTCAT ENTRIES(A.DAT NOSUCH.DAT)",
                "/*");

        assertEquals(4, result.returnCode());
        assertTrue(output().contains("NONVSAM ------- A.DAT"), output());
        assertTrue(output().contains("ENTRY NOSUCH.DAT NOT FOUND"), output());
    }

    @Test
    @DisplayName("知らない制御文は誤りである (FR-137)")
    void unknownCommandsAreReported() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IDCAMS",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  EXPORT SOMETHING",
                "/*");

        assertEquals(12, result.returnCode());
        assertTrue(output().contains("COMMAND NOT SUPPORTED YET: EXPORT"), output());
    }

    @Test
    @DisplayName("消してから作る書き方が通る (FR-137)")
    void theDeleteThenDefinePatternWorks() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IDCAMS",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  DELETE WORK.KSDS",
                "  DEFINE CLUSTER (NAME(WORK.KSDS) RECORDSIZE(50 50))",
                "/*");

        // 1 回目は消すものがないので 8 になるが、作るほうは通る
        assertEquals(8, result.returnCode());
        assertTrue(Files.exists(directory.resolve("WORK.KSDS")));
    }
}
