package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 内部ジョブモデルの実行 (要件 FR-130, FR-133, FR-134, FR-136)。
 *
 * <p>解釈するのは内部モデルだけである。記述形式は知らない。ここで動かすプログラムは
 * 生成コードと同じ入口を手で実装したものであり、実行の側から見れば区別がない。
 */
@Tag("V1")
class JobRunnerTest {

    @TempDir
    Path directory;

    private ByteArrayOutputStream sink;

    private JobRunner runner() {
        sink = new ByteArrayOutputStream();
        return JobRunner.at(directory.resolve("work"),
                JobRunnerTest.class.getClassLoader(), sink);
    }

    private String output() {
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    private static Step step(String name, String program, String parm, StepCondition condition,
                             DdAssignment... dd) {
        return new Step(name, program, parm, List.of(dd), condition);
    }

    private static byte[] ebcdic(String text) {
        return CodePages.DEFAULT.encode(text);
    }

    private void write(String name, byte[] bytes) {
        try {
            Files.createDirectories(directory);
            Files.write(directory.resolve(name), bytes);
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

    @Test
    @DisplayName("復帰コードはステップごとに集まる (FR-136)")
    void returnCodesAreCollected() {
        JobRunner.Result result = runner().run(new Job("J", List.of(
                step("A", "SETRC", "4", new StepCondition.Always()),
                step("B", "SETRC", "0", new StepCondition.Always()))));

        assertEquals(4, result.returnCode());
        assertEquals(4, result.step("A").returnCode());
        assertEquals(0, result.step("B").returnCode());
        assertEquals(JobRunner.Status.EXECUTED, result.step("B").status());
    }

    @Test
    @DisplayName("復帰コードはステップをまたいで持ち越さない (FR-130)")
    void eachStepStartsWithAFreshReturnCode() {
        JobRunner.Result result = runner().run(new Job("J", List.of(
                step("A", "SETRC", "8", new StepCondition.Always()),
                // PARM を渡さなければ 0 のままである。前のステップの 8 は残らない
                step("B", "SETRC", null, new StepCondition.Always()))));

        assertEquals(0, result.step("B").returnCode());
        assertEquals(8, result.returnCode());
    }

    @Test
    @DisplayName("条件が成り立たなければステップを飛ばす (FR-136)")
    void aStepIsBypassedWhenItsConditionFails() {
        JobRunner.Result result = runner().run(new Job("J", List.of(
                step("A", "SETRC", "4", new StepCondition.Always()),
                step("B", "SETRC", "0", new StepCondition.ReturnCode("A",
                        StepCondition.Comparison.EQ, 0)),
                step("C", "SETRC", "0", new StepCondition.ReturnCode("A",
                        StepCondition.Comparison.LE, 4)))));

        assertEquals(JobRunner.Status.BYPASSED, result.step("B").status());
        assertEquals(JobRunner.Status.EXECUTED, result.step("C").status());
    }

    @Test
    @DisplayName("飛ばされたステップの復帰コードは比べられない (FR-136)")
    void aBypassedStepHasNoReturnCode() {
        JobRunner.Result result = runner().run(new Job("J", List.of(
                step("A", "SETRC", "4", new StepCondition.Always()),
                step("B", "SETRC", "0", new StepCondition.ReturnCode("A",
                        StepCondition.Comparison.EQ, 0)),
                // B は動いていない。動いていないステップの復帰コードは比べられない
                step("C", "SETRC", "0", new StepCondition.ReturnCode("B",
                        StepCondition.Comparison.EQ, 0)))));

        assertEquals(JobRunner.Status.BYPASSED, result.step("C").status());
    }

    @Test
    @DisplayName("異常終了すれば以降のステップは飛ばされる (FR-136)")
    void anAbendBypassesTheRest() {
        JobRunner.Result result = runner().run(new Job("J", List.of(
                step("A", "BOOM", null, new StepCondition.Always()),
                step("B", "SETRC", "0", new StepCondition.Always()))));

        assertEquals(JobRunner.Status.ABENDED, result.step("A").status());
        assertEquals("data exception", result.step("A").failure());
        assertEquals(JobRunner.Status.BYPASSED, result.step("B").status());
    }

    @Test
    @DisplayName("EVEN と ONLY は異常終了を越える (FR-136)")
    void evenAndOnlySurviveAnAbend() {
        JobRunner.Result result = runner().run(new Job("J", List.of(
                step("A", "BOOM", null, new StepCondition.Always()),
                step("B", "SETRC", "0", new StepCondition.EvenIfAbend()),
                step("C", "SETRC", "0", new StepCondition.OnlyIfAbend()))));

        assertEquals(JobRunner.Status.EXECUTED, result.step("B").status());
        assertEquals(JobRunner.Status.EXECUTED, result.step("C").status());
    }

    @Test
    @DisplayName("ONLY は異常終了していなければ動かない (FR-136)")
    void onlyNeedsAnAbend() {
        JobRunner.Result result = runner().run(new Job("J", List.of(
                step("A", "SETRC", "0", new StepCondition.Always()),
                step("B", "SETRC", "0", new StepCondition.OnlyIfAbend()))));

        assertEquals(JobRunner.Status.BYPASSED, result.step("B").status());
    }

    @Test
    @DisplayName("ロードモジュールが見つからなければ異常終了である (FR-141)")
    void aMissingProgramIsAnAbend() {
        JobRunner.Result result = runner().run(new Job("J", List.of(
                step("A", "NOSUCHPG", null, new StepCondition.Always()))));

        assertEquals(JobRunner.Status.ABENDED, result.step("A").status());
        assertNotNull(result.step("A").failure());
    }

    @Test
    @DisplayName("DD 割当がプログラムの入出力へ届く (FR-133)")
    void ddAssignmentsReachTheProgram() {
        write("in.dat", ebcdic("AAAAAAAAAAAAAAAAAAAABBBBBBBBBBBBBBBBBBBB"));
        write("in.dat.meta", "recfm=F\nlrecl=20\ncodepage=IBM-1047\n"
                .getBytes(StandardCharsets.UTF_8));

        JobRunner.Result result = runner().run(new Job("J", List.of(
                step("COPY", "COPYDD", null, new StepCondition.Always(),
                        new DdAssignment("INDD",
                                new DdTarget.DataSet(directory.resolve("in.dat"))),
                        new DdAssignment("OUTDD",
                                new DdTarget.DataSet(directory.resolve("out.dat")))))));

        assertEquals(JobRunner.Status.EXECUTED, result.step("COPY").status());
        assertEquals("AAAAAAAAAAAAAAAAAAAABBBBBBBBBBBBBBBBBBBB",
                CodePages.DEFAULT.decode(bytesOf("out.dat")));
    }

    @Test
    @DisplayName("DUMMY は読めば即座に終わる (FR-133)")
    void aDummyInputIsEmpty() {
        JobRunner.Result result = runner().run(new Job("J", List.of(
                step("COPY", "COPYDD", null, new StepCondition.Always(),
                        new DdAssignment("INDD", new DdTarget.Dummy()),
                        new DdAssignment("OUTDD",
                                new DdTarget.DataSet(directory.resolve("out.dat")))))));

        assertEquals(JobRunner.Status.EXECUTED, result.step("COPY").status());
        assertEquals(0, bytesOf("out.dat").length);
    }

    @Test
    @DisplayName("埋め込みデータを読み、SYSOUT がジョブの出力へ流れる (FR-132, FR-135)")
    void inlineDataFlowsThroughTheSpool() {
        JobRunner.Result result = runner().run(new Job("J", List.of(
                step("LOG", "WRITELOG", null, new StepCondition.Always(),
                        new DdAssignment("SYSIN",
                                new DdTarget.Inline(ebcdic("DETAIL\nTOTAL\n"))),
                        new DdAssignment("REPORT", new DdTarget.Sysout())))));

        assertEquals(JobRunner.Status.EXECUTED, result.step("LOG").status());
        assertEquals("DETAIL|TOTAL|", output());
    }

    @Test
    @DisplayName("PARM は先頭 2 バイト長で渡る (FR-134)")
    void theParmArrivesWithItsLength() {
        runner().run(new Job("J", List.of(
                step("SHOW", "SAYPARM", "202609", new StepCondition.Always()))));

        assertEquals("PARM(6)=202609|", output());
    }

    @Test
    @DisplayName("PARM がなければ引数も渡らない (FR-134)")
    void withoutAParmThereIsNoArgument() {
        runner().run(new Job("J", List.of(
                step("SHOW", "SAYPARM", null, new StepCondition.Always()))));

        assertEquals("NO PARM|", output());
    }

    @Test
    @DisplayName("ジョブの終了コードはいちばん大きい復帰コードである (FR-136)")
    void theJobEndsWithTheHighestReturnCode() {
        JobRunner.Result result = runner().run(new Job("J", List.of(
                step("A", "SETRC", "0", new StepCondition.Always()),
                step("B", "SETRC", "12", new StepCondition.Always()),
                step("C", "SETRC", "4", new StepCondition.Always()))));

        assertEquals(12, result.returnCode());
    }

    @Test
    @DisplayName("宣言的形式で書いたジョブがそのまま動く (FR-130, FR-132)")
    void aDescribedJobRunsAsWritten() {
        JobScript.Result script = JobScript.read(String.join("\n", List.of(
                "JOB PAYROLL",
                "STEP FIRST PGM=SETRC PARM=4",
                "STEP SECOND PGM=SAYPARM PARM=OK",
                "  WHEN RC FIRST <= 4",
                "STEP THIRD PGM=SAYPARM PARM=NEVER",
                "  WHEN RC FIRST = 0")));
        assertTrue(script.succeeded(), () -> script.diagnostics().toString());

        JobRunner.Result result = runner().run(script.job());

        assertEquals("PARM(2)=OK|", output());
        assertEquals(JobRunner.Status.BYPASSED, result.step("THIRD").status());
        assertEquals(4, result.returnCode());
    }
}
