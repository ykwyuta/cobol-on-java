package dev.cobolonjava.compiler.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Db2 precompiler が提供する INCLUDE 名。 */
@Tag("V1")
class Db2SystemCopyBookResolverTest {

    private static String program(String include, String... procedure) {
        StringBuilder out = new StringBuilder();
        for (String line : new String[] {
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SQLCAPGM.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                include,
                "01  WS-CODE PIC S9(9) COMP.",
                "PROCEDURE DIVISION."}) {
            out.append("       ").append(line).append('\n');
        }
        for (String line : procedure) {
            out.append("           ").append(line).append('\n');
        }
        return out.toString();
    }

    @Test
    @DisplayName("INCLUDE SQLCA の項目を手続き部から参照できる")
    void includesSqlca() {
        CobolCompiler.Result result = CobolCompiler.with(new Db2SystemCopyBookResolver())
                .compile("SQLCAPGM.cbl", program("EXEC SQL INCLUDE SQLCA END-EXEC.",
                        "IF SQLCODE = 100 AND SQLSTATE = '02000'",
                        "    MOVE SQLERRD(3) TO WS-CODE",
                        "END-IF",
                        "MOVE SQLERRMC TO SQLCAID",
                        "GOBACK."));

        assertTrue(result.succeeded(), result.diagnostics().toString());
    }

    @Test
    @DisplayName("INCLUDE SQLDA は POINTER を持つ記述子なので、名前をつけて断る")
    void rejectsSqlda() {
        CobolCompiler.Result result = CobolCompiler.with(new Db2SystemCopyBookResolver())
                .compile("SQLCAPGM.cbl", program("EXEC SQL INCLUDE SQLDA END-EXEC.", "GOBACK."));

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("INCLUDE SQLDA")
                        && d.toString().contains("SQLCAPGM.cbl:5")),
                result.diagnostics().toString());
    }
}
