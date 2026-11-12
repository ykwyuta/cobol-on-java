package dev.cobolonjava.pli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 内部手続きの引数と戻り値、DO からの出かた (Enterprise PL/I Language Reference,
 * "Passing arguments to procedures"、"RETURN statement"、"LEAVE statement")。
 *
 * <p>以前は、式の引数、値を返す手続き、LEAVE と ITERATE を断っていた。Zeus の PL/I の
 * 検査スイート (ZPVS) は報告の副手続きに式を渡すので、1 本も動かせなかった。
 */
class ProcedureAndLoopTest {

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(ProcedureAndLoopTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static PliCompiler.Result compile(String body) {
        return PliCompiler.standard().compile("PRC.pli",
                "PRC: PROCEDURE OPTIONS(MAIN);\n" + body + "\nEND PRC;\n");
    }

    private static String run(String body) throws Exception {
        PliCompiler.Result result = compile(body);
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        CobolProgram program = (CobolProgram) new GeneratedLoader()
                .define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        program.runFresh(ProgramContext.capturing(output));
        return String.join(",", output.toString(StandardCharsets.UTF_8).strip()
                .lines().map(String::strip).toList());
    }

    private static String rejection(String body) {
        PliCompiler.Result result = compile(body);
        assertFalse(result.succeeded(), "expected the compiler to refuse: " + body);
        return result.diagnostics().toString();
    }

    @Test
    @DisplayName("式の引数は仮の変数で渡り、CHAR(*) は引数の長さを受け継ぐ")
    void passesExpressionsAsDummyArguments() throws Exception {
        assertEquals("5,YES,3,NO", run("""
                DCL N FIXED BIN(31) INIT(4);
                CALL SHOW('HELLO', N=4);
                CALL SHOW('ABC', N=5);
                SHOW: PROC(ID, OK);
                  DCL ID CHAR(*), OK BIT(1);
                  PUT SKIP LIST(LENGTH(ID));
                  IF OK THEN PUT SKIP LIST('YES'); ELSE PUT SKIP LIST('NO');
                END SHOW;
                """));
    }

    @Test
    @DisplayName("変数を渡せば参照で渡り、手続きの中の代入が呼んだ側に見える")
    void passesVariablesByReference() throws Exception {
        assertEquals("8", run("""
                DCL N FIXED BIN(31) INIT(7);
                CALL BUMP(N);
                PUT SKIP LIST(N);
                BUMP: PROC(X);
                  DCL X FIXED BIN(31);
                  X = X + 1;
                END BUMP;
                """));
    }

    @Test
    @DisplayName("仮の変数への代入は呼んだ側に届かない")
    void doesNotWriteBackThroughADummyArgument() throws Exception {
        assertEquals("7", run("""
                DCL N FIXED BIN(31) INIT(7);
                CALL BUMP(N + 0);
                PUT SKIP LIST(N);
                BUMP: PROC(X);
                  DCL X FIXED BIN(31);
                  X = X + 1;
                END BUMP;
                """));
    }

    @Test
    @DisplayName("RETURNS を持つ手続きは式の中で呼べる")
    void callsAFunctionProcedure() throws Exception {
        assertEquals("15", run("""
                DCL N FIXED BIN(31);
                N = TWICE(7) + 1;
                PUT SKIP LIST(N);
                TWICE: PROC(X) RETURNS(FIXED BIN(31));
                  DCL X FIXED BIN(31);
                  RETURN(X * 2);
                END TWICE;
                """));
    }

    @Test
    @DisplayName("LEAVE は DO を出て、ITERATE は次の繰り返しへ進む")
    void leavesAndIterates() throws Exception {
        // 1〜10 のうち偶数を飛ばし、7 で出る。足されるのは 1 + 3 + 5 = 9
        assertEquals("9", run("""
                DCL (I, T) FIXED BIN(31) INIT(0);
                DO I = 1 TO 10;
                  IF I = 7 THEN LEAVE;
                  IF I = 2 | I = 4 | I = 6 THEN ITERATE;
                  T = T + I;
                END;
                PUT SKIP LIST(T);
                """));
    }

    @Test
    @DisplayName("名前を書いた LEAVE は、その名前の DO まで出る")
    void leavesANamedLoop() throws Exception {
        // 内側で J=3 になったら外側ごと出る。足されるのは I=1 の J=1,2 だけ
        assertEquals("2", run("""
                DCL (I, J, T) FIXED BIN(31) INIT(0);
                OUTER: DO I = 1 TO 3;
                  DO J = 1 TO 3;
                    IF J = 3 THEN LEAVE OUTER;
                    T = T + 1;
                  END;
                END;
                PUT SKIP LIST(T);
                """));
    }

    @Test
    @DisplayName("繰り返さない DO も LEAVE で出られる")
    void leavesAPlainGroup() throws Exception {
        assertEquals("A,C", run("""
                DO;
                  PUT SKIP LIST('A');
                  LEAVE;
                  PUT SKIP LIST('B');
                END;
                PUT SKIP LIST('C');
                """));
    }

    @Test
    @DisplayName("DO の外の LEAVE、無い名前の ITERATE、RETURNS の無い RETURN(値) は断る")
    void rejectsMisplacedStatements() {
        assertTrue(rejection("LEAVE;").contains("outside a DO"));
        assertTrue(rejection("DO; ITERATE MISSING; END;").contains("no enclosing DO"));
        assertTrue(rejection("RETURN(7);").contains("without RETURNS"));
    }

    @Test
    @DisplayName("入口の変数は代入した手続きを呼び、配列の要素の入口は TAB(1)(3) で呼べる")
    void entryVariables() throws Exception {
        assertEquals("14 49 6 9", run("""
                DCL E ENTRY(FIXED BIN(31)) RETURNS(FIXED BIN(31)) VARIABLE;
                DCL TAB(2) ENTRY(FIXED BIN(31)) RETURNS(FIXED BIN(31)) VARIABLE;
                DCL (A, B) FIXED BIN(31);
                E = TWICE; A = E(7);
                E = SQUARE; B = E(7);
                TAB(1) = TWICE; TAB(2) = SQUARE;
                PUT SKIP LIST(A, B, TAB(1)(3), TAB(2)(3));
                TWICE: PROC(X) RETURNS(FIXED BIN(31));
                  DCL X FIXED BIN(31); RETURN(X * 2); END TWICE;
                SQUARE: PROC(X) RETURNS(FIXED BIN(31));
                  DCL X FIXED BIN(31); RETURN(X * X); END SQUARE;
                """).strip().replaceAll("\\s+", " "));
    }

    @Test
    @DisplayName("GENERIC は引数の種類で選択肢を選び、定数の引数に合う選択肢が無ければ翻訳で断る")
    void genericSelection() throws Exception {
        assertEquals("12 3", run("""
                DCL G GENERIC(GN WHEN(FIXED), GC WHEN(CHAR));
                DCL (F1, F2) FIXED BIN(31);
                DCL FLAG FIXED BIN(31);
                CALL G(12); F1 = FLAG;
                CALL G('ABC'); F2 = FLAG;
                PUT SKIP LIST(F1, F2);
                GN: PROC(X); DCL X FIXED BIN(31); FLAG = X; END GN;
                GC: PROC(X); DCL X CHAR(*); FLAG = LENGTH(X); END GC;
                """).strip().replaceAll("\\s+", " "));
        assertTrue(rejection("""
                DCL G GENERIC(C WHEN(CHAR));
                CALL G(7);
                C: PROC(X); DCL X FIXED BIN(31); END C;
                """).contains("no GENERIC alternative"));
    }

    @Test
    @DisplayName("名は手続きを宣言した場所でたどる。呼んだ側の同じ名の変数は見えない")
    void namesResolveWhereTheProcedureIsDeclared() throws Exception {
        assertEquals("1,2,1", run("""
                DCL X FIXED BIN(31) INIT(1);
                CALL OUTER;
                PUT SKIP LIST(X);
                OUTER: PROC;
                  DCL X FIXED BIN(31) INIT(2);
                  CALL SHOW;
                  CALL INNER;
                  INNER: PROC;
                    PUT SKIP LIST(X);
                  END INNER;
                END OUTER;
                SHOW: PROC;
                  PUT SKIP LIST(X);
                END SHOW;
                """));
    }

    @Test
    @DisplayName("on-unit は呼んだ側の鎖でたどる。呼んだ手続きが確立した ON は、呼ばれた先の条件を受ける")
    void onUnitsFollowTheCallChain() throws Exception {
        assertEquals("CAUGHT,1", run("""
                DCL X FIXED BIN(31) INIT(1);
                CALL OUTER;
                OUTER: PROC;
                  DCL X FIXED BIN(31) INIT(2);
                  ON CONDITION(BAD) PUT SKIP LIST('CAUGHT');
                  CALL RAISE;
                END OUTER;
                RAISE: PROC;
                  SIGNAL CONDITION(BAD);
                  PUT SKIP LIST(X);
                END RAISE;
                """));
    }
}
