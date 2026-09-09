package dev.cobolonjava.junit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.interop.Termination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** JUnit extension、生成 COBOL、外部サブルーチン Mock の結合契約。 */
class CobolExtensionTest {

    private static final String SOURCE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. CALLMOCK.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01 WS-TEXT PIC X(3) VALUE 'abc'.",
            "       PROCEDURE DIVISION.",
            "       MAIN-START.",
            "           CALL 'RATEAPI' USING WS-TEXT",
            "           DISPLAY WS-TEXT",
            "           GOBACK.");

    @RegisterExtension
    final CobolExtension cobol = CobolExtension.builder()
            .sourceText("CALLMOCK.cbl", SOURCE)
            .build();

    @Test
    void executesGeneratedCobolAndRecordsMockSnapshots() {
        CobolProgramMock mock = cobol.expectProgram("RATEAPI")
                .thenAnswer((context, arguments) ->
                        arguments.get(0).setBytes(context.codePage().encode("XYZ")));

        CobolTestResult result = cobol.program("CALLMOCK").call();

        assertEquals(Termination.RETURNED, result.termination());
        assertEquals(0, result.returnCode());
        assertEquals("XYZ", result.output().trim());
        assertEquals(1, mock.count());
        ProgramInvocation invocation = mock.invocations().get(0);
        assertArrayEquals(cobol.codePage().encode("abc"), invocation.argumentsBefore().get(0));
        assertArrayEquals(cobol.codePage().encode("XYZ"), invocation.argumentsAfter().get(0));
        byte[] exposedSnapshot = invocation.argumentsBefore().get(0);
        exposedSnapshot[0] = 0;
        assertArrayEquals(cobol.codePage().encode("abc"), invocation.argumentsBefore().get(0));
        assertThrows(IllegalTestStateException.class,
                () -> cobol.stubProgram("TOO-LATE"));
    }

    @Test
    void resolvesTheTestContextAsAJunitParameter(CobolTestContext context) {
        context.stubProgram("RATEAPI", (call, arguments) ->
                arguments.get(0).setBytes(call.codePage().encode("PAR")));

        CobolTestResult result = context.program("CALLMOCK").runMain();

        assertEquals("PAR", result.output().trim());
        assertEquals("PAR", context.output().trim());
    }

    @Test
    void anExpectationDoesNotSilentlyRepeatAnUndefinedAnswer() {
        CobolProgramMock mock = cobol.expectProgram("RATEAPI")
                .times(2)
                .thenReturn();

        cobol.program("CALLMOCK").call();
        assertThrows(UnexpectedCobolCallException.class,
                () -> cobol.program("CALLMOCK").call());

        assertEquals(2, mock.count(), "失敗した呼び出しも履歴へ残す");
    }
}
