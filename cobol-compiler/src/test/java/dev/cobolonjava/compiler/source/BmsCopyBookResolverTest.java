package dev.cobolonjava.compiler.source;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** BMSの原文を、COPYから記号マップとして引く。 */
@Tag("V1")
class BmsCopyBookResolverTest {

    @TempDir
    Path directory;

    private static String card(String body, boolean continued) {
        return continued ? String.format("%-71s*", body) : body;
    }

    private void writeMapset(String... cards) throws IOException {
        Files.writeString(directory.resolve("SCRNSET.bms"), String.join("\n", cards) + "\n");
    }

    private static String program(String... procedure) {
        StringBuilder out = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SCREEN1.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "COPY SCRNSET.",
                "01  WS-INDEX PIC 9(2) VALUE 1.",
                "PROCEDURE DIVISION.")) {
            out.append("       ").append(line).append('\n');
        }
        for (String line : procedure) {
            out.append("           ").append(line).append('\n');
        }
        return out.toString();
    }

    @Test
    @DisplayName("COPY mapsetで記号マップの派生名と、OCCURSの添字参照が翻訳できる")
    void compilesProgramUsingGeneratedSymbolicMap() throws IOException {
        writeMapset(
                card("SCRNSET  DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,STORAGE=AUTO,", true),
                card("               TIOAPFX=YES,EXTATT=YES,", true),
                card("               DSATTS=(COLOR,HILIGHT,OUTLINE,PS,SOSI)", false),
                card("SCRN1    DFHMDI SIZE=(24,80)", false),
                card("CUSTNO   DFHMDF POS=(5,17),LENGTH=10,ATTRB=(NORM,NUM,IC)", false),
                card("RATE     DFHMDF POS=(6,17),LENGTH=7,PICOUT='9999.99'", false),
                card("ROW      DFHMDF POS=(9,1),LENGTH=79,OCCURS=3", false),
                card("         DFHMSD TYPE=FINAL", false),
                card("         END", false));

        CobolCompiler.Result result = CobolCompiler.with(new BmsCopyBookResolver(directory))
                .compile("SCREEN1.cbl", program(
                        "MOVE LOW-VALUES TO SCRN1O.",
                        "MOVE -1 TO CUSTNOL.",
                        "MOVE SPACES TO CUSTNOO.",
                        "MOVE ZERO TO RATEO.",
                        "MOVE SPACES TO ROWO(WS-INDEX).",
                        "IF CUSTNOI = SPACES AND ROWF(2) = SPACE",
                        "    MOVE CUSTNOA TO CUSTNOC",
                        "END-IF.",
                        "GOBACK."));

        assertTrue(result.succeeded(), result.diagnostics().toString());
    }

    @Test
    @DisplayName("BMSの誤りはCOPY文の位置とBMSの行番号をつけて断る")
    void reportsBmsErrorsAtTheCopyStatement() throws IOException {
        writeMapset(
                card("SCRNSET  DFHMSD TYPE=MAP,MODE=INOUT,LANG=COBOL,TIOAPFX=YES", false),
                card("SCRN1    DFHMDI SIZE=(24,80)", false),
                card("F1       DFHMDF POS=(1,2),LENGTH=3,GRPNAME=G1", false),
                card("         DFHMSD TYPE=FINAL", false));

        CobolCompiler.Result result = CobolCompiler.with(new BmsCopyBookResolver(directory))
                .compile("SCREEN1.cbl", program("GOBACK."));

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.toString().contains("SCREEN1.cbl:5")
                                && diagnostic.message().contains("line 3")
                                && diagnostic.message().contains("GRPNAME")),
                result.diagnostics().toString());
    }
}
