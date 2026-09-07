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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 世代データグループ (要件 FR-114、暫定判断 P-060)。
 *
 * <p>世代は<b>普通のデータセット</b>であり、{@code 基底名.GnnnnV00} という名前で並ぶ。
 * {@code 基底名(+1)} と書けるのは JCL が相対番号を絶対名へ直すからである。
 *
 * <p>ここで確かめたいのは番号の付き方そのものより、<b>いつ直すか</b>と<b>いつ数えるか</b>
 * である。ジョブの初めに 1 度だけ直し、目録へ載った世代だけを数える。どちらを崩しても、
 * 実機では 1 つのファイルを指すジョブがここでは 2 つ作る。
 */
@Tag("V1")
class GenerationDataGroupTest {

    @TempDir
    Path directory;

    private ByteArrayOutputStream sink;

    private JobRunner.Result run(String... cards) {
        sink = new ByteArrayOutputStream();
        dev.cobolonjava.job.jcl.Jcl.Result parsed = dev.cobolonjava.job.jcl.Jcl.read(
                String.join("\n", cards));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        return JobRunner.at(directory.resolve("work"),
                        GenerationDataGroupTest.class.getClassLoader(), sink)
                .withBase(directory)
                .run(parsed.job());
    }

    private String output() {
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    /** 制御文だけを差し替える {@code IDCAMS} のジョブ。 */
    private JobRunner.Result idcams(String... control) {
        String[] head = new String[] {
            "//J        JOB  (ACCT)",
            "//STEP1    EXEC PGM=IDCAMS",
            "//SYSPRINT DD   SYSOUT=*",
            "//SYSIN    DD   *",
        };
        String[] all = new String[head.length + control.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(control, 0, all, head.length, control.length);
        return run(all);
    }

    /** 基底を 1 つ登録する。 */
    private void define(String options) {
        JobRunner.Result result = idcams("  DEFINE GDG(NAME(PAY.HISTORY) " + options + ")");
        assertEquals(0, result.returnCode(), output());
    }

    /** 世代を 1 つ書く。1 本のジョブであり、終われば目録へ載っている。 */
    private JobRunner.Result write(String dsn, String content) {
        return run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSUT1   DD   *",
                content,
                "/*",
                "//SYSUT2   DD   DSN=" + dsn + ",DISP=(NEW,CATLG),SPACE=(TRK,(1))");
    }

    private boolean onVolume(String name) {
        return Files.exists(directory.resolve(name));
    }

    private boolean cataloged(String name) {
        return new SystemCatalog(directory).isCataloged(name);
    }

    private String read(String name) {
        try {
            return CodePages.DEFAULT.decode(Files.readAllBytes(directory.resolve(name))).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- 基底の登録 ----

    @Test
    @DisplayName("登録していない基底は相対世代で指せない (FR-114)")
    void aBaseMustBeDefinedFirst() {
        // 黙って絶対名を組み立てると、DEFINE GDG を忘れたジョブが世代でないものを作る
        JobRunner.Result result = write("PAY.HISTORY(+1)", "ROW 1");

        assertEquals(JobRunner.Status.FAILED, result.step("STEP1").status());
        assertTrue(result.step("STEP1").failure().contains("NOT A GENERATION DATA GROUP"),
                result.step("STEP1").failure());
    }

    @Test
    @DisplayName("同じ基底は 2 度登録できない (FR-114)")
    void aBaseIsDefinedOnce() {
        define("LIMIT(3)");
        JobRunner.Result result = idcams("  DEFINE GDG(NAME(PAY.HISTORY) LIMIT(3))");

        assertEquals(12, result.returnCode());
        assertTrue(output().contains("IDC3013I DUPLICATE DATA SET NAME PAY.HISTORY"), output());
    }

    @Test
    @DisplayName("LIMIT を書かない DEFINE GDG は誤りである (FR-114)")
    void defineNeedsALimit() {
        // 通せば、あふれる境目の無い群れができて世代が溜まり続ける
        JobRunner.Result result = idcams("  DEFINE GDG(NAME(PAY.HISTORY))");

        assertEquals(12, result.returnCode());
        assertTrue(output().contains("IDC3202I DEFINE GDG NEEDS LIMIT()"), output());
    }

    @Test
    @DisplayName("基底は置き場にファイルを持たない (FR-114)")
    void aBaseIsNotADataSet() {
        define("LIMIT(3)");

        assertFalse(onVolume("PAY.HISTORY"));
    }

    @Test
    @DisplayName("LISTCAT は基底を見せる (FR-114, FR-137)")
    void listcatShowsTheBase() {
        define("LIMIT(3)");
        idcams("  LISTCAT");

        assertTrue(output().contains("GDG ----------- PAY.HISTORY"), output());
    }

    // ---- 番号の付き方 ----

    @Test
    @DisplayName("初めの世代は G0001V00 である (FR-114)")
    void theFirstGenerationIsOne() {
        define("LIMIT(3)");
        JobRunner.Result result = write("PAY.HISTORY(+1)", "ROW 1");

        assertEquals(0, result.returnCode(), output());
        assertTrue(onVolume("PAY.HISTORY.G0001V00"));
        assertEquals("ROW 1", read("PAY.HISTORY.G0001V00"));
    }

    @Test
    @DisplayName("(0) はいちばん新しい世代である (FR-114)")
    void zeroIsTheNewestGeneration() {
        define("LIMIT(3)");
        write("PAY.HISTORY(+1)", "ROW 1");
        write("PAY.HISTORY(+1)", "ROW 2");

        assertTrue(onVolume("PAY.HISTORY.G0002V00"));
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSUT1   DD   DSN=PAY.HISTORY(0),DISP=SHR",
                "//SYSUT2   DD   DSN=PAY.COPY,DISP=(NEW,CATLG),SPACE=(TRK,(1))");

        assertEquals(0, result.returnCode(), output());
        assertEquals("ROW 2", read("PAY.COPY"));
    }

    @Test
    @DisplayName("(-1) は 1 つ前の世代である (FR-114)")
    void minusOneIsTheGenerationBefore() {
        define("LIMIT(3)");
        write("PAY.HISTORY(+1)", "ROW 1");
        write("PAY.HISTORY(+1)", "ROW 2");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSUT1   DD   DSN=PAY.HISTORY(-1),DISP=SHR",
                "//SYSUT2   DD   DSN=PAY.COPY,DISP=(NEW,CATLG),SPACE=(TRK,(1))");

        assertEquals(0, result.returnCode(), output());
        assertEquals("ROW 1", read("PAY.COPY"));
    }

    @Test
    @DisplayName("世代が無ければ (0) は見つからない (FR-114, FR-133)")
    void zeroWithoutAGenerationIsNotFound() {
        define("LIMIT(3)");
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSUT1   DD   DSN=PAY.HISTORY(0),DISP=SHR",
                "//SYSUT2   DD   DSN=PAY.COPY,DISP=(NEW,CATLG),SPACE=(TRK,(1))");

        assertEquals(JobRunner.Status.FAILED, result.step("STEP1").status());
        assertTrue(result.step("STEP1").failure().contains("DATA SET NOT FOUND"),
                result.step("STEP1").failure());
    }

    // ---- 番号はジョブの初めに決まる ----

    @Test
    @DisplayName("同じジョブの (+1) は同じ世代を指す (FR-114)")
    void plusOneIsTheSameDataSetAllJobLong() {
        // ステップごとに引き直すと、1 つのファイルへ 2 度書くジョブが世代を 2 つ作る
        define("LIMIT(3)");
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSUT1   DD   *",
                "ROW 1",
                "/*",
                "//SYSUT2   DD   DSN=PAY.HISTORY(+1),DISP=(NEW,PASS),",
                "//              SPACE=(TRK,(1))",
                "//STEP2    EXEC PGM=IEBGENER",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSUT1   DD   DSN=PAY.HISTORY(+1),DISP=OLD",
                "//SYSUT2   DD   DSN=PAY.COPY,DISP=(NEW,CATLG),SPACE=(TRK,(1))");

        assertEquals(0, result.returnCode(), output());
        assertEquals("ROW 1", read("PAY.COPY"));
        assertFalse(onVolume("PAY.HISTORY.G0002V00"), "世代を 2 つ作ってはならない");
    }

    @Test
    @DisplayName("(0) はジョブが始まった時点の世代である (FR-114)")
    void zeroDoesNotMoveWithinAJob() {
        define("LIMIT(3)");
        write("PAY.HISTORY(+1)", "ROW 1");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSUT1   DD   *",
                "ROW 2",
                "/*",
                "//SYSUT2   DD   DSN=PAY.HISTORY(+1),DISP=(NEW,CATLG),",
                "//              SPACE=(TRK,(1))",
                "//STEP2    EXEC PGM=IEBGENER",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSUT1   DD   DSN=PAY.HISTORY(0),DISP=SHR",
                "//SYSUT2   DD   DSN=PAY.COPY,DISP=(NEW,CATLG),SPACE=(TRK,(1))");

        assertEquals(0, result.returnCode(), output());
        // ステップ 1 が作った G0002V00 ではなく、ジョブの初めの G0001V00 である
        assertEquals("ROW 1", read("PAY.COPY"));
    }

    @Test
    @DisplayName("同じジョブで登録した基底は相対世代で指せない (FR-114)")
    void aBaseDefinedInTheSameJobIsTooLate() {
        // ホストも JCL をジョブの初めに解釈する。実機で動かないジョブを通してはならない
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IDCAMS",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  DEFINE GDG(NAME(PAY.HISTORY) LIMIT(3))",
                "/*",
                "//STEP2    EXEC PGM=IEFBR14",
                "//OUT      DD   DSN=PAY.HISTORY(+1),DISP=(NEW,CATLG),",
                "//              SPACE=(TRK,(1))");

        assertEquals(JobRunner.Status.FAILED, result.step("STEP2").status());
        assertTrue(result.step("STEP2").failure().contains("NOT A GENERATION DATA GROUP"),
                result.step("STEP2").failure());
    }

    // ---- 組み入れとあふれ ----

    @Test
    @DisplayName("目録へ載らなかった世代は数に入らない (FR-114, FR-133)")
    void aGenerationIsCountedOnlyWhenItIsCataloged() {
        define("LIMIT(3)");
        JobRunner.Result first = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEFBR14",
                "//OUT      DD   DSN=PAY.HISTORY(+1),DISP=(NEW,DELETE),",
                "//              SPACE=(TRK,(1))");

        assertEquals(0, first.returnCode(), output());
        assertFalse(onVolume("PAY.HISTORY.G0001V00"));
        // 次のジョブの (+1) はまだ 1 である。群れは増えていない
        write("PAY.HISTORY(+1)", "ROW 1");
        assertTrue(onVolume("PAY.HISTORY.G0001V00"));
    }

    @Test
    @DisplayName("あふれた世代は目録から外れる (FR-114)")
    void theOldestRollsOffAtTheLimit() {
        define("LIMIT(2)");
        write("PAY.HISTORY(+1)", "ROW 1");
        write("PAY.HISTORY(+1)", "ROW 2");
        write("PAY.HISTORY(+1)", "ROW 3");

        // NOSCRATCH なので置き場には残る。外れたのは目録からである
        assertTrue(onVolume("PAY.HISTORY.G0001V00"));
        assertFalse(cataloged("PAY.HISTORY.G0001V00"));
        assertTrue(cataloged("PAY.HISTORY.G0002V00"));
        assertTrue(cataloged("PAY.HISTORY.G0003V00"));
    }

    @Test
    @DisplayName("外れた世代には名前で届かない (FR-114, FR-131)")
    void aRolledOffGenerationIsOutOfReach() {
        define("LIMIT(1)");
        write("PAY.HISTORY(+1)", "ROW 1");
        write("PAY.HISTORY(+1)", "ROW 2");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSUT1   DD   DSN=PAY.HISTORY.G0001V00,DISP=SHR",
                "//SYSUT2   DD   DSN=PAY.COPY,DISP=(NEW,CATLG),SPACE=(TRK,(1))");

        assertEquals(JobRunner.Status.FAILED, result.step("STEP1").status());
        assertTrue(result.step("STEP1").failure().contains("DATA SET NOT FOUND"),
                result.step("STEP1").failure());
    }

    @Test
    @DisplayName("SCRATCH は外した世代を消す (FR-114)")
    void scratchErasesTheRolledOffGeneration() {
        define("LIMIT(1) SCRATCH");
        write("PAY.HISTORY(+1)", "ROW 1");
        write("PAY.HISTORY(+1)", "ROW 2");

        assertFalse(onVolume("PAY.HISTORY.G0001V00"));
        assertTrue(onVolume("PAY.HISTORY.G0002V00"));
    }

    @Test
    @DisplayName("EMPTY はあふれたとき全部外す (FR-114)")
    void emptyRollsOffEveryOlderGeneration() {
        define("LIMIT(2) EMPTY");
        write("PAY.HISTORY(+1)", "ROW 1");
        write("PAY.HISTORY(+1)", "ROW 2");
        write("PAY.HISTORY(+1)", "ROW 3");

        assertFalse(cataloged("PAY.HISTORY.G0001V00"));
        assertFalse(cataloged("PAY.HISTORY.G0002V00"));
        assertTrue(cataloged("PAY.HISTORY.G0003V00"));
    }

    @Test
    @DisplayName("絶対名で書いた世代も群れに入る (FR-114)")
    void anAbsoluteNameJoinsTheGroup() {
        // 実資産には絶対名で書いたジョブがある。名前の形だけが群れの一員である証しである
        define("LIMIT(3)");
        write("PAY.HISTORY.G0001V00", "ROW 1");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSPRINT DD   SYSOUT=*",
                "//SYSUT1   DD   DSN=PAY.HISTORY(0),DISP=SHR",
                "//SYSUT2   DD   DSN=PAY.COPY,DISP=(NEW,CATLG),SPACE=(TRK,(1))");

        assertEquals(0, result.returnCode(), output());
        assertEquals("ROW 1", read("PAY.COPY"));
    }

    @Test
    @DisplayName("LISTCAT は外れた世代を見せない (FR-114, FR-137)")
    void listcatDoesNotShowARolledOffGeneration() {
        // 並べているのは目録である。置き場に残っていても名前では引けない
        define("LIMIT(1)");
        write("PAY.HISTORY(+1)", "ROW 1");
        write("PAY.HISTORY(+1)", "ROW 2");
        idcams("  LISTCAT");

        assertTrue(onVolume("PAY.HISTORY.G0001V00"));
        assertFalse(output().contains("PAY.HISTORY.G0001V00"), output());
        assertTrue(output().contains("NONVSAM ------- PAY.HISTORY.G0002V00"), output());
    }

    // ---- 基底を消す ----

    @Test
    @DisplayName("世代の残る基底は FORCE がないと消せない (FR-114, FR-137)")
    void deletingANonEmptyBaseNeedsForce() {
        // 消せてしまうと、誰も名前で指せない世代が置き場に残る
        define("LIMIT(3)");
        write("PAY.HISTORY(+1)", "ROW 1");
        JobRunner.Result result = idcams("  DELETE PAY.HISTORY GDG");

        assertEquals(8, result.returnCode());
        assertTrue(output().contains("IDC3211I ENTRY PAY.HISTORY IS NOT EMPTY"), output());
        assertTrue(onVolume("PAY.HISTORY.G0001V00"));
    }

    @Test
    @DisplayName("FORCE は世代ごと消す (FR-114, FR-137)")
    void forceDeletesTheGenerationsToo() {
        define("LIMIT(3)");
        write("PAY.HISTORY(+1)", "ROW 1");
        JobRunner.Result result = idcams("  DELETE PAY.HISTORY GDG FORCE");

        assertEquals(0, result.returnCode(), output());
        assertFalse(onVolume("PAY.HISTORY.G0001V00"));
        assertTrue(output().contains("IDC0550I ENTRY (B) PAY.HISTORY DELETED"), output());
    }

    @Test
    @DisplayName("登録の無い基底を消せば見つからない (FR-114, FR-137)")
    void deletingAnUnknownBaseIsEight() {
        JobRunner.Result result = idcams("  DELETE PAY.HISTORY GDG");

        assertEquals(8, result.returnCode());
        assertTrue(output().contains("IDC3012I ENTRY PAY.HISTORY NOT FOUND"), output());
    }
}
