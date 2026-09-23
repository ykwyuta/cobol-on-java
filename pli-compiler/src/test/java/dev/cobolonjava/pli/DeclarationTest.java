package dev.cobolonjava.pli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 宣言まわりの規則 (Enterprise PL/I Language Reference, "DECLARE statement"、"INITIAL
 * attribute"、"BASED attribute")。
 *
 * <p>どれも z/OS probe (tools/zos-probe/pli) を実機で正しい書き方で書き、この処理系で流して
 * 見つかったものである。以前の実装では、どの試験も通らないか、黙って違う値を返していた。
 */
class DeclarationTest {

    @TempDir
    Path temporary;

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(DeclarationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    /** 原文を翻訳して動かし、SYSPRINT を行の並び ('\n' 区切り) で返す。 */
    private static String run(String source) throws Exception {
        PliCompiler.Result result = PliCompiler.standard().compile("DECLS.pli", source);
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        CobolProgram program = (CobolProgram) new GeneratedLoader()
                .define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        program.runFresh(ProgramContext.capturing(output));
        return output.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n");
    }

    private static String main(String body) throws Exception {
        return run("DECLS: PROCEDURE OPTIONS(MAIN);\n" + body + "\nEND DECLS;\n");
    }

    @Test
    @DisplayName("INIT の負の数は負のまま入る (以前は 0 になっていた)")
    void negativeInitialValues() throws Exception {
        // FIXED BIN(31) は幅 14、FIXED DEC(5) は幅 8 の欄に右寄せ
        assertEquals(String.format("%14s", "-12345") + "\n" + String.format("%8s", "-7") + "\n",
                main("""
                        DCL B FIXED BIN(31) INIT(-12345);
                        DCL D FIXED DEC(5) INIT(-7);
                        PUT SKIP LIST(B);
                        PUT SKIP LIST(D);
                        """));
    }

    @Test
    @DisplayName("INIT には式を書ける。後に宣言した変数の ADDR でもよい")
    void initialValuesAreExpressions() throws Exception {
        assertEquals("XYZ\n", main("""
                DCL P POINTER INIT(ADDR(S));
                DCL V CHAR(3) BASED(P);
                DCL S CHAR(3) INIT('XYZ');
                PUT SKIP LIST(V);
                """));
    }

    @Test
    @DisplayName("PROC は PROCEDURE の略である")
    void procIsAnAbbreviation() throws Exception {
        assertEquals("IN\n", run("""
                DECLS: PROC OPTIONS(MAIN);
                  CALL INNER;
                  INNER: PROC;
                    PUT SKIP LIST('IN');
                  END INNER;
                END DECLS;
                """));
    }

    @Test
    @DisplayName("宣言は書いた場所によらず手続きに入ったときに確立する")
    void declarationsDoNotDependOnTheirOrder() throws Exception {
        assertEquals("AB\n", main("""
                DCL V CHAR(2) BASED(ADDR(S));
                S = 'AB';
                PUT SKIP LIST(V);
                DCL S CHAR(2);
                """));
    }

    @Test
    @DisplayName("NULL の POINTER に重ねた宣言は、POINTER に値が入ってから使える")
    void basedOnANullPointerIsBoundLater() throws Exception {
        assertEquals("MINE\n", main("""
                DCL P POINTER;
                DCL V CHAR(4) BASED(P);
                DCL MINE CHAR(4) INIT('MINE');
                P = ADDR(MINE);
                PUT SKIP LIST(V);
                """));
    }

    @Test
    @DisplayName("NULL の POINTER に重ねた変数を参照すると止まる")
    void referencingAnUnboundBasedVariableFails() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> main("""
                DCL P POINTER;
                DCL V CHAR(4) BASED(P);
                PUT SKIP LIST(V);
                """));
        assertTrue(String.valueOf(failure.getMessage()).contains("null pointer"),
                failure::getMessage);
    }

    @Test
    @DisplayName("16 進の文字の定数は書いた byte のまま入る")
    void hexadecimalCharacterConstants() throws Exception {
        // EBCDIC の C1 C2 は A B
        assertEquals("AB\n", main("""
                DCL C CHAR(2) INIT('C1C2'X);
                PUT SKIP LIST(C);
                """));
        PliCompiler.Result odd = PliCompiler.standard().compile("DECLS.pli",
                "DECLS: PROCEDURE OPTIONS(MAIN); DCL C CHAR(1) INIT('C1C'X); END DECLS;");
        assertFalse(odd.succeeded());
    }

    @Test
    @DisplayName("STG は SIZE、LENGTH は文字列の長さ、LOW / HIGH は X'00' / X'FF' の並び")
    void storageLengthLowAndHigh() throws Exception {
        assertEquals("S 4\nL 5\nZ 0\nF -1\n", main("""
                DCL S CHAR(4);
                DCL T CHAR(5);
                DCL W FIXED BIN(15);
                DCL WC CHAR(2) BASED(ADDR(W));
                PUT SKIP EDIT('S ', STG(S))(A, F(1));
                PUT SKIP EDIT('L ', LENGTH(T))(A, F(1));
                WC = LOW(2);
                PUT SKIP EDIT('Z ', W)(A, F(1));
                WC = HIGH(2);
                PUT SKIP EDIT('F ', W)(A, F(2));
                """));
    }

    /**
     * 以前は VARYING を読み飛ばしていたので、OUT は固定長 64 字のままで、OUT || 'AB' の結果の
     * 頭 64 字 (空白) が入り続けていた。z/OS probe の PLIMAP の下書きで見つかった。
     */
    @Test
    @DisplayName("VARYING は代入した値の長さを持ち、連結で伸びる")
    void varyingStringsKeepTheirCurrentLength() throws Exception {
        assertEquals("[ABABAB] 6\n", main("""
                DCL OUT CHAR(64) VARYING;
                DCL I FIXED BIN(15);
                OUT = '';
                DO I = 1 TO 3;
                  OUT = OUT || 'AB';
                END;
                PUT SKIP EDIT('[', OUT, '] ', LENGTH(OUT))(A, A, A, F(1));
                """));
    }

    @Test
    @DisplayName("VARYING の記憶域は長さの半語と最大の長さ。最大を超えた右は落ちる")
    void varyingStorageAndTruncation() throws Exception {
        assertEquals("[ABC] 3 5\n", main("""
                DCL V CHAR(3) VAR INIT('ABCDE');
                DCL H FIXED BIN(15) BASED(ADDR(V));
                PUT SKIP EDIT('[', V, '] ', H, ' ', STG(V))(A, A, A, F(1), A, F(1));
                """));
    }

    @Test
    @DisplayName("BIT VARYING はまだ持たないので断る")
    void bitVaryingIsRefused() {
        PliCompiler.Result result = PliCompiler.standard().compile("DECLS.pli",
                "DECLS: PROCEDURE OPTIONS(MAIN); DCL B BIT(8) VARYING; END DECLS;");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("VARYING"));
    }

    /**
     * 呼んだ先が POINTER の引数を書き換えたら、呼んだ側の BASED の変数は新しい先を見る
     * (P-185 の 3 点目。z/OS probe の PLIPTR / PLIPTRS と同じ形)。
     */
    @Test
    @DisplayName("呼んだ先が書き換えた POINTER に、呼んだ側の BASED が重なり直す")
    void basedVariablesFollowAPointerChangedByTheCallee() throws Exception {
        Path caller = Files.writeString(temporary.resolve("PLIPTR.pli"), """
                PLIPTR: PROCEDURE OPTIONS(MAIN);
                  DCL PLIPTRS ENTRY(POINTER) EXTERNAL;
                  DCL MINE CHAR(4) INIT('MINE');
                  DCL P POINTER;
                  DCL V CHAR(4) BASED(P);
                  P = ADDR(MINE);
                  PUT SKIP LIST(V);
                  CALL PLIPTRS(P);
                  PUT SKIP LIST(V);
                END PLIPTR;
                """);
        Path callee = Files.writeString(temporary.resolve("PLIPTRS.pli"), """
                PLIPTRS: PROCEDURE(Q);
                  DCL Q POINTER;
                  DCL THEIRS CHAR(4) STATIC INIT('SUBV');
                  Q = ADDR(THEIRS);
                END PLIPTRS;
                """);
        Path output = temporary.resolve("out");
        Main.main(new String[] {"-d", output.toString(), caller.toString(), callee.toString()});
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {
                output.toUri().toURL()}, DeclarationTest.class.getClassLoader())) {
            CobolProgram program = (CobolProgram) loader.loadClass("cobol.generated.PLIPTR")
                    .getDeclaredConstructor().newInstance();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            program.runFresh(ProgramContext.capturing(captured));
            assertEquals("MINE\nSUBV\n", captured.toString(StandardCharsets.UTF_8)
                    .replace(System.lineSeparator(), "\n"));
        }
    }
}
