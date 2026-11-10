package dev.cobolonjava.compiler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 書けない組み合わせを<b>断る</b>ことの検査。
 *
 * <p>ZCVS (Zeus の検査スイート) の負系を流して、黙って翻訳していたものが 18 本見つかった。
 * 黙って通すと、句が効かないまま動く。翻訳できる・できないの数だけを見ていると、
 * 「断るべきものを通している」は<b>合格として数えられてしまう</b>ので、負系を別に持つ。
 *
 * <p>どの検査も、<b>規則を 1 つ直した対照</b>が翻訳できることを一緒に確かめる。
 * 断れているのが「その規則」であって、別の理由で落ちているのではないことを示すためである。
 */
@Tag("V1")
class RefusedCombinationsTest {

    private static CobolCompiler.Result compile(List<String> lines) {
        StringBuilder source = new StringBuilder();
        for (String line : lines) {
            source.append("       ").append(line).append('\n');
        }
        return CobolCompiler.standard().compile("PGM.cbl", source.toString());
    }

    /** データ記述項を 1 行だけ持つプログラム。 */
    private static List<String> withItem(String section, String item) {
        return List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. PGM.", "DATA DIVISION.",
                section, item, "PROCEDURE DIVISION.", "    STOP RUN.");
    }

    private static void refused(List<String> lines, String because) {
        CobolCompiler.Result result = compile(lines);
        assertFalse(result.succeeded(), () -> "should be refused: " + lines);
        assertTrue(result.diagnostics().toString().contains(because),
                () -> because + " not in " + result.diagnostics());
    }

    private static void accepted(List<String> lines) {
        CobolCompiler.Result result = compile(lines);
        assertTrue(result.succeeded(), () -> lines + ": " + result.diagnostics());
    }

    private static final String WS = "WORKING-STORAGE SECTION.";

    @Test
    @DisplayName("JUSTIFIED は英字・英数字の項目にだけ書ける")
    void justifiedRequiresAnAlphanumericItem() {
        refused(withItem(WS, "01 ITEM-A PIC 99 JUSTIFIED."), "JUSTIFIED requires");
        accepted(withItem(WS, "01 ITEM-A PIC XX JUSTIFIED."));
    }

    @Test
    @DisplayName("BLANK WHEN ZERO は S と * を持つ PICTURE には書けない")
    void blankWhenZeroRefusesSignAndAsterisk() {
        refused(withItem(WS, "01 ITEM-A PIC S99 BLANK WHEN ZERO."), "BLANK WHEN ZERO requires");
        refused(withItem(WS, "01 ITEM-A PIC **9 BLANK WHEN ZERO."), "BLANK WHEN ZERO requires");
        accepted(withItem(WS, "01 ITEM-A PIC ZZ9 BLANK WHEN ZERO."));
    }

    @Test
    @DisplayName("SIGN 句は S を持つ PICTURE にだけ書ける")
    void signRequiresASignedPicture() {
        refused(withItem(WS, "01 ITEM-A PIC 99 SIGN LEADING SEPARATE."), "SIGN requires");
        accepted(withItem(WS, "01 ITEM-A PIC S99 SIGN LEADING SEPARATE."));
    }

    @Test
    @DisplayName("LOCAL-STORAGE の項目に EXTERNAL は書けない")
    void localStorageCannotBeExternal() {
        refused(withItem("LOCAL-STORAGE SECTION.", "01 ITEM-A PIC X EXTERNAL."),
                "LOCAL-STORAGE cannot specify EXTERNAL");
        accepted(withItem(WS, "01 ITEM-A PIC X EXTERNAL."));
    }

    @Test
    @DisplayName("OCCURS DEPENDING ON の対象は整数の項目でなければならない")
    void dependingObjectMustBeAnInteger() {
        refused(List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. PGM.", "DATA DIVISION.", WS,
                "01 CNT PIC 9V9.",
                "01 TAB.",
                "   05 ITEM-A PIC X OCCURS 1 TO 3 DEPENDING ON CNT.",
                "PROCEDURE DIVISION.", "    STOP RUN."), "must be an integer");
    }

    @Test
    @DisplayName("END PROGRAM の名前は、閉じるプログラムの名前と合っていなければならない")
    void endProgramMustMatch() {
        refused(List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. PGM.",
                "PROCEDURE DIVISION.", "    STOP RUN.", "END PROGRAM WRONGID."),
                "does not match");
        accepted(List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. PGM.",
                "PROCEDURE DIVISION.", "    STOP RUN.", "END PROGRAM PGM."));
    }

    @Test
    @DisplayName("別のプログラムを中に持つプログラムは END PROGRAM で閉じなければならない")
    void aContainingProgramMustBeClosed() {
        List<String> open = List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. OUTER.",
                "PROCEDURE DIVISION.", "    STOP RUN.",
                "IDENTIFICATION DIVISION.", "PROGRAM-ID. INNER.",
                "PROCEDURE DIVISION.", "    GOBACK.", "END PROGRAM INNER.");
        refused(open, "END PROGRAM required");
        List<String> closed = new java.util.ArrayList<>(open);
        closed.add("END PROGRAM OUTER.");
        accepted(closed);
    }

    @Test
    @DisplayName("再帰プログラムには ALTER と行き先の無い GO TO を書けない")
    void recursiveProgramsCannotAlter() {
        refused(List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. PGM IS RECURSIVE.",
                "PROCEDURE DIVISION.",
                "P-MAIN.", "    ALTER P-JUMP TO PROCEED TO P-DONE.",
                "P-JUMP.", "    GO TO P-DONE.",
                "P-DONE.", "    GOBACK."), "RECURSIVE");
        refused(List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. PGM IS RECURSIVE.",
                "PROCEDURE DIVISION.",
                "P-MAIN.", "    GO TO.",
                "P-DONE.", "    GOBACK."), "RECURSIVE");
        accepted(List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. PGM.",
                "PROCEDURE DIVISION.",
                "P-MAIN.", "    ALTER P-JUMP TO PROCEED TO P-DONE.",
                "P-JUMP.", "    GO TO P-DONE.",
                "P-DONE.", "    GOBACK."));
    }

    @Test
    @DisplayName("INITIALIZE REPLACING に同じ分類を 2 度は書けない")
    void initializeReplacingCategoriesAreUnique() {
        refused(List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. PGM.", "DATA DIVISION.", WS,
                "01 WS-A PIC X(4).", "PROCEDURE DIVISION.",
                "    INITIALIZE WS-A REPLACING ALPHANUMERIC DATA BY 'AAAA'",
                "        ALPHANUMERIC DATA BY 'BBBB'.",
                "    STOP RUN."), "duplicate");
    }

    @Test
    @DisplayName("戻り値を返すプログラムに ENTRY は書けず、UNTIL EXIT に WITH TEST は付かない")
    void entryWithReturningAndTestWithUntilExit() {
        refused(List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. PGM.", "DATA DIVISION.",
                "LINKAGE SECTION.", "01 LK-R PIC 9(4) COMP.",
                "PROCEDURE DIVISION RETURNING LK-R.",
                "    ENTRY 'ALTENT'.", "    GOBACK."), "ENTRY");
        refused(List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. PGM.",
                "PROCEDURE DIVISION.",
                "    PERFORM WITH TEST AFTER UNTIL EXIT", "        CONTINUE",
                "    END-PERFORM.", "    STOP RUN."), "UNTIL EXIT");
    }

    @Test
    @DisplayName("数字だけの手続き名は数字定数ではないので、18 桁の上限に掛からない")
    void allDigitProcedureNamesAreNotNumericLiterals() {
        // CCVS85 の NC107A の書き方。定数の桁数の検査が手続き名まで数えて断っていた
        accepted(List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. PGM.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    PERFORM 0000000000000000000000000001.",
                "    STOP RUN.",
                "0000000000000000000000000001.",
                "    CONTINUE."));
        refused(withItem(WS, "01 ITEM-A PIC 9 VALUE 1234567890123456789."), "the maximum is");
    }
}
