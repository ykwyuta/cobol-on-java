package dev.cobolonjava.verify.pli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PliVerificationRunnerTest {

    @TempDir
    Path temporary;

    /** FIXED BIN(31) の list-directed は幅 14 の欄に右寄せである (LRM "Target: CHARACTER")。 */
    private static final String TOTAL_15 = String.format("%14s", "15") + "\n";
    private static final String TOTAL_14 = String.format("%14s", "14") + "\n";

    private static final String LOOP = """
            LOOP: PROCEDURE OPTIONS(MAIN);
              DCL I FIXED BIN(31);
              DCL TOTAL FIXED BIN(31) INIT(0);
              DO I = 1 TO 5;
                TOTAL = TOTAL + I;
              END;
              PUT SKIP LIST(TOTAL);
            END LOOP;
            """;

    @Test
    void executesCasesAndComparesReferenceOutput() {
        PliVerificationRunner runner = PliVerificationRunner.standard();

        PliVerificationReport report = runner.run(List.of(
                new PliVerificationRunner.Source("PASS.pli", "iteration", LOOP, TOTAL_15),
                new PliVerificationRunner.Source("WRONG.pli", "iteration", LOOP, TOTAL_14),
                new PliVerificationRunner.Source("COMPILE.pli", "syntax", LOOP, null)));

        assertEquals(3, report.translated());
        assertEquals(1, report.count(PliCaseOutcome.Status.PASSED));
        assertEquals(1, report.count(PliCaseOutcome.Status.WRONG_OUTPUT));
        assertEquals(1, report.count(PliCaseOutcome.Status.COMPILED));
        assertEquals(50.0, report.oracleRate());
        assertTrue(report.csv().contains("iteration,2,2,1,1,0,0,100.0,50.0"));
    }

    @Test
    void rejectedCaseDoesNotStopTheRemainingCases() {
        PliVerificationReport report = PliVerificationRunner.standard().run(List.of(
                new PliVerificationRunner.Source("BAD.pli", "syntax", "not PL/I", null),
                new PliVerificationRunner.Source("GOOD.pli", "syntax", LOOP, null)));

        assertEquals(1, report.count(PliCaseOutcome.Status.REJECTED));
        assertEquals(1, report.count(PliCaseOutcome.Status.COMPILED));
        assertEquals(1, report.reasons(5).size());
    }

    @Test
    void directoryPairsPliWithReferenceOutput() throws Exception {
        Path group = Files.createDirectories(temporary.resolve("iteration"));
        Files.writeString(group.resolve("LOOP.pli"), LOOP);
        Files.writeString(group.resolve("LOOP.out"), TOTAL_15);
        Files.writeString(temporary.resolve("BARE.pl1"), LOOP);

        List<PliVerificationRunner.Source> sources = PliSourceDirectory.read(temporary);

        assertEquals(2, sources.size());
        assertEquals("(root)", sources.get(0).group());
        assertEquals("iteration", sources.get(1).group());
        assertEquals(TOTAL_15, sources.get(1).expectedOutput());
    }
}
