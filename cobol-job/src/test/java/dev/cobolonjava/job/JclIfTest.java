package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.job.jcl.Jcl;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * JCL の {@code IF} / {@code THEN} / {@code ELSE} / {@code ENDIF} (要件 FR-131, FR-136)。
 *
 * <p>{@code COND} と違って<b>「真なら動かす」</b>の向きで書く。内部モデルの向きと同じなので
 * 裏返さない。囲まれたステップの条件は、囲んでいる条件と自分の {@code COND} を重ねたものになる。
 */
@Tag("V1")
class JclIfTest {

    private static final Path BASE = Path.of("data");

    private static Job job(String... cards) {
        Jcl.Result result = Jcl.read(String.join("\n", cards));
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        return result.job();
    }

    private static String diagnostics(String... cards) {
        return Jcl.read(String.join("\n", cards)).diagnostics().toString();
    }

    /** 条件だけを取り出して動くかどうかを見る。 */
    private static boolean allows(StepCondition condition, JobState state) {
        return condition.allows(state);
    }

    private static JobState after(String step, int returnCode) {
        JobState state = new JobState();
        state.completed(step, returnCode);
        return state;
    }

    @Test
    @DisplayName("THEN の中のステップに条件が付く (FR-131)")
    void stepsInsideThenGetTheCondition() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//CHECK    EXEC PGM=PAYCHK",
                "//         IF (CHECK.RC = 0) THEN",
                "//REPORT   EXEC PGM=PAYRPT",
                "//         ENDIF");

        StepCondition.ReturnCode condition = assertInstanceOf(StepCondition.ReturnCode.class,
                job.steps().get(1).condition());
        assertEquals("CHECK", condition.step());
        assertEquals(StepCondition.Comparison.EQ, condition.comparison());
        assertTrue(allows(condition, after("CHECK", 0)));
        assertTrue(!allows(condition, after("CHECK", 4)));
    }

    @Test
    @DisplayName("ELSE の中のステップは裏返した条件になる (FR-131)")
    void stepsInsideElseGetTheOppositeCondition() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//CHECK    EXEC PGM=PAYCHK",
                "//         IF (CHECK.RC = 0) THEN",
                "//GOOD     EXEC PGM=PGMA",
                "//         ELSE",
                "//BAD      EXEC PGM=PGMB",
                "//         ENDIF");

        assertTrue(allows(job.steps().get(1).condition(), after("CHECK", 0)));
        assertTrue(!allows(job.steps().get(2).condition(), after("CHECK", 0)));
        assertTrue(allows(job.steps().get(2).condition(), after("CHECK", 8)));
    }

    @Test
    @DisplayName("外に置いたステップは条件を受けない (FR-131)")
    void stepsOutsideTheBlockAreUnaffected() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//         IF (RC = 0) THEN",
                "//INSIDE   EXEC PGM=PGMA",
                "//         ENDIF",
                "//OUTSIDE  EXEC PGM=PGMB");

        assertInstanceOf(StepCondition.Always.class, job.steps().get(1).condition());
    }

    @Test
    @DisplayName("入れ子の IF は条件を重ねる (FR-131)")
    void nestedIfsCombine() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//A        EXEC PGM=P",
                "//         IF (A.RC = 0) THEN",
                "//         IF (RC < 4) THEN",
                "//B        EXEC PGM=Q",
                "//         ENDIF",
                "//         ENDIF");

        StepCondition.All all =
                assertInstanceOf(StepCondition.All.class, job.steps().get(1).condition());
        assertEquals(2, all.parts().size());
    }

    @Test
    @DisplayName("COND と IF は重なる (FR-131, FR-136)")
    void condAndIfBothApply() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//A        EXEC PGM=P",
                "//         IF (A.RC = 0) THEN",
                "//B        EXEC PGM=Q,COND=(8,LE)",
                "//         ENDIF");

        StepCondition.All all =
                assertInstanceOf(StepCondition.All.class, job.steps().get(1).condition());
        assertEquals(2, all.parts().size());
        assertInstanceOf(StepCondition.Not.class, all.parts().get(1));
    }

    @Test
    @DisplayName("AND と OR と NOT を読む (FR-131)")
    void logicalOperatorsAreRead() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//A        EXEC PGM=P",
                "//B        EXEC PGM=Q",
                "//         IF (A.RC = 0 & (B.RC < 4 | ¬B.RUN)) THEN",
                "//C        EXEC PGM=R",
                "//         ENDIF");

        StepCondition.All all =
                assertInstanceOf(StepCondition.All.class, job.steps().get(2).condition());
        assertEquals(2, all.parts().size());
        StepCondition.Any any = assertInstanceOf(StepCondition.Any.class, all.parts().get(1));
        assertInstanceOf(StepCondition.Not.class, any.parts().get(1));
    }

    @Test
    @DisplayName("綴りの演算子も読む (FR-131)")
    void wordOperatorsAreRead() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//A        EXEC PGM=P",
                "//         IF (A.RC EQ 0 AND NOT A.RC GT 4) THEN",
                "//B        EXEC PGM=Q",
                "//         ENDIF");

        assertInstanceOf(StepCondition.All.class, job.steps().get(1).condition());
    }

    @Test
    @DisplayName("ABEND は異常終了を越える (FR-131, FR-136)")
    void abendConditionsSurviveAnAbend() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//A        EXEC PGM=P",
                "//         IF (ABEND) THEN",
                "//CLEANUP  EXEC PGM=Q",
                "//         ENDIF");

        StepCondition condition = job.steps().get(1).condition();
        assertInstanceOf(StepCondition.OnlyIfAbend.class, condition);
        assertTrue(condition.survivesAbend());
    }

    @Test
    @DisplayName("ステップが動いたかを見られる (FR-131)")
    void aStepMayBeTestedForHavingRun() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//A        EXEC PGM=P",
                "//         IF (A.RUN) THEN",
                "//B        EXEC PGM=Q",
                "//         ENDIF");

        StepCondition.Ran condition =
                assertInstanceOf(StepCondition.Ran.class, job.steps().get(1).condition());
        assertEquals("A", condition.step());
        assertTrue(allows(condition, after("A", 4)));
        assertTrue(!allows(condition, new JobState()));
    }

    @Test
    @DisplayName("手続きの中のステップも名指せる (FR-131)")
    void aProcedureStepMayBeNamed() {
        Job job = job(
                "//PAYROLL  JOB  (ACCT)",
                "//A        EXEC PGM=P",
                "//         IF (RUN.CHECK.RC = 0) THEN",
                "//B        EXEC PGM=Q",
                "//         ENDIF");

        assertEquals("RUN.CHECK", assertInstanceOf(StepCondition.ReturnCode.class,
                job.steps().get(1).condition()).step());
    }

    // ---- 誤りの検査 ----

    @Test
    @DisplayName("THEN のない IF は誤りである (FR-131)")
    void anIfNeedsThen() {
        assertTrue(diagnostics("//J JOB", "//  IF (RC = 0)").contains("IF needs THEN"));
    }

    @Test
    @DisplayName("ENDIF で閉じない IF は誤りである (FR-131)")
    void anUnclosedIfIsAnError() {
        assertTrue(diagnostics("//J JOB", "//  IF (RC = 0) THEN", "//A EXEC PGM=P")
                .contains("not closed by ENDIF"));
    }

    @Test
    @DisplayName("対応しない ELSE と ENDIF は誤りである (FR-131)")
    void strayElseAndEndifAreErrors() {
        assertTrue(diagnostics("//J JOB", "//  ELSE").contains("ELSE without a matching IF"));
        assertTrue(diagnostics("//J JOB", "//  ENDIF").contains("ENDIF without a matching IF"));
    }

    @Test
    @DisplayName("読めない関係式は誤りである (FR-131)")
    void malformedConditionsAreErrors() {
        assertTrue(diagnostics("//J JOB", "//  IF (MAYBE) THEN", "//  ENDIF")
                .contains("IF does not understand"));
        assertTrue(diagnostics("//J JOB", "//  IF (RC ~ 0) THEN", "//  ENDIF")
                .contains("unknown comparison in IF"));
        assertTrue(diagnostics("//J JOB", "//  IF (RC = x) THEN", "//  ENDIF")
                .contains("must be an integer"));
        assertTrue(diagnostics("//J JOB", "//  IF ((RC = 0) THEN", "//  ENDIF")
                .contains("missing a closing parenthesis"));
    }

    @Test
    @DisplayName("知らない異常終了コードは誤りである (FR-131, FR-141)")
    void anUnknownAbendCodeIsAnError() {
        assertTrue(diagnostics("//J JOB", "//  IF (ABENDCC = S9Z9) THEN", "//  ENDIF")
                .contains("unknown abend code: S9Z9"));
        assertTrue(diagnostics("//J JOB", "//  IF (ABENDCC > S0C7) THEN", "//  ENDIF")
                .contains("ABENDCC compares with"));
    }
}
