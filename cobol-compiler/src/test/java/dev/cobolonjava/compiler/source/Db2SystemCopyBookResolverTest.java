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
    @DisplayName("INCLUDE SQLDA は暫定の形 (SQLVAR 750 個、番地は 4 byte の POINTER) で置き、欄を参照できる")
    void includesSqlda() {
        CobolCompiler.Result result = CobolCompiler.with(new Db2SystemCopyBookResolver())
                .compile("SQLCAPGM.cbl", program("EXEC SQL INCLUDE SQLDA END-EXEC.",
                        "MOVE 'SQLDA' TO SQLDAID",
                        "MOVE 750 TO SQLN",
                        "MOVE SQLTYPE(1) TO WS-CODE",
                        "MOVE SQLNAMEC(750) TO SQLDAID",
                        "SET SQLDATA(1) TO NULL",
                        "GOBACK."));

        assertTrue(result.succeeded(), result.diagnostics().toString());
        assertFalse(result.diagnostics().stream().anyMatch(d -> d.message().contains("INCLUDE SQLDA")));
    }
}
