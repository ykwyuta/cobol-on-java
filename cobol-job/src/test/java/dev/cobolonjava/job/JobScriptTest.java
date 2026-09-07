package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 宣言的形式のジョブ記述 (要件 FR-130, FR-132)。
 *
 * <p>内部ジョブモデルをそのまま書ける形式である。<b>知らない書き方は誤りにする</b>。
 * 読み飛ばすと、書いたつもりの指定が効いていないことに気付けない。
 */
@Tag("V1")
class JobScriptTest {

    private static Job job(String... lines) {
        JobScript.Result result = JobScript.read(String.join("\n", lines));
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        return result.job();
    }

    private static String diagnostics(String... lines) {
        return JobScript.read(String.join("\n", lines)).diagnostics().toString();
    }

    @Test
    @DisplayName("ジョブとステップを読む (FR-132)")
    void aJobHoldsItsSteps() {
        Job job = job(
                "JOB PAYROLL",
                "STEP EXTRACT PGM=PAYEXT",
                "STEP REPORT PGM=PAYRPT");

        assertEquals("PAYROLL", job.name());
        assertEquals(2, job.steps().size());
        assertEquals("EXTRACT", job.steps().get(0).name());
        assertEquals("PAYEXT", job.steps().get(0).program());
    }

    @Test
    @DisplayName("注記と空行は読み飛ばす (FR-132)")
    void commentsAndBlankLinesAreIgnored() {
        Job job = job(
                "# 給与の計算",
                "",
                "JOB PAYROLL",
                "  # ここから",
                "STEP EXTRACT PGM=PAYEXT");

        assertEquals(1, job.steps().size());
    }

    @Test
    @DisplayName("DD 割当の行き先を読み分ける (FR-133)")
    void ddTargetsAreRecognised() {
        Job job = job(
                "JOB J",
                "STEP S PGM=P",
                "  DD INFILE DSN=data/in.dat",
                "  DD SYSOUT SYSOUT",
                "  DD SCRATCH DUMMY");

        Step step = job.steps().get(0);
        assertEquals(3, step.dd().size());
        assertEquals(Path.of("data/in.dat"),
                assertInstanceOf(DdTarget.DataSet.class, step.dd().get(0).target()).path());
        assertInstanceOf(DdTarget.Sysout.class, step.dd().get(1).target());
        assertInstanceOf(DdTarget.Dummy.class, step.dd().get(2).target());
    }

    @Test
    @DisplayName("埋め込みデータは END までを読む (FR-131 の SYSIN 相当)")
    void inlineDataRunsToEnd() {
        Job job = job(
                "JOB J",
                "STEP S PGM=P",
                "  DD SYSIN DATA",
                "    DETAIL",
                "    TOTAL",
                "  END",
                "  DD SYSOUT SYSOUT");

        Step step = job.steps().get(0);
        DdTarget.Inline inline =
                assertInstanceOf(DdTarget.Inline.class, step.dd().get(0).target());
        assertEquals("DETAIL\nTOTAL\n", CodePages.DEFAULT.decode(inline.data()));
        assertEquals(2, step.dd().size());
    }

    @Test
    @DisplayName("PARM を読む (FR-134)")
    void theParmIsRead() {
        Job job = job("JOB J", "STEP S PGM=P PARM=202609");

        assertEquals("202609", job.steps().get(0).parm());
    }

    @Test
    @DisplayName("復帰コードの条件を読む (FR-136)")
    void returnCodeConditionsAreRead() {
        Job job = job(
                "JOB J",
                "STEP A PGM=P",
                "STEP B PGM=Q",
                "  WHEN RC A = 0");

        StepCondition.ReturnCode condition = assertInstanceOf(StepCondition.ReturnCode.class,
                job.steps().get(1).condition());
        assertEquals("A", condition.step());
        assertEquals(StepCondition.Comparison.EQ, condition.comparison());
        assertEquals(0, condition.value());
    }

    @Test
    @DisplayName("ステップを書かない条件はいちばん大きい復帰コードを見る (FR-136)")
    void aConditionWithoutAStepLooksAtTheHighest() {
        Job job = job("JOB J", "STEP A PGM=P", "  WHEN RC < 8");

        StepCondition.ReturnCode condition = assertInstanceOf(StepCondition.ReturnCode.class,
                job.steps().get(0).condition());
        assertEquals(null, condition.step());
        assertEquals(StepCondition.Comparison.LT, condition.comparison());
    }

    @Test
    @DisplayName("AND と OR と NOT を読む (FR-136)")
    void conditionsCombine() {
        Job job = job(
                "JOB J",
                "STEP A PGM=P",
                "STEP B PGM=Q",
                "STEP C PGM=R",
                "  WHEN RC A = 0 AND NOT RC B > 4");

        StepCondition.All all = assertInstanceOf(StepCondition.All.class,
                job.steps().get(2).condition());
        assertEquals(2, all.parts().size());
        assertInstanceOf(StepCondition.Not.class, all.parts().get(1));
    }

    @Test
    @DisplayName("異常終了の条件を読む (FR-136)")
    void abendConditionsAreRead() {
        Job job = job(
                "JOB J",
                "STEP A PGM=P",
                "STEP B PGM=Q",
                "  WHEN ABEND",
                "STEP C PGM=R",
                "  WHEN EVEN");

        assertInstanceOf(StepCondition.OnlyIfAbend.class, job.steps().get(1).condition());
        assertInstanceOf(StepCondition.EvenIfAbend.class, job.steps().get(2).condition());
        assertTrue(job.steps().get(2).condition().survivesAbend());
    }

    // ---- 誤りの検査 ----

    @Test
    @DisplayName("知らない語は誤りである (FR-131 と同じ方針)")
    void unknownKeywordsAreErrors() {
        assertTrue(diagnostics("JOB J", "SETUP something").contains("unknown keyword"));
    }

    @Test
    @DisplayName("JOB がなければ誤りである (FR-132)")
    void aDescriptionNeedsAJobLine() {
        assertTrue(diagnostics("STEP S PGM=P").contains("STEP comes after JOB"));
    }

    @Test
    @DisplayName("PGM がなければ誤りである (FR-132)")
    void aStepNeedsAProgram() {
        assertTrue(diagnostics("JOB J", "STEP S DISP=SHR").contains("STEP needs PGM="));
    }

    @Test
    @DisplayName("知らない DD の行き先は誤りである (FR-133)")
    void unknownDdTargetsAreErrors() {
        assertTrue(diagnostics("JOB J", "STEP S PGM=P", "  DD X TAPE")
                .contains("unknown DD target"));
    }

    @Test
    @DisplayName("AND と OR は混ぜられない (FR-136)")
    void andAndOrCannotBeMixed() {
        assertTrue(diagnostics("JOB J", "STEP A PGM=P", "  WHEN RC = 0 AND RC = 1 OR RC = 2")
                .contains("cannot be mixed"));
    }

    @Test
    @DisplayName("条件の書き方が違えば誤りである (FR-136)")
    void malformedConditionsAreErrors() {
        assertTrue(diagnostics("JOB J", "STEP A PGM=P", "  WHEN RC A ~ 0")
                .contains("unknown comparison"));
        assertTrue(diagnostics("JOB J", "STEP A PGM=P", "  WHEN RC A = x")
                .contains("must be an integer"));
        assertTrue(diagnostics("JOB J", "STEP A PGM=P", "  WHEN MAYBE")
                .contains("a condition starts with"));
    }

    @Test
    @DisplayName("閉じていない埋め込みデータは誤りである (FR-132)")
    void unclosedInlineDataIsAnError() {
        assertTrue(diagnostics("JOB J", "STEP S PGM=P", "  DD SYSIN DATA", "    ONE")
                .contains("not closed by END"));
    }

    @Test
    @DisplayName("1 つの記述に JOB は 1 つである (FR-132)")
    void oneDescriptionHoldsOneJob() {
        assertTrue(diagnostics("JOB A", "JOB B").contains("holds one JOB"));
    }

    // ---- SPACE (FR-141) ----

    @Test
    @DisplayName("SPACE はバイトで書く (FR-132, FR-141)")
    void spaceIsWrittenInBytes() {
        Job job = job(
                "JOB J",
                "STEP S PGM=P",
                "  DD OUT DSN=out.dat SPACE=8000");

        assertEquals(8000L, job.steps().get(0).dd().get(0).space());
    }

    @Test
    @DisplayName("DISP と SPACE はどちらの順でも書ける (FR-132, FR-141)")
    void modifiersComeInAnyOrder() {
        Job job = job(
                "JOB J",
                "STEP S PGM=P",
                "  DD A DSN=a.dat DISP=(NEW,CATLG) SPACE=100",
                "  DD B DSN=b.dat SPACE=100 DISP=(NEW,CATLG)");

        assertEquals(100L, job.steps().get(0).dd().get(0).space());
        assertEquals(100L, job.steps().get(0).dd().get(1).space());
        assertEquals(Disposition.Status.NEW, assertInstanceOf(DdTarget.DataSet.class,
                job.steps().get(0).dd().get(1).target()).disposition().status());
    }

    @Test
    @DisplayName("SPACE は数でなければ誤りである (FR-132, FR-141)")
    void spaceMustBeANumber() {
        assertTrue(diagnostics("JOB J", "STEP S PGM=P", "  DD OUT DSN=out.dat SPACE=TRK")
                .contains("SPACE takes a size in bytes"));
        assertTrue(diagnostics("JOB J", "STEP S PGM=P", "  DD OUT DSN=out.dat SPACE=0")
                .contains("SPACE takes a positive size"));
    }

    @Test
    @DisplayName("行き先を持たない DD に SPACE は書けない (FR-132, FR-141)")
    void aSysoutTakesNoSpace() {
        assertTrue(diagnostics("JOB J", "STEP S PGM=P", "  DD RPT SYSOUT SPACE=100")
                .contains("SPACE go with DSN="));
    }

    @Test
    @DisplayName("知らない修飾語は誤りである (FR-132)")
    void anUnknownModifierIsAnError() {
        assertTrue(diagnostics("JOB J", "STEP S PGM=P", "  DD OUT DSN=out.dat UNIT=SYSDA")
                .contains("DD does not support"));
    }
}
