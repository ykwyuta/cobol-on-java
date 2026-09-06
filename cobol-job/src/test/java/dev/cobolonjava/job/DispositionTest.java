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
 * {@code DISP} を実行へ効かせる (要件 FR-133)。
 *
 * <p>{@code DISP} はステップの<b>前と後の両方</b>を決める。前では、あるはずのものが無い
 * ジョブを止める。後では、残すか消すかを決める。どちらも「黙って動いてしまう」ことを
 * 防ぐためにある。
 */
@Tag("V1")
class DispositionTest {

    @TempDir
    Path directory;

    private ByteArrayOutputStream sink;

    private JobRunner.Result run(String... cards) {
        sink = new ByteArrayOutputStream();
        dev.cobolonjava.job.jcl.Jcl.Result parsed = dev.cobolonjava.job.jcl.Jcl.read(
                String.join("\n", cards), directory);
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        return JobRunner.at(directory.resolve("work"),
                        DispositionTest.class.getClassLoader(), sink)
                .withBase(directory)
                .run(parsed.job());
    }

    private String output() {
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
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

    private String read(String name) {
        try {
            return CodePages.DEFAULT.decode(Files.readAllBytes(directory.resolve(name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean exists(String name) {
        return Files.exists(directory.resolve(name));
    }

    // ---- ステップの前 ----

    @Test
    @DisplayName("DISP=SHR と書いたものが無ければステップは動かない (FR-133)")
    void aMissingDataSetStopsTheStep() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEFBR14",
                "//IN       DD   DSN=NOSUCH.DAT,DISP=SHR");

        assertEquals(JobRunner.Status.FAILED, result.step("STEP1").status());
        assertEquals(12, result.returnCode());
        assertTrue(result.step("STEP1").failure().contains("DATA SET NOT FOUND"),
                result.step("STEP1").failure());
    }

    @Test
    @DisplayName("DISP=NEW と書いたものが既にあればステップは動かない (FR-133)")
    void anExistingDataSetStopsANewAllocation() {
        write("OUT.DAT", "AAAAA", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEFBR14",
                "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG)");

        assertEquals(JobRunner.Status.FAILED, result.step("STEP1").status());
        assertTrue(result.step("STEP1").failure().contains("DUPLICATE NAME"),
                result.step("STEP1").failure());
        // 中身は触られていない
        assertEquals("AAAAA", read("OUT.DAT"));
    }

    @Test
    @DisplayName("DISP=NEW は空のデータセットを割り当てる (FR-133)")
    void newAllocatesAnEmptyDataSet() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=IN.DAT,DISP=(NEW,CATLG)",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG)");

        // 割り当てた時点で場所は取れている。読めば 0 件である
        assertEquals(JobRunner.Status.EXECUTED, result.step("STEP1").status());
        assertTrue(exists("IN.DAT"));
        assertEquals("", read("OUT.DAT"));
    }

    @Test
    @DisplayName("割当てに失敗すると後続のステップは流される (FR-133)")
    void aJclErrorFlushesTheRest() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEFBR14",
                "//IN       DD   DSN=NOSUCH.DAT,DISP=SHR",
                "//STEP2    EXEC PGM=IEFBR14,COND=EVEN",
                "//STEP3    EXEC PGM=IEFBR14");

        // COND=EVEN でも覆せない。これは異常終了とは別のものである
        assertEquals(JobRunner.Status.FLUSHED, result.step("STEP2").status());
        assertEquals(JobRunner.Status.FLUSHED, result.step("STEP3").status());
    }

    // ---- ステップの後 ----

    @Test
    @DisplayName("DISP=NEW だけなら作ったものは消える (FR-133)")
    void aNewDataSetGoesAwayByDefault() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=WORK.DAT,DISP=NEW",
                "//SYSUT2   DD   DSN=OUT.DAT,DISP=(NEW,CATLG)");

        assertEquals(0, result.returnCode());
        assertFalse(exists("WORK.DAT"), "DISP=NEW の既定は DELETE である");
        assertTrue(exists("OUT.DAT"));
    }

    @Test
    @DisplayName("DISP=(OLD,DELETE) はステップのあとで消す (FR-133)")
    void deleteRemovesTheDataSet() {
        write("IN.DAT", "AAAAA", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=IN.DAT,DISP=(OLD,DELETE)",
                "//SYSUT2   DD   DSN=OUT.DAT,DISP=(NEW,CATLG)");

        assertEquals(0, result.returnCode());
        assertFalse(exists("IN.DAT"));
        assertFalse(exists("IN.DAT.meta"), "属性のサイドカーも一緒に消える");
        assertEquals("AAAAA", read("OUT.DAT"));
    }

    @Test
    @DisplayName("異常終了したときの処置は 3 つ目が決める (FR-133)")
    void theAbnormalDispositionApplies() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BOOM",
                "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG,DELETE)");

        assertEquals(JobRunner.Status.ABENDED, result.step("STEP1").status());
        assertFalse(exists("OUT.DAT"), "異常終了したので 3 つ目の DELETE が効く");
    }

    @Test
    @DisplayName("3 つ目を書かなければ 2 つ目と同じになる (FR-133)")
    void theAbnormalDispositionFollowsTheNormalOne() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BOOM",
                "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG)");

        assertEquals(JobRunner.Status.ABENDED, result.step("STEP1").status());
        assertTrue(exists("OUT.DAT"), "2 つ目が CATLG なので 3 つ目も CATLG である");
    }

    // ---- DISP=MOD ----

    @Test
    @DisplayName("DISP=MOD は OPEN OUTPUT を末尾への書き足しへ変える (FR-133)")
    void modAppendsEvenWhenTheProgramWritesOutput() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=WRITELOG",
                "//REPORT   DD   DSN=LOG.DAT,DISP=MOD",
                "//SYSIN    DD   *",
                "FIRST",
                "/*",
                "//STEP2    EXEC PGM=WRITELOG",
                "//REPORT   DD   DSN=LOG.DAT,DISP=MOD",
                "//SYSIN    DD   *",
                "SECOND");

        assertEquals(0, result.returnCode());
        // WRITELOG は OPEN OUTPUT と書いてある。上書きなら SECOND だけが残る
        assertTrue(read("LOG.DAT").contains("FIRST"), read("LOG.DAT"));
        assertTrue(read("LOG.DAT").contains("SECOND"), read("LOG.DAT"));
    }

    @Test
    @DisplayName("DISP=MOD は無ければ作る (FR-133)")
    void modCreatesWhatIsNotThere() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=WRITELOG",
                "//REPORT   DD   DSN=LOG.DAT,DISP=(MOD,CATLG)",
                "//SYSIN    DD   *",
                "ONLY");

        assertEquals(0, result.returnCode());
        assertTrue(read("LOG.DAT").contains("ONLY"), read("LOG.DAT"));
    }

    // ---- 作業領域の後始末 ----

    @Test
    @DisplayName("正常に終わったステップの作業領域は片付ける (FR-130)")
    void theStepWorkDirectoryIsCleanedUp() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=WRITELOG",
                "//REPORT   DD   DSN=LOG.DAT,DISP=(NEW,CATLG)",
                "//SYSIN    DD   *",
                "LINE");

        assertEquals(0, result.returnCode());
        assertFalse(Files.exists(directory.resolve("work").resolve("J.STEP1")),
                "スプールも埋め込みデータも、そのステップの間だけ要るものである");
    }

    @Test
    @DisplayName("異常終了したステップの作業領域は残す (FR-130)")
    void theStepWorkDirectoryStaysAfterAnAbend() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BOOM",
                "//SYSIN    DD   *",
                "LINE");

        assertEquals(JobRunner.Status.ABENDED, result.step("STEP1").status());
        assertTrue(Files.exists(directory.resolve("work").resolve("J.STEP1")),
                "何が起きたのかを見られるほうが役に立つ");
    }

    // ---- 宣言的形式 ----

    @Test
    @DisplayName("宣言的形式は DISP を書かなければ何も言っていないことになる (FR-132)")
    void theDeclarativeFormatSaysNothingByDefault() {
        JobScript.Result parsed = JobScript.read(String.join("\n",
                "JOB J",
                "STEP STEP1 PGM=IEFBR14",
                "  DD IN DSN=nosuch.dat"));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());

        JobRunner.Result result = JobRunner.at(directory.resolve("work"),
                        DispositionTest.class.getClassLoader(), new ByteArrayOutputStream())
                .withBase(directory)
                .run(parsed.job());

        // 状態を言っていないので、無くても止まらない
        assertEquals(JobRunner.Status.EXECUTED, result.step("STEP1").status());
    }

    @Test
    @DisplayName("宣言的形式でも DISP を書ける (FR-132, FR-133)")
    void theDeclarativeFormatCanSayIt() {
        JobScript.Result parsed = JobScript.read(String.join("\n",
                "JOB J",
                "STEP STEP1 PGM=IEFBR14",
                "  DD IN DSN=nosuch.dat DISP=SHR"));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());

        JobRunner.Result result = JobRunner.at(directory.resolve("work"),
                        DispositionTest.class.getClassLoader(), new ByteArrayOutputStream())
                .withBase(directory)
                .run(parsed.job());

        assertEquals(JobRunner.Status.FAILED, result.step("STEP1").status());
    }

    @Test
    @DisplayName("知らない DISP の綴りは誤りである (FR-132)")
    void anUnknownDispositionIsReported() {
        JobScript.Result parsed = JobScript.read(String.join("\n",
                "JOB J",
                "STEP STEP1 PGM=IEFBR14",
                "  DD IN DSN=x.dat DISP=(NEW,SAVE)"));

        assertFalse(parsed.succeeded());
        assertTrue(parsed.diagnostics().toString().contains("unknown DISP: SAVE"),
                parsed.diagnostics().toString());
    }

    @Test
    @DisplayName("JCL の DISP も 3 つとも読む (FR-131, FR-133)")
    void theJclFrontendReadsAllThree() {
        dev.cobolonjava.job.jcl.Jcl.Result parsed = dev.cobolonjava.job.jcl.Jcl.read(
                String.join("\n",
                        "//J        JOB  (ACCT)",
                        "//STEP1    EXEC PGM=P",
                        "//OUT      DD   DSN=OUT.DAT,DISP=(NEW,CATLG,DELETE)"), directory);
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());

        DdTarget.DataSet target = (DdTarget.DataSet)
                parsed.job().steps().get(0).dd().get(0).target();
        assertEquals(Disposition.Status.NEW, target.disposition().status());
        assertEquals(Disposition.Action.CATLG, target.disposition().normal());
        assertEquals(Disposition.Action.DELETE, target.disposition().abnormal());
    }

    @Test
    @DisplayName("割当ての誤りは DD 名とデータセット名を名指しする (FR-133)")
    void theFailureNamesTheDd() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEFBR14",
                "//PAYIN    DD   DSN=NOSUCH.DAT,DISP=OLD");

        String failure = result.step("STEP1").failure();
        assertTrue(failure.contains("PAYIN"), failure);
        assertTrue(failure.contains("NOSUCH.DAT"), failure);
    }
}
