package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.abend.AbendCode;
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
 * 異常終了コードがジョブへ伝わる (要件 FR-141)。
 *
 * <p>異常終了は「起きたかどうか」だけでなく<b>何が起きたか</b>で分かれる。データの誤りなら
 * 入力を直して流し直せばよく、ロードモジュールが無いなら組み立てから直す。後始末のしかたも
 * 違う。JCL の {@code ABENDCC} はそれを見分けるためにある。
 */
@Tag("V1")
class AbendTest {

    @TempDir
    Path directory;

    private ByteArrayOutputStream sink;

    private JobRunner.Result run(String... cards) {
        sink = new ByteArrayOutputStream();
        dev.cobolonjava.job.jcl.Jcl.Result parsed = dev.cobolonjava.job.jcl.Jcl.read(
                String.join("\n", cards));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        return JobRunner.at(directory.resolve("work"), AbendTest.class.getClassLoader(), sink)
                .withBase(directory)
                .run(parsed.job());
    }

    private JobRunner.Result runScript(String... lines) {
        JobScript.Result parsed = JobScript.read(String.join("\n", lines));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        return JobRunner.at(directory.resolve("work"), AbendTest.class.getClassLoader(),
                        new ByteArrayOutputStream())
                .withBase(directory)
                .run(parsed.job());
    }

    private String output() {
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    private String read(String name) {
        try {
            return CodePages.DEFAULT.decode(Files.readAllBytes(directory.resolve(name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- コードが立つ ----

    @Test
    @DisplayName("数値項目の不正データは S0C7 として伝わる (FR-141)")
    void badNumericDataIsS0c7() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BADDATA");

        assertEquals(JobRunner.Status.ABENDED, result.step("STEP1").status());
        assertEquals(AbendCode.S0C7, result.step("STEP1").abendCode());
        assertEquals(AbendCode.S0C7, result.state().abendCode("STEP1"));
    }

    @Test
    @DisplayName("ロードモジュールが見つからなければ S806 (FR-141)")
    void aMissingModuleIsS806() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=NOSUCHPG");

        assertEquals(JobRunner.Status.ABENDED, result.step("STEP1").status());
        assertEquals(AbendCode.S806, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("渡されていない連絡節の項目を触れば S0C4 (FR-141)")
    void anUnsuppliedArgumentIsS0c4() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=NOARG");

        assertEquals(AbendCode.S0C4, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("PARM を渡せば連絡節は触れる (FR-134, FR-141)")
    void aSuppliedArgumentIsFine() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=NOARG,PARM='202609'");

        assertEquals(JobRunner.Status.EXECUTED, result.step("STEP1").status());
    }

    @Test
    @DisplayName("コードを名乗らない誤りでも異常終了ではある (FR-141)")
    void anUnknownFailureStillAbends() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BOOM");

        assertEquals(JobRunner.Status.ABENDED, result.step("STEP1").status());
        assertNull(result.step("STEP1").abendCode());
        assertTrue(result.state().abended());
    }

    // ---- ABENDCC で見分ける ----

    @Test
    @DisplayName("IF ABENDCC = コード で後始末を分けられる (FR-131, FR-141)")
    void abendCodesSelectTheCleanUp() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BADDATA",
                "//         IF (STEP1.ABENDCC = S0C7) THEN",
                "//FIXDATA  EXEC PGM=IEFBR14",
                "//         ENDIF",
                "//         IF (STEP1.ABENDCC = S806) THEN",
                "//REBUILD  EXEC PGM=IEFBR14",
                "//         ENDIF");

        assertEquals(JobRunner.Status.EXECUTED, result.step("FIXDATA").status());
        assertEquals(JobRunner.Status.BYPASSED, result.step("REBUILD").status());
    }

    @Test
    @DisplayName("ステップを言わない ABENDCC は最後に分かったコードを見る (FR-141)")
    void abendCodeWithoutAStepUsesTheLastOne() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BADDATA",
                "//         IF (ABENDCC = S0C7) THEN",
                "//CLEANUP  EXEC PGM=IEFBR14",
                "//         ENDIF");

        assertEquals(JobRunner.Status.EXECUTED, result.step("CLEANUP").status());
    }

    @Test
    @DisplayName("等しくないほうも書ける (FR-141)")
    void abendCodesCanBeCompared() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BADDATA",
                "//         IF (STEP1.ABENDCC ¬= S806) THEN",
                "//OTHER    EXEC PGM=IEFBR14",
                "//         ENDIF");

        assertEquals(JobRunner.Status.EXECUTED, result.step("OTHER").status());
    }

    @Test
    @DisplayName("異常終了していなければ ABENDCC は成り立たない (FR-141)")
    void abendCodesDoNotMatchWhenNothingAbended() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEFBR14",
                "//         IF (STEP1.ABENDCC = S0C7) THEN",
                "//CLEANUP  EXEC PGM=IEFBR14",
                "//         ENDIF");

        assertEquals(JobRunner.Status.EXECUTED, result.step("STEP1").status());
        assertEquals(JobRunner.Status.BYPASSED, result.step("CLEANUP").status());
    }

    @Test
    @DisplayName("宣言的形式でも ABENDCC を書ける (FR-132, FR-141)")
    void theDeclarativeFormatCanSayIt() {
        JobRunner.Result result = runScript(
                "JOB J",
                "STEP STEP1 PGM=BADDATA",
                "STEP FIXDATA PGM=IEFBR14",
                "  WHEN ABENDCC STEP1 = S0C7",
                "STEP REBUILD PGM=IEFBR14",
                "  WHEN ABENDCC STEP1 = S806");

        assertEquals(JobRunner.Status.EXECUTED, result.step("FIXDATA").status());
        assertEquals(JobRunner.Status.BYPASSED, result.step("REBUILD").status());
    }

    @Test
    @DisplayName("宣言的形式でも知らないコードは誤りである (FR-132, FR-141)")
    void theDeclarativeFormatReportsAnUnknownCode() {
        JobScript.Result parsed = JobScript.read(String.join("\n",
                "JOB J",
                "STEP STEP1 PGM=P",
                "  WHEN ABENDCC = S9Z9"));

        assertFalse(parsed.succeeded());
        assertTrue(parsed.diagnostics().toString().contains("unknown abend code: S9Z9"),
                parsed.diagnostics().toString());
    }

    // ---- 覚え書き ----

    @Test
    @DisplayName("受け止め手のないファイルの異常は U4038 (FR-104, FR-141)")
    void anUnhandledFileConditionIsU4038() {
        // COPYDD は FILE STATUS も宣言部分も書いていない。入力が無ければそこで打ち切る
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG)");

        assertEquals(JobRunner.Status.ABENDED, result.step("STEP1").status());
        assertEquals(AbendCode.U4038, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("形の壊れたデータセットを読むのは S001 (FR-141)")
    void aDamagedDataSetIsS001() {
        // 20 バイト区切りのはずが 25 バイトある。1 件は読めるが、その先は切り出せない
        dataSet("IN.DAT", 25, 20);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IOFAIL",
                "//INDD     DD   DSN=IN.DAT,DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG)");

        assertEquals(JobRunner.Status.ABENDED, result.step("STEP1").status());
        assertEquals(AbendCode.S001, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("二次割当の無い領域を使い切って書けなくなるのは SD37 (FR-141、P-052)")
    void runningOutOfSpaceIsSd37() {
        dataSet("IN.DAT", 60, 20);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IOFAIL",
                "//INDD     DD   DSN=IN.DAT,DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG),",
                "//              SPACE=(20,(2))");

        assertEquals(JobRunner.Status.ABENDED, result.step("STEP1").status());
        assertEquals(AbendCode.SD37, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("二次割当も使い切れば SB37。16 エクステント (一次 1、二次 15) が限り (P-052)")
    void runningOutOfSecondarySpaceIsSb37() {
        // 20 バイトのレコード 17 件。一次 1 件 + 二次 1 件 x 15 回 = 16 件で止まる
        dataSet("IN.DAT", 340, 20);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IOFAIL",
                "//INDD     DD   DSN=IN.DAT,DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG),",
                "//              SPACE=(20,(1,1))");

        assertEquals(JobRunner.Status.ABENDED, result.step("STEP1").status());
        assertEquals(AbendCode.SB37, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("16 エクステントに収まれば、二次割当で伸びて止まらない (P-052)")
    void secondarySpaceExtendsTheDataSet() {
        dataSet("IN.DAT", 320, 20);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IOFAIL",
                "//INDD     DD   DSN=IN.DAT,DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG),",
                "//              SPACE=(20,(1,1))");

        assertEquals(JobRunner.Status.EXECUTED, result.step("STEP1").status());
    }

    @Test
    @DisplayName("異常終了しても、そこまでに書いたレコードは残る (FR-141)")
    void whatWasWrittenBeforeTheAbendSurvives() {
        dataSet("IN.DAT", 60, 20);

        run("//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IOFAIL",
                "//INDD     DD   DSN=IN.DAT,DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG,CATLG),",
                "//              SPACE=(20,(2))");

        // 閉じずに終わっても置き場へ届いている。何が起きたのかを残ったもので調べられる
        assertEquals(40, size("OUT.DAT"));
    }

    private long size(String name) {
        try {
            return Files.size(directory.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("二次割当があれば使い切っても伸ばせる (FR-141)")
    void aSecondaryAllocationExtends() {
        dataSet("IN.DAT", 60, 20);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IOFAIL",
                "//INDD     DD   DSN=IN.DAT,DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG),",
                "//              SPACE=(20,(2,1))");

        assertEquals(JobRunner.Status.EXECUTED, result.step("STEP1").status());
    }

    /** 決まった大きさのデータセットを置く。中身は空白であり、形だけが問題である。 */
    private void dataSet(String name, int size, int recordLength) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, CodePages.DEFAULT.space());
        try {
            Files.write(directory.resolve(name), bytes);
            Files.writeString(directory.resolve(name + ".meta"),
                    "recfm=F\nlrecl=" + recordLength + "\ncodepage=IBM-1047\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("異常終了はジョブログにコードごと出る (FR-141, FR-142)")
    void theJobLogNamesTheCode() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BADDATA");

        assertEquals(AbendCode.S0C7, result.step("STEP1").abendCode());
        assertEquals("data exception", result.step("STEP1").abendCode().reason());
    }

    @Test
    @DisplayName("異常終了したジョブは 0 を返さない (FR-136, FR-141)")
    void anAbendedJobDoesNotReportSuccess() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BADDATA",
                "//STEP2    EXEC PGM=SETRC,PARM='0',COND=EVEN");

        // 復帰コードは立たない。そのまま返すと 0 になり、通ったと読み違える
        assertEquals(JobRunner.Status.EXECUTED, result.step("STEP2").status());
        assertEquals(0, result.state().highest());
        assertEquals(12, result.returnCode());
        assertTrue(result.abended());
        assertFalse(result.failed());
    }

    @Test
    @DisplayName("異常終了したステップの復帰コードは無い (FR-136, FR-141)")
    void anAbendedStepHasNoReturnCode() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BADDATA");

        assertEquals(-1, result.step("STEP1").returnCode());
    }

    // ---- 診断出力 (FR-142, FR-143) ----

    @Test
    @DisplayName("異常終了すれば診断出力が出る (FR-142)")
    void anAbendWritesADiagnosis() {
        run("//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BADDATA");

        String written = output();
        assertTrue(written.contains("ABEND S0C7 WAS ISSUED"), written);
        assertTrue(written.contains("TRACEBACK"), written);
        assertTrue(written.contains("BADDATA"), written);
    }

    @Test
    @DisplayName("CEEDUMP を書けばそこへ出る (FR-142)")
    void theDiagnosisGoesToCeedump() {
        run("//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BADDATA",
                "//CEEDUMP  DD   DSN=DUMP.TXT,DISP=(NEW,CATLG)");

        String dump = read("DUMP.TXT");
        assertTrue(dump.contains("ABEND S0C7 WAS ISSUED"), dump);
        // 行き先を書いたのだから、ジョブの出力へは回らない
        assertFalse(output().contains("CEE3250C"), output());
    }

    @Test
    @DisplayName("TERMTHDACT(QUIET) は何も出さない (FR-143)")
    void quietWritesNothing() {
        run("//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BADDATA",
                "//CEEOPTS  DD   *",
                "  TERMTHDACT(QUIET)");

        assertFalse(output().contains("CEE3250C"), output());
    }

    @Test
    @DisplayName("TERMTHDACT(MSG) は覚え書きだけを出す (FR-143)")
    void msgWritesOnlyTheMessage() {
        run("//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BADDATA",
                "//CEEOPTS  DD   *",
                "  TERMTHDACT(MSG)");

        assertTrue(output().contains("ABEND S0C7 WAS ISSUED"), output());
        assertFalse(output().contains("TRACEBACK"), output());
    }

    @Test
    @DisplayName("TERMTHDACT(DUMP) は記憶域の中身まで出す (FR-143)")
    void dumpWritesTheStorage() {
        run("//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=NOARG",
                "//CEEOPTS  DD   *",
                "  TERMTHDACT(DUMP)");

        assertTrue(output().contains("TRACEBACK"), output());
        assertTrue(output().contains("STORAGE FOR"), output());
    }

    @Test
    @DisplayName("知らない TERMTHDACT の綴りは既定のままにする (FR-143)")
    void anUnknownLevelKeepsTheDefault() {
        run("//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=BADDATA",
                "//CEEOPTS  DD   *",
                "  TERMTHDACT(LOUD)");

        assertTrue(output().contains("TRACEBACK"), output());
    }

    @Test
    @DisplayName("正常に終われば診断出力は出ない (FR-142)")
    void nothingIsWrittenWhenNothingFails() {
        run("//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEFBR14");

        assertFalse(output().contains("CEE3250C"), output());
    }
}
