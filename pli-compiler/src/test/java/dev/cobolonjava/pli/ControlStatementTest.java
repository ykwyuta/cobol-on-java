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
 * do-group と RETURN、知らない文の扱い (Enterprise PL/I Language Reference, "DO statement")。
 *
 * <p>どの試験も、以前の実装 (DO; を条件の無い繰り返しにし、UNTIL を前で調べ、2 つ目の
 * 反復指定と RETURN の値を読み飛ばし、知らない文を翻訳で通していた) では通らない。
 */
class ControlStatementTest {

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(ControlStatementTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    /** 本体を主手続きに包んで動かし、出力の各行の空白を落として ',' でつなぐ。 */
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

    private static PliCompiler.Result compile(String body) {
        return PliCompiler.standard().compile("CTL.pli",
                "CTL: PROCEDURE OPTIONS(MAIN);\n" + body + "\nEND CTL;\n");
    }

    private static String rejection(String body) {
        PliCompiler.Result result = compile(body);
        assertFalse(result.succeeded(), "expected the compiler to refuse: " + body);
        return result.diagnostics().toString();
    }

    @Test
    @DisplayName("DO; は繰り返さず 1 度だけ実行する (Type 1)")
    void aSimpleDoGroupRunsOnce() throws Exception {
        assertEquals("1,2", run("""
                DCL N FIXED BIN(31) INIT(0);
                DO;
                  N = N + 1;
                END;
                PUT SKIP LIST(N);
                IF N = 1 THEN DO;
                  N = N + 1;
                END;
                ELSE DO;
                  N = 99;
                END;
                PUT SKIP LIST(N);
                """));
    }

    @Test
    @DisplayName("UNTIL は繰り返した後に調べるので、最初から真でも 1 度は動く")
    void untilIsTestedAfterEachRepetition() throws Exception {
        assertEquals("6", run("""
                DCL N FIXED BIN(31) INIT(5);
                DO UNTIL(N > 0);
                  N = N + 1;
                END;
                PUT SKIP LIST(N);
                """));
    }

    @Test
    @DisplayName("WHILE は繰り返す前に調べるので、最初から偽なら 1 度も動かない")
    void whileIsTestedBeforeEachRepetition() throws Exception {
        assertEquals("5", run("""
                DCL N FIXED BIN(31) INIT(5);
                DO WHILE(N < 0);
                  N = N + 1;
                END;
                PUT SKIP LIST(N);
                """));
    }

    @Test
    @DisplayName("WHILE と UNTIL は両方書け、順も問わない")
    void whileAndUntilCombine() throws Exception {
        assertEquals("3,3", run("""
                DCL N FIXED BIN(31) INIT(0);
                DO WHILE(N < 10) UNTIL(N = 3);
                  N = N + 1;
                END;
                PUT SKIP LIST(N);
                N = 0;
                DO UNTIL(N = 3) WHILE(N < 10);
                  N = N + 1;
                END;
                PUT SKIP LIST(N);
                """));
    }

    @Test
    @DisplayName("DO LOOP は無限の繰り返しで、GO TO で抜ける")
    void doLoopRepeatsUntilLeft() throws Exception {
        assertEquals("4", run("""
                DCL N FIXED BIN(31) INIT(0);
                DO LOOP;
                  N = N + 1;
                  IF N = 4 THEN GO TO DONE;
                END;
                DONE:
                PUT SKIP LIST(N);
                """));
    }

    @Test
    @DisplayName("%PAGE と %SKIP は翻訳の listing だけに効き、実行には何も起こさない")
    void listingDirectivesDoNothingAtRunTime() throws Exception {
        assertEquals("X", run("""
                %PAGE;
                %SKIP(2);
                PUT SKIP LIST('X');
                """));
    }

    @Test
    @DisplayName("値を持つ RETURN、知らない ON・EXEC・文は、実行まで待たずに翻訳で断る")
    void unsupportedStatementsAreRefusedAtCompileTime() {
        assertTrue(rejection("RETURN(5);").contains("RETURN with a value"));
        assertTrue(rejection("ON ERROR BEGIN; END;").contains("ON ERROR"));
        assertTrue(rejection("EXEC CICS RETURN;").contains("EXEC CICS"));
        assertTrue(rejection("""
                DCL A CHAR(3);
                GET LIST(A);
                """).contains("statement GET"));
        assertTrue(rejection("""
                DCL I FIXED BIN(31);
                DO I = 1 TO 5 WHILE(I < 3);
                END;
                """).contains("CTL.pli"));
    }
}
