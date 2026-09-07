package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.job.jcl.Jcl;
import dev.cobolonjava.job.jcl.JclLibrary;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 目録手続きとシンボリックパラメタ (要件 FR-131)。
 *
 * <p>展開はモデルを組む前に済ませる。抜けたカードには<b>手続きの呼び出しも
 * シンボリックパラメタも残っていない</b>。組み立てる側は手続きを知らずに済む。
 */
@Tag("V1")
class JclProcedureTest {

    private static final JclLibrary LIBRARY = JclLibrary.of(Map.of(
            "COPYPROC", String.join("\n", List.of(
                    "//COPYPROC PROC MEMBER=DEFAULT",
                    "//COPY     EXEC PGM=IEBGENER",
                    "//SYSUT1   DD   DSN=&MEMBER,DISP=SHR",
                    "//SYSUT2   DD   SYSOUT=*")),
            "TWOSTEP", String.join("\n", List.of(
                    "//TWOSTEP  PROC",
                    "//FIRST    EXEC PGM=PGMA",
                    "//OUT      DD   SYSOUT=*",
                    "//SECOND   EXEC PGM=PGMB",
                    "//OUT      DD   SYSOUT=*")),
            "CONDPROC", String.join("\n", List.of(
                    "//CONDPROC PROC",
                    "//FIRST    EXEC PGM=PGMA",
                    "//SECOND   EXEC PGM=PGMB,COND=(4,LT,FIRST)")),
            "COMMONDD", String.join("\n", List.of(
                    "//SYSPRINT DD   SYSOUT=*",
                    "//SYSUDUMP DD   DUMMY"))));

    private static Job job(String... cards) {
        Jcl.Result result = Jcl.read(String.join("\n", cards), LIBRARY);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        return result.job();
    }

    private static String diagnostics(String... cards) {
        return Jcl.read(String.join("\n", cards), LIBRARY).diagnostics().toString();
    }

    @Test
    @DisplayName("目録手続きを展開する (FR-131)")
    void aCatalogedProcedureIsExpanded() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//STEP1    EXEC COPYPROC,MEMBER=PAY.IN");

        assertEquals(1, job.steps().size());
        Step step = job.steps().get(0);
        assertEquals("STEP1", step.name());
        assertEquals("IEBGENER", step.program());
        assertEquals("PAY.IN",
                assertInstanceOf(DdTarget.DataSet.class, step.dd().get(0).target()).name());
    }

    @Test
    @DisplayName("PROC= と書いても同じである (FR-131)")
    void theProcKeywordIsEquivalent() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//STEP1    EXEC PROC=COPYPROC,MEMBER=PAY.IN");

        assertEquals("IEBGENER", job.steps().get(0).program());
    }

    @Test
    @DisplayName("書かなければ手続きの既定値が効く (FR-131)")
    void theProcedureDefaultApplies() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//STEP1    EXEC COPYPROC");

        assertEquals("DEFAULT", assertInstanceOf(DdTarget.DataSet.class,
                job.steps().get(0).dd().get(0).target()).name());
    }

    @Test
    @DisplayName("ジョブの中に書いた手続きも呼べる (FR-131)")
    void anInStreamProcedureIsCalled() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//MYPROC   PROC CLASS=A",
                "//RUN      EXEC PGM=MYPGM",
                "//OUT      DD   SYSOUT=&CLASS",
                "//MYPROC   PEND",
                "//STEP1    EXEC MYPROC");

        assertEquals(1, job.steps().size());
        assertEquals("MYPGM", job.steps().get(0).program());
        assertInstanceOf(DdTarget.Sysout.class, job.steps().get(0).dd().get(0).target());
    }

    @Test
    @DisplayName("複数ステップの手続きは 呼び出し名.中の名前 になる (FR-131)")
    void aMultiStepProcedureQualifiesItsSteps() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//STEP1    EXEC TWOSTEP");

        assertEquals(2, job.steps().size());
        assertEquals("STEP1.FIRST", job.steps().get(0).name());
        assertEquals("STEP1.SECOND", job.steps().get(1).name());
        assertEquals("PGMA", job.steps().get(0).program());
    }

    @Test
    @DisplayName("SET で置いた値が効く (FR-131)")
    void setValuesApply() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//         SET  MEMBER=SET.VALUE",
                "//STEP1    EXEC COPYPROC");

        assertEquals("SET.VALUE", assertInstanceOf(DdTarget.DataSet.class,
                job.steps().get(0).dd().get(0).target()).name());
    }

    @Test
    @DisplayName("呼び出しで書いた値は SET より強い (FR-131)")
    void anOverrideBeatsSet() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//         SET  MEMBER=SET.VALUE",
                "//STEP1    EXEC COPYPROC,MEMBER=CALL.VALUE");

        assertEquals("CALL.VALUE", assertInstanceOf(DdTarget.DataSet.class,
                job.steps().get(0).dd().get(0).target()).name());
    }

    @Test
    @DisplayName("シンボリックは点で終わりを示せる (FR-131)")
    void aTrailingDotEndsTheSymbol() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//         SET  PREFIX=PAY",
                "//STEP1    EXEC PGM=P",
                "//IN       DD   DSN=&PREFIX..MASTER,DISP=SHR");

        assertEquals("PAY.MASTER", assertInstanceOf(DdTarget.DataSet.class,
                job.steps().get(0).dd().get(0).target()).name());
    }

    @Test
    @DisplayName("&& はシンボリックではなく一時データセットになる (FR-131, FR-133)")
    void doubledAmpersandsMakeATemporaryDataSet() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//STEP1    EXEC PGM=P",
                "//WORK     DD   DSN=&&TEMP,DISP=NEW");

        // && は展開の段で 1 つの & になり、先頭の & が一時データセットを表す
        assertEquals("TEMP", assertInstanceOf(DdTarget.Temporary.class,
                job.steps().get(0).dd().get(0).target()).name());
    }

    @Test
    @DisplayName("DD の上書きは同じ名前を差し替える (FR-131)")
    void anOverrideReplacesTheDd() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//STEP1    EXEC COPYPROC,MEMBER=PAY.IN",
                "//COPY.SYSUT2 DD DSN=PAY.OUT,DISP=SHR");

        Step step = job.steps().get(0);
        assertEquals(2, step.dd().size());
        assertEquals("SYSUT2", step.dd().get(1).name());
        assertEquals("PAY.OUT",
                assertInstanceOf(DdTarget.DataSet.class, step.dd().get(1).target()).name());
    }

    @Test
    @DisplayName("ない名前の上書きは足される (FR-131)")
    void anOverrideForANewNameIsAdded() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//STEP1    EXEC COPYPROC,MEMBER=PAY.IN",
                "//COPY.SYSIN  DD *",
                "GENERATE",
                "/*");

        Step step = job.steps().get(0);
        assertEquals(3, step.dd().size());
        assertEquals("SYSIN", step.dd().get(2).name());
        assertEquals("GENERATE\n", CodePages.DEFAULT.decode(
                assertInstanceOf(DdTarget.Inline.class, step.dd().get(2).target()).data()));
    }

    @Test
    @DisplayName("上書きは名指したステップだけに当たる (FR-131)")
    void anOverrideAppliesToTheNamedStep() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//STEP1    EXEC TWOSTEP",
                "//SECOND.OUT  DD DSN=ONLY.SECOND,DISP=SHR");

        assertInstanceOf(DdTarget.Sysout.class, job.steps().get(0).dd().get(0).target());
        assertEquals("ONLY.SECOND", assertInstanceOf(DdTarget.DataSet.class,
                job.steps().get(1).dd().get(0).target()).name());
    }

    @Test
    @DisplayName("INCLUDE はメンバの本文を差し込む (FR-131)")
    void includeSplicesItsMember() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//STEP1    EXEC PGM=MYPGM",
                "//         INCLUDE MEMBER=COMMONDD",
                "//OUT      DD   SYSOUT=*");

        Step step = job.steps().get(0);
        assertEquals(3, step.dd().size());
        assertEquals("SYSPRINT", step.dd().get(0).name());
        assertEquals("SYSUDUMP", step.dd().get(1).name());
        assertEquals("OUT", step.dd().get(2).name());
    }

    // ---- 誤りの検査 ----

    @Test
    @DisplayName("値のないシンボリックは誤りである (FR-131)")
    void anUnresolvedSymbolIsAnError() {
        assertTrue(diagnostics(
                "//PAYROLL  JOB  (ACCT)",
                "//STEP1    EXEC PGM=P",
                "//IN       DD   DSN=&NOSUCH,DISP=SHR")
                .contains("no value for symbolic parameter: &NOSUCH"));
    }

    @Test
    @DisplayName("PEND で閉じない手続きは誤りである (FR-131)")
    void anUnclosedProcedureIsAnError() {
        assertTrue(diagnostics(
                "//PAYROLL  JOB  (ACCT)",
                "//MYPROC   PROC",
                "//RUN      EXEC PGM=MYPGM")
                .contains("is not closed by PEND"));
    }

    @Test
    @DisplayName("手続きのない PEND は誤りである (FR-131)")
    void aStrayPendIsAnError() {
        assertTrue(diagnostics("//PAYROLL  JOB  (ACCT)", "//         PEND")
                .contains("PEND without a matching PROC"));
    }

    @Test
    @DisplayName("上書きの DD には 手続きのステップ名. が要る (FR-131)")
    void anOverrideNeedsItsQualifier() {
        assertTrue(diagnostics(
                "//PAYROLL  JOB  (ACCT)",
                "//STEP1    EXEC COPYPROC,MEMBER=X",
                "//SYSUT2   DD   SYSOUT=*")
                .contains("needs procstep.ddname"));
    }

    @Test
    @DisplayName("ないメンバの INCLUDE は誤りである (FR-131)")
    void aMissingIncludeIsAnError() {
        assertTrue(diagnostics("//PAYROLL  JOB  (ACCT)", "//         INCLUDE MEMBER=NOSUCH")
                .contains("no such procedure or member: NOSUCH"));
    }

    @Test
    @DisplayName("EXEC のない手続きは誤りである (FR-131)")
    void aProcedureNeedsAnExec() {
        assertTrue(diagnostics(
                "//PAYROLL  JOB  (ACCT)",
                "//EMPTY    PROC",
                "//EMPTY    PEND",
                "//STEP1    EXEC EMPTY")
                .contains("has no EXEC statement"));
    }

    @Test
    @DisplayName("手続きの中の COND は付け替えたあとの名前を指す (FR-131, FR-136)")
    void condInsideAProcedureFollowsTheRenaming() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//RUN      EXEC CONDPROC");

        StepCondition.Not condition = assertInstanceOf(StepCondition.Not.class,
                job.steps().get(1).condition());
        StepCondition.ReturnCode test =
                assertInstanceOf(StepCondition.ReturnCode.class, condition.inner());
        assertEquals("RUN.FIRST", test.step());
    }

    @Test
    @DisplayName("呼び出しに付けた COND は手続きの全ステップに効く (FR-131, FR-136)")
    void anOuterCondReachesEveryStep() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//SETUP    EXEC PGM=P",
                "//RUN      EXEC TWOSTEP,COND=(4,LT,SETUP)");

        for (int i = 1; i <= 2; i++) {
            StepCondition.Not condition = assertInstanceOf(StepCondition.Not.class,
                    job.steps().get(i).condition());
            assertEquals("SETUP", assertInstanceOf(StepCondition.ReturnCode.class,
                    condition.inner()).step());
        }
    }

    @Test
    @DisplayName("手続きの中の COND が呼び出しの COND より優先する (FR-131)")
    void anInnerCondWins() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//RUN      EXEC CONDPROC,COND=(0,LT)");

        StepCondition.Not condition = assertInstanceOf(StepCondition.Not.class,
                job.steps().get(1).condition());
        assertEquals("RUN.FIRST", assertInstanceOf(StepCondition.ReturnCode.class,
                condition.inner()).step());
    }
}
