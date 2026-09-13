package dev.cobolonjava.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.procedure.ProcedureOutcome;
import dev.cobolonjava.runtime.procedure.NonLocalProcedureTransferException;
import dev.cobolonjava.runtime.interop.CobolSessionStateException;
import dev.cobolonjava.runtime.interop.ProgramSignatureMismatchException;
import dev.cobolonjava.runtime.interop.Termination;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** JUnitから生成COBOLの明示的PERFORM SECTIONを差し替える結合契約。 */
class CobolSectionMockTest {

    private static final String SOURCE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. SECTIONTEST.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01 WS-N PIC 9(3) VALUE 0.",
            "       PROCEDURE DIVISION.",
            "       MAIN-START.",
            "           PERFORM FETCH-RATE",
            "           PERFORM CALCULATE",
            "           DISPLAY WS-N",
            "           GOBACK.",
            "       FETCH-RATE SECTION.",
            "       FETCH-P.",
            "           ADD 1 TO WS-N.",
            "       CALCULATE SECTION.",
            "       CALC-P.",
            "           ADD 10 TO WS-N.",
            "       ORCHESTRATE SECTION.",
            "       ORCHESTRATE-P.",
            "           PERFORM FETCH-RATE.");

    private static final String SEQUENCE_SOURCE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. ORDERTEST.",
            "       PROCEDURE DIVISION.",
            "       MAIN-START.",
            "           PERFORM FIRST-PART",
            "           CALL 'AUDIT'",
            "           GOBACK.",
            "       FIRST-PART SECTION.",
            "       FIRST-P.",
            "           CONTINUE.");

    private static final String NON_LOCAL_SOURCE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. GOTEST.",
            "       PROCEDURE DIVISION.",
            "       MAIN-START.",
            "           GOBACK.",
            "       BAD SECTION.",
            "       BAD-P.",
            "           GO TO AFTER-BAD.",
            "       SAFE SECTION.",
            "       AFTER-BAD.",
            "           CONTINUE.");

    private static final String DIRECT_ARGUMENT_SOURCE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. DIRECTARGS.",
            "       DATA DIVISION.",
            "       LINKAGE SECTION.",
            "       01 LK-TEXT PIC X(3).",
            "       PROCEDURE DIVISION USING LK-TEXT.",
            "       MAIN-START.",
            "           GOBACK.",
            "       MUTATE SECTION.",
            "       MUTATE-P.",
            "           MOVE 'SEC' TO LK-TEXT.",
            "       RETURN-NOW SECTION.",
            "       RETURN-P.",
            "           GOBACK.",
            "       STOP-NOW SECTION.",
            "       STOP-P.",
            "           STOP RUN.");

    @RegisterExtension
    final CobolExtension cobol = CobolExtension.builder()
            .sourceText("SECTIONTEST.cbl", SOURCE)
            .sourceText("ORDERTEST.cbl", SEQUENCE_SOURCE)
            .sourceText("GOTEST.cbl", NON_LOCAL_SOURCE)
            .sourceText("DIRECTARGS.cbl", DIRECT_ARGUMENT_SOURCE)
            .build();

    @Test
    void mocksOneSectionAndSpiesAnother() {
        CobolSectionMock fetch = cobol.mockSection("SECTIONTEST", "FETCH-RATE")
                .times(1)
                .thenAnswer(invocation -> invocation.workingStorage().view(0, 3)
                        .setBytes(invocation.codePage().encode("100")));
        CobolSectionMock calculate = cobol.spySection("SECTIONTEST", "CALCULATE")
                .times(1);

        CobolTestResult result = cobol.program("SECTIONTEST").call();

        assertEquals("110", result.output().trim());
        assertEquals(ProcedureOutcome.MOCK_RETURN, fetch.invocations().get(0).outcome());
        assertEquals("000", cobol.codePage().decode(
                fetch.invocations().get(0).workingStorageBefore()));
        assertEquals("100", cobol.codePage().decode(
                fetch.invocations().get(0).workingStorageAfter()));
        assertEquals(ProcedureOutcome.REAL_RETURN,
                calculate.invocations().get(0).outcome());
        assertEquals("100", cobol.codePage().decode(
                calculate.invocations().get(0).workingStorageBefore()));
        assertEquals("110", cobol.codePage().decode(
                calculate.invocations().get(0).workingStorageAfter()));
    }

    @Test
    void keepsTheCauseAndHistoryWhenASectionMockFails() {
        CobolSectionMock fetch = cobol.mockSection("SECTIONTEST", "FETCH-RATE")
                .times(1)
                .thenAnswer(invocation -> {
                    throw new IOException("rate source unavailable");
                });

        CobolMockException failure = assertThrows(CobolMockException.class,
                () -> cobol.program("SECTIONTEST").call());

        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals(1, fetch.count());
        assertEquals(ProcedureOutcome.THREW, fetch.invocations().get(0).outcome());
    }

    @Test
    void programAndSectionMocksUseOneSequence() {
        CobolSectionMock section = cobol.mockSection("ORDERTEST", "FIRST-PART");
        CobolProgramMock program = cobol.expectProgram("AUDIT");

        cobol.program("ORDERTEST").call();

        assertEquals(1, section.invocations().get(0).sequence());
        assertEquals(2, program.invocations().get(0).sequence());
    }

    @Test
    void rejectsUnknownSectionsBeforeExecution() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> cobol.spySection("SECTIONTEST", "FETCH-RTAE"));

        assertEquals("SECTION is not a mockable normal SECTION in SECTIONTEST: FETCH-RTAE",
                failure.getMessage());
    }

    @Test
    void rejectsUnknownProgramsBeforeExecution() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> cobol.spySection("SECTIONTSET", "FETCH-RATE"));

        assertEquals("unknown test program for SECTION override: SECTIONTSET",
                failure.getMessage());
    }

    @Test
    void invokesAnEligibleSectionAndIgnoresItsOwnMock() {
        CobolSectionMock target = cobol.mockSection("SECTIONTEST", "CALCULATE").times(0);
        CobolProgramFixture fixture = cobol.program("SECTIONTEST");
        fixture.workingStorage().view(0, 3).setBytes(cobol.codePage().encode("005"));

        CobolTestResult result = fixture.invokeSection("CALCULATE");

        assertEquals("015", cobol.codePage().decode(
                result.workingStorage().view(0, 3).toByteArray()));
        assertEquals(0, target.count());
    }

    @Test
    void nestedSectionMocksRemainActiveDuringDirectInvocation() {
        CobolSectionMock dependency = cobol.mockSection("SECTIONTEST", "FETCH-RATE")
                .thenAnswer(invocation -> invocation.workingStorage().view(0, 3)
                        .setBytes(invocation.codePage().encode("100")));
        cobol.mockSection("SECTIONTEST", "ORCHESTRATE").times(0);

        CobolTestResult result = cobol.program("SECTIONTEST")
                .invokeSection("ORCHESTRATE");

        assertEquals("100", cobol.codePage().decode(
                result.workingStorage().view(0, 3).toByteArray()));
        assertEquals(1, dependency.count());
    }

    @Test
    void rejectsNonLocalControlFlowWithoutPoisoningTheSession() {
        CobolProgramFixture fixture = cobol.program("GOTEST");

        NonLocalProcedureTransferException failure = assertThrows(
                NonLocalProcedureTransferException.class,
                () -> fixture.invokeSection("BAD"));

        assertEquals("BAD", failure.procedureId().name());
        fixture.invokeSection("SAFE");
    }

    @Test
    void passesLinkageArgumentsByReferenceToADirectSection() {
        Storage argument = Storage.copyOf(cobol.codePage().encode("---"));

        CobolTestResult result = cobol.program("DIRECTARGS")
                .byReference(argument.whole())
                .invokeSection("MUTATE");

        assertEquals(Termination.RETURNED, result.termination());
        assertEquals("SEC", cobol.codePage().decode(argument.array()));
    }

    @Test
    void treatsGobackAsAReturnFromTheDirectSection() {
        Storage argument = Storage.allocate(3);
        CobolTestResult result = cobol.program("DIRECTARGS")
                .byReference(argument.whole())
                .invokeSection("RETURN-NOW");

        assertEquals(Termination.RETURNED, result.termination());
    }

    @Test
    void stopRunTerminatesTheTestSession() {
        CobolProgramFixture fixture = cobol.program("DIRECTARGS")
                .byReference(Storage.allocate(3).whole());

        CobolTestResult result = fixture.invokeSection("STOP-NOW");

        assertEquals(Termination.STOP_RUN, result.termination());
        assertThrows(CobolSessionStateException.class,
                () -> fixture.invokeSection("MUTATE"));
    }

    @Test
    void rejectsDirectSectionArgumentsAgainstTheGeneratedSignature() {
        Storage argument = Storage.copyOf(cobol.codePage().encode("--"));
        CobolProgramFixture fixture = cobol.program("DIRECTARGS")
                .byReference(argument.whole());

        ProgramSignatureMismatchException failure = assertThrows(
                ProgramSignatureMismatchException.class,
                () -> fixture.invokeSection("MUTATE"));

        assertEquals("DIRECTARGS", failure.programId().value());
        assertEquals("--", cobol.codePage().decode(argument.array()));
    }

    @Test
    void preservesTheGeneratedSignatureWhenMockingACompiledProgram() {
        CobolProgramMock mock = cobol.expectProgram("DIRECTARGS").times(0);

        assertThrows(ProgramSignatureMismatchException.class,
                () -> cobol.program("DIRECTARGS")
                        .byReference(Storage.allocate(2).whole())
                        .call());

        assertEquals(0, mock.count());
    }
}
