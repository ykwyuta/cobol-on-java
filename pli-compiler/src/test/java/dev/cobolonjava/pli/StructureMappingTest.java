package dev.cobolonjava.pli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
 * 構造の配置 (LRM "Structure mapping")。どの試験も、要素を隙間なく並べる以前の実装とも、
 * C のように要素の前へ詰め物を足す配置とも区別できる形にしてある。
 */
class StructureMappingTest {

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(StructureMappingTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static PliCompiler.Result compile(String body) {
        return PliCompiler.standard().compile("M.pli",
                "M: PROCEDURE OPTIONS(MAIN);\n" + body + "\nEND M;\n");
    }

    /** 本体を動かし、出力の各行を左右の空白を落として ',' でつなぐ。 */
    private static String run(String body) throws Exception {
        PliCompiler.Result result = compile(body);
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        CobolProgram program = (CobolProgram) new GeneratedLoader()
                .define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        program.runFresh(ProgramContext.capturing(output));
        return String.join(",", output.toString(StandardCharsets.UTF_8).lines()
                .map(String::strip).toList());
    }

    private static int[] bytes(StructureMapping.Result mapping) {
        int[] offsets = new int[mapping.bitOffsets().length];
        for (int i = 0; i < offsets.length; i++) offsets[i] = mapping.byteOffset(i);
        return offsets;
    }

    private static StructureMapping.Result map(String declaration) {
        PliSyntax.ParseResult parsed = PliSyntax.parse("M.pli",
                "M: PROCEDURE OPTIONS(MAIN);\nDCL " + declaration + ";\nEND M;\n");
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        PliSyntax.Declare declare = (PliSyntax.Declare) parsed.program().body().get(0);
        return StructureMapping.map(declare.declarations());
    }

    @Test
    @DisplayName("1 つ目を 2 つ目の方へずらすので、CHAR(1) と FIXED BIN(31) の構造は 8 でなく 5 byte")
    void padningGoesBeforeTheStructure() {
        StructureMapping.Result mapping = map("1 S, 2 C CHAR(1), 2 B FIXED BIN(31)");

        assertEquals(5, mapping.size());
        assertArrayEquals(new int[] {0, 0, 1}, bytes(mapping));
    }

    @Test
    @DisplayName("境界に届かない隙間は構造の中に残る。BIN(31), CHAR(1), BIN(15) は 0, 4, 6 で 8 byte")
    void paddingThatCannotMoveStaysInside() {
        StructureMapping.Result mapping = map(
                "1 S, 2 W FIXED BIN(31), 2 C CHAR(1), 2 H FIXED BIN(15)");

        assertEquals(8, mapping.size());
        assertArrayEquals(new int[] {0, 0, 4, 6}, bytes(mapping));
    }

    @Test
    @DisplayName("小構造は先に配置し、倍語の境界からのずれを保ったまま外の構造に加わる")
    void aMinorStructureKeepsItsOffset() {
        // M は X, Y で 5 byte、境界からのずれ 3。C の後に 1 byte で続けられるので隙間は出ない
        StructureMapping.Result mapping = map(
                "1 S, 2 C CHAR(1), 2 M, 3 X CHAR(1), 3 Y FIXED BIN(31)");

        assertEquals(6, mapping.size());
        assertArrayEquals(new int[] {0, 0, 1, 1, 2}, bytes(mapping));
        assertEquals(5, mapping.byteLength(2));
    }

    @Test
    @DisplayName("UNALIGNED は境界合わせを 1 byte にし、要素へ受け継がれる")
    void unalignedPacksTheMembers() {
        assertEquals(8, map("1 S, 2 H FIXED BIN(15), 2 C CHAR(1), 2 W FIXED BIN(31)").size());
        assertEquals(7, map("1 S UNALIGNED, 2 H FIXED BIN(15), 2 C CHAR(1), 2 W FIXED BIN(31)")
                .size());
    }

    @Test
    @DisplayName("FIXED BIN(7) は 1 byte、BIN(63) は 8 byte")
    void binarySizesFollowThePrecision() {
        assertEquals(2, map("1 S, 2 T FIXED BIN(7), 2 C CHAR(1)").size());
        assertEquals(16, map("1 S, 2 C CHAR(1), 2 L FIXED BIN(63)").size() + 7);
    }

    @Test
    @DisplayName("実行時も同じ配置で置く。重ねた CHAR から見ると、D は 6 byte 目にある")
    void theRuntimeUsesTheSameMapping() throws Exception {
        // C の後に 3 byte の詰め物を置く C 流なら D は 9 byte 目、隙間なく並べれば 6 byte 目
        // だが B も 2〜5 byte 目になる。PL/I では C が 1、B が 2〜5、D が 6 で、大きさは 6
        assertEquals("6,A,Z", run("""
                DCL 1 S,
                  2 C CHAR(1) INIT('A'),
                  2 B FIXED BIN(31) INIT(0),
                  2 D CHAR(1) INIT('Z');
                DCL RAW CHAR(6) BASED(ADDR(S));
                PUT SKIP LIST(SIZE(S));
                PUT SKIP LIST(SUBSTR(RAW, 1, 1));
                PUT SKIP LIST(SUBSTR(RAW, 6, 1));
                """));
    }

    @Test
    @DisplayName("位取りのある FIXED BIN は 2 の q 乗を掛けた整数で置き、読むときに割り戻す")
    void scaledBinaryKeepsItsFraction() throws Exception {
        // BIN(15,2) を文字にすると 10 進の精度 (6,1)。1.25 は 1.2 と書かれる
        assertEquals("1.2", run("""
                DCL Q FIXED BIN(15,2);
                Q = 1.25;
                PUT SKIP LIST(Q);
                """));
    }

    @Test
    @DisplayName("ビット列は 8 ビットごとに 1 byte で置き、2 ビット以上の定数も値のまま入る")
    void bitStringsAreStoredAsBits() throws Exception {
        assertEquals("'10100000'B,1,160", run("""
                DCL B BIT(8) INIT('10100000'B);
                DCL 1 S, 2 X BIT(8);
                DCL N FIXED BIN(31);
                PUT SKIP LIST(B);
                PUT SKIP LIST(SIZE(S));
                N = B;
                PUT SKIP LIST(N);
                """));
    }

    @Test
    @DisplayName("'00'B は偽である。どれか 1 ビットが 1 のときだけ真")
    void aBitStringOfZerosIsFalse() throws Exception {
        assertEquals("F", run("""
                DCL B BIT(2) INIT('00'B);
                IF B THEN PUT SKIP LIST('T'); ELSE PUT SKIP LIST('F');
                """));
    }

    @Test
    @DisplayName("UNALIGNED のビット列は前後とビット単位で詰まる。BIT(1) が 3 つで 1 byte")
    void unalignedBitsShareBytes() throws Exception {
        StructureMapping.Result mapping = map("1 S, 2 A BIT(1), 2 B BIT(1), 2 C BIT(1), 2 D CHAR(1)");

        // 3 ビットは D の方へずれ、最初の byte の 5〜7 ビット目に入る (頭の 5 ビットは空き)
        assertEquals(2, mapping.size());
        assertArrayEquals(new int[] {0, 5, 6, 7, 8}, mapping.bitOffsets());
        // 1 つを書いても、同じ byte の隣のビットは変わらない
        assertEquals("'1'B,'0'B,'1'B,1", run("""
                DCL 1 S, 2 A BIT(1), 2 B BIT(1), 2 C BIT(1), 2 D CHAR(1);
                A = '1'B;
                C = '1'B;
                B = '0'B;
                PUT SKIP LIST(A);
                PUT SKIP LIST(B);
                PUT SKIP LIST(C);
                PUT SKIP LIST(SIZE(S) - 1);
                """));
    }

    @Test
    @DisplayName("2 ビット以上のビット列の & / | / ^ はビットごとに演算する。短いほうは右に 0 を足す")
    void bitOperationsWorkBitByBit() throws Exception {
        assertEquals("'1000'B,'1110'B,'0101'B", run("""
                DCL A BIT(4) INIT('1010'B);
                DCL B BIT(4) INIT('1100'B);
                PUT SKIP LIST(A & B);
                PUT SKIP LIST(A | B);
                PUT SKIP LIST(^A);
                """));
    }

    @Test
    @DisplayName("POINTER は 4 byte の値を持つ。ADDR を入れ、BASED は代入のたびに重ね直す")
    void pointersHoldAnAddressAndBasedFollowsThem() throws Exception {
        assertEquals("FIRST,SECOND,1", run("""
                DCL A CHAR(6) INIT('FIRST');
                DCL B CHAR(6) INIT('SECOND');
                DCL P POINTER;
                DCL Q POINTER;
                P = ADDR(A);
                DCL V CHAR(6) BASED(P);
                PUT SKIP LIST(V);
                P = ADDR(B);
                PUT SKIP LIST(V);
                Q = P;
                IF Q = ADDR(B) THEN PUT SKIP LIST(1);
                """));
    }

    @Test
    @DisplayName("構造の中の POINTER は 4 byte で全語の境界に置く")
    void aPointerInAStructureTakesAFullword() {
        StructureMapping.Result mapping = map("1 S, 2 C CHAR(1), 2 P POINTER, 2 D CHAR(3)");

        assertEquals(8, mapping.size());
        assertArrayEquals(new int[] {0, 0, 1, 5}, bytes(mapping));
    }
}
