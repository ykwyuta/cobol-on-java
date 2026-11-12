package dev.cobolonjava.pli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** マクロ前処理 (Enterprise PL/I Language Reference, "Preprocessor facilities")。 */
class MacroProcessorTest {

    private static String expand(String source) {
        PliMacroProcessor.Result result = PliMacroProcessor.process("M.pli", source);
        assertTrue(result.diagnostics().isEmpty(), () -> result.diagnostics().toString());
        return result.source();
    }

    /** 空白をまとめて比べる。前処理の値の FIXED は 8 桁の欄に右寄せされる。 */
    private static String words(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("前処理の文が無い原文は、注記も改行も含めて 1 文字も変えない")
    void sourceWithoutPreprocessorStatementsIsUnchanged() {
        String source = "/*PLI-OPTIONS RULES(ANS)*/\n X: PROC OPTIONS(MAIN);\n"
                + " /* 注記\n 2 行 */ %PAGE;\n PUT LIST('100%');\n END X;\n";
        assertEquals(source, expand(source));
    }

    @Test
    @DisplayName("%DO と %IF はコンパイル時に動き、FIXED の値は 8 桁の欄で置き換わる。行は変わらない")
    void compileTimeLoopAndIf() {
        String source = """
                 %DCL (I,V) FIXED;
                 %V=0;
                 %DO I=1 TO 5;
                 %V=V+I;
                 %END;
                 %IF V=15 %THEN %V=17;
                 N=V;
                """;
        String expanded = expand(source);
        assertEquals("N=      17;", expanded.lines().toList().get(6).strip());
        assertEquals(source.lines().count(), expanded.lines().count());
    }

    @Test
    @DisplayName("%DEACTIVATE した名は置き換えない。名札として書ける")
    void deactivatedNamesAreLeftAlone() {
        assertEquals("N= 1; V: N=3;", words(expand("""
                 %DCL V FIXED;
                 %V=1;
                 N=V;
                 %DEACTIVATE V;
                 V: N=3;
                """)));
    }

    @Test
    @DisplayName("RESCAN は置き換えた値をもう一度読み、NORESCAN は読まない")
    void rescanAndNorescan() {
        String declarations = """
                 %DCL (A, B) CHAR;
                 %A='B';
                 %B='C';
                """;
        assertEquals("X=C;", words(expand(declarations + " X=A;\n")));
        assertEquals("X=B;", words(expand(declarations + " %ACTIVATE A NORESCAN;\n X=A;\n")));
    }

    @Test
    @DisplayName("前処理の手続きは引数を文字で受け、仮引数の宣言の型へ移して計算する")
    void preprocessorProcedure() {
        assertEquals("N= 9;", words(expand("""
                 %DCL SQUARE ENTRY;
                 %SQUARE: PROC(X) RETURNS(FIXED);
                 DCL X FIXED;
                 RETURN(X*X);
                 %END SQUARE;
                 N=SQUARE(-3);
                """)));
    }

    @Test
    @DisplayName("STATEMENT の手続きは「仮引数名(引数)」でも呼べ、文を終える ';' ごと置き換わる")
    void statementFormWithKeywordArguments() {
        String procedure = """
                 %DCL SETVAL ENTRY;
                 %SETVAL: PROC(NAME,VALUE) STATEMENT RETURNS(CHAR);
                 DCL (NAME,VALUE) CHAR;
                 RETURN(NAME||'='||VALUE||';');
                 %END SETVAL;
                """;
        assertEquals("N=42;", words(expand(procedure + " SETVAL(N,42);\n")));
        assertEquals("N=- 7;", words(expand(procedure + " SETVAL VALUE(-7) NAME(N);\n")));
    }

    @Test
    @DisplayName("宣言していない名の参照と、%NOTE の 16 以上は前処理を止める")
    void undeclaredNamesAndSevereNotesStop() {
        PliMacroProcessor.Result undeclared = PliMacroProcessor.process("M.pli",
                " %DCL V FIXED;\n %V=W+1;\n");
        assertFalse(undeclared.diagnostics().isEmpty());
        assertTrue(undeclared.diagnostics().get(0).message().contains("W is not declared"));
        assertEquals(2, undeclared.diagnostics().get(0).line());
        PliMacroProcessor.Result severe = PliMacroProcessor.process("M.pli",
                " %NOTE('STOP HERE', 16);\n");
        assertTrue(severe.diagnostics().get(0).message().contains("STOP HERE"));
    }
}
