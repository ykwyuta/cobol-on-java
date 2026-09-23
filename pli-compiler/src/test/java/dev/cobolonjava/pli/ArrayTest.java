package dev.cobolonjava.pli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 構造の外の配列 (Enterprise PL/I Language Reference, "DIMENSION attribute"、"Array
 * assignments") と、擬似変数 SUBSTR。
 *
 * <p>以前は次元を読み飛ばしていたので、{@code DCL A(10) FIXED BIN} は 1 つの変数になり、
 * {@code A(I) = ...} は「文を知らない」で断られていた。z/OS probe の PLIMAP の下書きで、
 * 16 進の文字を配列へ並べようとして見つかった。
 */
class ArrayTest {

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(ArrayTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String main(String body) throws Exception {
        PliCompiler.Result result = PliCompiler.standard().compile("ARRAYS.pli",
                "ARRAYS: PROCEDURE OPTIONS(MAIN);\n" + body + "\nEND ARRAYS;\n");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        CobolProgram program = (CobolProgram) new GeneratedLoader()
                .define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        program.runFresh(ProgramContext.capturing(output));
        return output.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "\n");
    }

    private static String rejection(String body) {
        PliCompiler.Result result = PliCompiler.standard().compile("ARRAYS.pli",
                "ARRAYS: PROCEDURE OPTIONS(MAIN);\n" + body + "\nEND ARRAYS;\n");
        assertFalse(result.succeeded(), "expected the compiler to refuse: " + body);
        return result.diagnostics().toString();
    }

    @Test
    @DisplayName("配列は要素ごとに記憶域を持ち、INIT の並びを順に入れる")
    void initialValuesFillTheElementsInOrder() throws Exception {
        assertEquals("1 2 3 SIZE=6\n", main("""
                DCL A(3) FIXED BIN(15) INIT(1, 2, 3);
                PUT SKIP EDIT(A(1), ' ', A(2), ' ', A(3), ' SIZE=', SIZE(A))
                             (F(1), A, F(1), A, F(1), A, F(1));
                """));
    }

    @Test
    @DisplayName("INIT の反復の係数 (n) と (*)")
    void iterationFactors() throws Exception {
        assertEquals("7700\n", main("""
                DCL A(4) FIXED BIN(15) INIT((2)7, (*)0);
                PUT SKIP EDIT(A)(F(1));
                """));
    }

    @Test
    @DisplayName("多次元の配列は最後の添字がいちばん速く変わる。下限も書ける")
    void rowMajorOrderAndLowerBounds() throws Exception {
        assertEquals("ABCD D\n", main("""
                DCL M(0:1, 2) CHAR(1) INIT('A', 'B', 'C', 'D');
                PUT SKIP EDIT(M, ' ', M(1, 2))(A, A, A, A, A, A);
                """));
    }

    @Test
    @DisplayName("要素へ代入でき、スカラーを配列へ代入するとすべての要素に入る")
    void elementAndWholeArrayAssignment() throws Exception {
        assertEquals("999\n2 4 6\n", main("""
                DCL A(3) FIXED BIN(15);
                DCL I FIXED BIN(15);
                A = 9;
                PUT SKIP EDIT(A)(F(1));
                DO I = 1 TO 3;
                  A(I) = I * 2;
                END;
                PUT SKIP EDIT(A(1), ' ', A(2), ' ', A(3))(F(1), A, F(1), A, F(1));
                """));
    }

    @Test
    @DisplayName("BASED の配列は重ねた先の記憶域を要素に分けて見る")
    void basedArraysOverlayTheirBase() throws Exception {
        assertEquals("Y QXYZ\n", main("""
                DCL S CHAR(4) INIT('WXYZ');
                DCL HX(4) CHAR(1) BASED(ADDR(S));
                DCL H3 CHAR(1);
                H3 = HX(3);
                HX(1) = 'Q';
                PUT SKIP EDIT(H3, ' ', S)(A, A, A);
                """));
    }

    @Test
    @DisplayName("添字が上下限の外なら止める (SUBSCRIPTRANGE)")
    void subscriptOutOfRangeFails() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> main("""
                DCL A(3) FIXED BIN(15);
                A(4) = 1;
                """));
        assertTrue(String.valueOf(failure.getMessage()).contains("SUBSCRIPTRANGE"),
                failure::getMessage);
    }

    @Test
    @DisplayName("擬似変数 SUBSTR は文字列の一部を置き換え、長さを変えない")
    void substrPseudovariable() throws Exception {
        assertEquals("AXYDE|AZ   |\n", main("""
                DCL S CHAR(5) INIT('ABCDE');
                DCL T CHAR(5) INIT('ABCDE');
                SUBSTR(S, 2, 2) = 'XY';
                SUBSTR(T, 2) = 'Z';
                PUT SKIP EDIT(S, '|', T, '|')(A, A, A, A);
                """));
    }

    @Test
    @DisplayName("構造の中の配列は、構造の中の場所に要素を並べる")
    void arraysInsideAStructure() throws Exception {
        // K (2 byte) と T (1 byte) の対は N (半語) の方へ 1 byte ずれ、隙間は構造の外へ出る。
        // だから大きさは 8 ではなく 7 (1 + 2 + 4)
        assertEquals("AB C 7 9 SIZE=7\n", main("""
                DCL 1 S,
                      2 K(2) CHAR(1) INIT('A', 'B'),
                      2 T CHAR(1) INIT('C'),
                      2 N(2) FIXED BIN(15);
                N(1) = 7;
                S.N(2) = 9;
                PUT SKIP EDIT(K(1), K(2), ' ', T, ' ', N(1), ' ', N(2), ' SIZE=', SIZE(S))
                             (A, A, A, A, A, F(1), A, F(1), A, F(1));
                """));
    }

    @Test
    @DisplayName("ALIGNED の要素の配列は、要素の境界に合わせて並ぶ")
    void alignedArrayElementsKeepTheirBoundary() throws Exception {
        // C は 1 byte、N は全語の境界。対の規則で C は N の直前へずれるので、構造は 1 + 4 x 2 = 9 byte
        assertEquals("9\n", main("""
                DCL 1 S, 2 C CHAR(1), 2 N(2) FIXED BIN(31);
                PUT SKIP EDIT(SIZE(S))(F(1));
                """));
    }

    @Test
    @DisplayName("ビット列の配列は UNALIGNED ならビット単位で詰まる")
    void bitArraysArePacked() throws Exception {
        assertEquals("101 SIZE=1 X=A0\n", main("""
                DCL B(3) BIT(1) INIT('1'B, '0'B, '1'B);
                DCL X CHAR(1) BASED(ADDR(B));
                DCL S CHAR(9) VARYING INIT('');
                IF B(1) THEN S = '1'; ELSE S = '0';
                IF B(2) THEN S = S || '1'; ELSE S = S || '0';
                IF B(3) THEN S = S || '1'; ELSE S = S || '0';
                IF X = 'A0'X THEN S = S || ' SIZE=';
                PUT SKIP EDIT(S, SIZE(B), ' X=A0')(A, F(1), A);
                """));
    }

    @Test
    @DisplayName("配列の式は、配列への代入の右辺で要素ごとに評価する")
    void arrayExpressionsAreEvaluatedElementWise() throws Exception {
        assertEquals("11 22 33\n", main("""
                DCL A(3) FIXED BIN(15) INIT(1, 2, 3);
                DCL B(3) FIXED BIN(15) INIT(10, 20, 30);
                DCL C(3) FIXED BIN(15);
                C = A + B;
                PUT SKIP EDIT(C(1), ' ', C(2), ' ', C(3))(F(2), A, F(2), A, F(2));
                """));
    }

    @Test
    @DisplayName("まだ持たない配列は、次元を黙って捨てずに断る")
    void unsupportedArraysAreRefused() {
        assertTrue(rejection("DCL 1 S(3), 2 A CHAR(1);").contains("arrays of structures"));
        assertTrue(rejection("DCL 1 S, 2 R(3), 3 A CHAR(1);").contains("arrays of structures"));
        assertTrue(rejection("DCL C(*) CHAR(1);").contains("bounds"));
        RuntimeException expression = assertThrows(RuntimeException.class, () -> main("""
                DCL A(2) FIXED BIN(15);
                DCL B FIXED BIN(15);
                B = A + 1;
                """));
        assertTrue(String.valueOf(expression.getMessage()).contains("assigned to an array"),
                expression::getMessage);
    }
}
