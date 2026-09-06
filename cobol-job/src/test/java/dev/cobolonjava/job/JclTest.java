package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.job.jcl.Jcl;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * JCL フロントエンド (要件 FR-131)。
 *
 * <p>既存の JCL 資産を<b>書き換えずに実行する</b>ためのものである。読み取った結果は
 * 内部ジョブモデルであり、宣言的形式で書いたものと区別がない。
 */
@Tag("V1")
class JclTest {

    private static final Path BASE = Path.of("data");

    private static Job job(String... cards) {
        Jcl.Result result = Jcl.read(String.join("\n", cards), BASE);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        return result.job();
    }

    private static String diagnostics(String... cards) {
        return Jcl.read(String.join("\n", cards), BASE).diagnostics().toString();
    }

    @Test
    @DisplayName("JOB と EXEC を読む (FR-131)")
    void jobAndExecAreRead() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT),'PAY RUN',CLASS=A",
                "//CHECK    EXEC PGM=PAYCHK",
                "//REPORT   EXEC PGM=PAYRPT");

        assertEquals("PAYROLL", job.name());
        assertEquals(2, job.steps().size());
        assertEquals("CHECK", job.steps().get(0).name());
        assertEquals("PAYCHK", job.steps().get(0).program());
    }

    @Test
    @DisplayName("注記カードは読み飛ばす (FR-131)")
    void commentCardsAreSkipped() {
        Job job = job(
                "//* 給与の計算",
                "//PAYROLL  JOB  (ACCT)",
                "//* ここから",
                "//CHECK    EXEC PGM=PAYCHK");

        assertEquals(1, job.steps().size());
    }

    @Test
    @DisplayName("73 桁目から先は見ない (FR-131)")
    void columnsBeyondSeventyTwoAreIgnored() {
        String padding = " ".repeat(72 - "//CHECK    EXEC PGM=PAYCHK".length());
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//CHECK    EXEC PGM=PAYCHK" + padding + "SEQ00010");

        assertEquals("PAYCHK", job.steps().get(0).program());
    }

    @Test
    @DisplayName("オペランドの外の空白から先は注記である (FR-131)")
    void textAfterTheOperandsIsAComment() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//CHECK    EXEC PGM=PAYCHK        RUN THE CHECKER");

        assertEquals("PAYCHK", job.steps().get(0).program());
    }

    @Test
    @DisplayName("コンマで終わるカードは次へ続く (FR-131)")
    void aCardEndingInACommaContinues() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//CHECK    EXEC PGM=PAYCHK,",
                "//             PARM='202609'");

        assertEquals("202609", job.steps().get(0).parm());
    }

    @Test
    @DisplayName("PARM の引用符を外す (FR-134)")
    void theParmIsUnquoted() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//CHECK    EXEC PGM=PAYCHK,PARM='A,B'");

        assertEquals("A,B", job.steps().get(0).parm());
    }

    @Test
    @DisplayName("DD の行き先を読み分ける (FR-133)")
    void ddTargetsAreRecognised() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//CHECK    EXEC PGM=PAYCHK",
                "//PAYIN    DD   DSN=PAY.MASTER,DISP=SHR",
                "//SYSOUT   DD   SYSOUT=*",
                "//SCRATCH  DD   DUMMY");

        Step step = job.steps().get(0);
        assertEquals(3, step.dd().size());
        DdTarget.DataSet dataSet =
                assertInstanceOf(DdTarget.DataSet.class, step.dd().get(0).target());
        assertEquals(BASE.resolve("PAY.MASTER"), dataSet.path());
        assertEquals(Disposition.Status.SHR, dataSet.disposition().status());
        assertInstanceOf(DdTarget.Sysout.class, step.dd().get(1).target());
        assertInstanceOf(DdTarget.Dummy.class, step.dd().get(2).target());
    }

    @Test
    @DisplayName("DISP の 1 つ目の副パラメタを読む (FR-131)")
    void theFirstDispositionIsRead() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//CHECK    EXEC PGM=PAYCHK",
                "//OUT      DD   DSN=PAY.OUT,DISP=(NEW,CATLG,DELETE)");

        assertEquals(Disposition.Status.NEW, assertInstanceOf(DdTarget.DataSet.class,
                job.steps().get(0).dd().get(0).target()).disposition().status());
    }

    @Test
    @DisplayName("DD * は次の文か /* までを埋め込みデータにする (FR-131)")
    void inlineDataRunsToTheDelimiter() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//CHECK    EXEC PGM=PAYCHK",
                "//SYSIN    DD   *",
                "DETAIL",
                "TOTAL",
                "/*",
                "//SYSOUT   DD   SYSOUT=*");

        Step step = job.steps().get(0);
        DdTarget.Inline inline =
                assertInstanceOf(DdTarget.Inline.class, step.dd().get(0).target());
        assertEquals("DETAIL\nTOTAL\n", CodePages.DEFAULT.decode(inline.data()));
        assertEquals(2, step.dd().size());
    }

    @Test
    @DisplayName("DD DATA は // で始まる行も取り込む (FR-131)")
    void inlineDataWithSlashesNeedsData() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//CHECK    EXEC PGM=PAYCHK",
                "//SYSIN    DD   DATA",
                "//NOT A CARD",
                "/*");

        DdTarget.Inline inline = assertInstanceOf(DdTarget.Inline.class,
                job.steps().get(0).dd().get(0).target());
        assertEquals("//NOT A CARD\n", CodePages.DEFAULT.decode(inline.data()));
    }

    @Test
    @DisplayName("名前欄の空いた DD は連結である (FR-131)")
    void aDdWithoutANameIsAConcatenation() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//CHECK    EXEC PGM=PAYCHK",
                "//PAYIN    DD   DSN=PAY.EAST,DISP=SHR",
                "//         DD   DSN=PAY.WEST,DISP=SHR");

        Step step = job.steps().get(0);
        assertEquals(1, step.dd().size());
        DdTarget.Concatenation joined =
                assertInstanceOf(DdTarget.Concatenation.class, step.dd().get(0).target());
        assertEquals(2, joined.parts().size());
    }

    @Test
    @DisplayName("COND の向きは裏返る (FR-136)")
    void condIsTurnedAround() {
        // COND=(4,LT,CHECK) は「4 < CHECK の RC なら飛ばす」である
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//CHECK    EXEC PGM=PAYCHK",
                "//REPORT   EXEC PGM=PAYRPT,COND=(4,LT,CHECK)");

        StepCondition.Not condition = assertInstanceOf(StepCondition.Not.class,
                job.steps().get(1).condition());
        StepCondition.ReturnCode test =
                assertInstanceOf(StepCondition.ReturnCode.class, condition.inner());
        assertEquals("CHECK", test.step());
        assertEquals(StepCondition.Comparison.GT, test.comparison());
        assertEquals(4, test.value());
    }

    @Test
    @DisplayName("COND の試験が複数あればどれかで飛ばす (FR-136)")
    void severalCondTestsBypassOnAny() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//A        EXEC PGM=P",
                "//B        EXEC PGM=Q,COND=((4,LT,A),(8,EQ))");

        StepCondition.Not condition = assertInstanceOf(StepCondition.Not.class,
                job.steps().get(1).condition());
        assertEquals(2, assertInstanceOf(StepCondition.Any.class, condition.inner())
                .parts().size());
    }

    @Test
    @DisplayName("COND=EVEN と COND=ONLY を読む (FR-136)")
    void evenAndOnlyAreRead() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//A        EXEC PGM=P",
                "//B        EXEC PGM=Q,COND=EVEN",
                "//C        EXEC PGM=R,COND=ONLY");

        assertInstanceOf(StepCondition.EvenIfAbend.class, job.steps().get(1).condition());
        assertInstanceOf(StepCondition.OnlyIfAbend.class, job.steps().get(2).condition());
    }

    @Test
    @DisplayName("COND に EVEN と試験を混ぜて書ける (FR-136)")
    void evenCombinesWithTests() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//A        EXEC PGM=P",
                "//B        EXEC PGM=Q,COND=((8,LE,A),EVEN)");

        StepCondition.All all =
                assertInstanceOf(StepCondition.All.class, job.steps().get(1).condition());
        assertTrue(all.survivesAbend());
        assertEquals(2, all.parts().size());
    }

    // ---- 知らない書き方は誤りにする ----

    @Test
    @DisplayName("知らない操作は誤りである (FR-131)")
    void unknownOperationsAreErrors() {
        assertTrue(diagnostics("//PAYROLL  JOB  (ACCT)", "//X        SETUP SOMETHING")
                .contains("unknown JCL operation"));
    }

    @Test
    @DisplayName("未対応の JCL 構文は読み飛ばさない (FR-131)")
    void unsupportedConstructsAreReported() {
        assertTrue(diagnostics("//PAYROLL  JOB  (ACCT)",
                "//         JCLLIB ORDER=(MY.PROCLIB)").contains("JCLLIB is not supported yet"));
        assertTrue(diagnostics("//PAYROLL  JOB  (ACCT)",
                "//         OUTPUT DEFAULT=YES").contains("OUTPUT is not supported yet"));
    }

    @Test
    @DisplayName("ない手続きを呼べば誤りである (FR-131)")
    void aMissingProcedureIsAnError() {
        assertTrue(diagnostics("//PAYROLL  JOB  (ACCT)", "//STEP1    EXEC MYPROC")
                .contains("no such procedure or member: MYPROC"));
    }

    @Test
    @DisplayName("装置と大きさの指定は未対応である (FR-131)")
    void unitAndSpaceAreNotSupported() {
        assertTrue(diagnostics(
                "//PAYROLL  JOB  (ACCT)",
                "//A        EXEC PGM=P",
                "//OUT      DD   DSN=X,UNIT=SYSDA,SPACE=(TRK,(1,1))")
                .contains("UNIT is not supported yet"));
    }

    @Test
    @DisplayName("JOB がなければ誤りである (FR-131)")
    void aJclNeedsAJobStatement() {
        assertTrue(diagnostics("//A        EXEC PGM=P").contains("EXEC comes after JOB"));
    }

    @Test
    @DisplayName("// で始まらない行は誤りである (FR-131)")
    void aCardMustStartWithSlashes() {
        assertTrue(diagnostics("//PAYROLL  JOB  (ACCT)", "EXEC PGM=P")
                .contains("starts with //"));
    }

    @Test
    @DisplayName("COND の書き方が違えば誤りである (FR-136)")
    void malformedCondIsAnError() {
        assertTrue(diagnostics("//J JOB", "//A EXEC PGM=P,COND=(4,XX,A)")
                .contains("unknown COND comparison"));
        assertTrue(diagnostics("//J JOB", "//A EXEC PGM=P,COND=(x,LT,A)")
                .contains("must be an integer"));
        assertTrue(diagnostics("//J JOB", "//A EXEC PGM=P,COND=(4)")
                .contains("malformed COND test"));
    }

    @Test
    @DisplayName("行き先のない DD は誤りである (FR-133)")
    void aDdNeedsATarget() {
        assertTrue(diagnostics("//J JOB", "//A EXEC PGM=P", "//X DD DCB=BLKSIZE")
                .contains("DCB is not supported yet"));
    }

    @Test
    @DisplayName("JCL と宣言的形式は同じ内部モデルへ落ちる (FR-130, FR-132)")
    void bothFrontendsProduceTheSameModel() {
        Job fromJcl = job(
                "//PAYROLL  JOB  (ACCT)",
                "//CHECK    EXEC PGM=PAYCHK",
                "//PAYIN    DD   DSN=pay.dat,DISP=SHR",
                "//SYSOUT   DD   SYSOUT=*",
                "//REPORT   EXEC PGM=PAYRPT,PARM='202609',COND=(4,LT,CHECK)",
                "//SYSOUT   DD   SYSOUT=*");

        JobScript.Result script = JobScript.read(String.join("\n", List.of(
                "JOB PAYROLL",
                "STEP CHECK PGM=PAYCHK",
                "  DD PAYIN DSN=data/pay.dat DISP=SHR",
                "  DD SYSOUT SYSOUT",
                "STEP REPORT PGM=PAYRPT PARM=202609",
                "  WHEN NOT RC CHECK > 4",
                "  DD SYSOUT SYSOUT")));
        assertTrue(script.succeeded(), () -> script.diagnostics().toString());

        assertEquals(script.job(), fromJcl);
    }
}
