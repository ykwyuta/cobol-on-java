package dev.cobolonjava.pli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 構造・POINTER・BASED・PICTURE (Enterprise PL/I Language Reference)。IBLOGIN を動かして出た食い違いを、
 * 1 つずつ区別できる形で固定する。
 */
class StructureAndPictureTest {

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(StructureAndPictureTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static PliCompiler.Result compile(String header, String body) {
        return PliCompiler.standard().compile("S.pli",
                "S: PROCEDURE" + header + " OPTIONS(MAIN);\n" + body + "\nEND S;\n");
    }

    private static String run(String body) throws Exception {
        return run("", body, new Storage[0]);
    }

    /** 本体を動かし、出力の各行を ',' でつなぐ (右の空白は落とす)。 */
    private static String run(String header, String body, Storage... arguments) throws Exception {
        PliCompiler.Result result = compile(header, body);
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        CobolProgram program = (CobolProgram) new GeneratedLoader()
                .define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        dev.cobolonjava.runtime.storage.DataView[] views =
                new dev.cobolonjava.runtime.storage.DataView[arguments.length];
        for (int i = 0; i < arguments.length; i++) views[i] = arguments[i].whole();
        program.runFresh(ProgramContext.capturing(output), views);
        return String.join(",", output.toString(StandardCharsets.UTF_8).lines()
                .map(String::stripTrailing).toList());
    }

    @Test
    @DisplayName("構造は要素の数だけの項目として書く。2 進の要素は数として幅を持つ")
    void aStructureIsWrittenElementByElement() throws Exception {
        assertEquals(String.format("%-24s%-24s%9s", "AB", "C", "7"), run("""
                DCL 1 R,
                  5 A CHAR(2) INIT('AB'),
                  5 B CHAR(1) INIT('C'),
                  5 N FIXED BIN(15) INIT(7);
                PUT SKIP LIST(R);
                """));
    }

    @Test
    @DisplayName("構造へ単一の値を代入すると要素ごとに変換する。数は 0、文字は '   0'")
    void assigningAScalarToAStructureIsDoneElementByElement() throws Exception {
        assertEquals(String.format("%9s,%s|", "0", "   0 "), run("""
                DCL 1 R,
                  5 N FIXED BIN(15) INIT(5),
                  5 C CHAR(5) INIT('XXXXX');
                R = 0;
                PUT SKIP LIST(N);
                PUT SKIP EDIT(C, '|') (A, A);
                """));
    }

    @Test
    @DisplayName("POINTER は HEX の 8 桁で書く")
    void aPointerIsWrittenInHex() throws Exception {
        String line = run("(P)", """
                DCL P POINTER;
                PUT SKIP LIST(P);
                """, Storage.allocate(8));
        assertTrue(line.matches("[0-9A-F]{8}"), line);
    }

    @Test
    @DisplayName("BASED の宣言は重ねた先を書き換えない。INITIAL は ALLOCATE で記憶域を取ったときだけ効く")
    void basedDeclarationsDoNotInitializeTheirTarget() throws Exception {
        Storage pcb = Storage.allocate(8);
        pcb.whole().setBytes(dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.encode("CUSTOMER"));
        assertEquals("CUSTOMER", run("(P)", """
                DCL P POINTER;
                DCL 1 MASK BASED(P),
                  5 NAME CHAR(8);
                PUT SKIP LIST(NAME);
                """, pcb));
    }

    @Test
    @DisplayName("PIC'(9)9' は 9 桁の数。字を入れると数に直してから 0 で埋めた 9 桁にする")
    void aNumericPictureConvertsWhatIsAssigned() throws Exception {
        assertEquals("000016918,16919", run("""
                DCL P PIC'(9)9';
                DCL N FIXED BIN(31) INIT(0);
                P = ' 16918   ';
                PUT SKIP LIST(P);
                N = P + 1;
                PUT SKIP LIST(TRIM(CHAR(N)));
                """));
    }

    @Test
    @DisplayName("V は小数点の位置を示すだけで場所を取らない。PIC'999V99' の 12.3 は 01230")
    void theVInAPictureMarksAnImpliedPoint() throws Exception {
        assertEquals("01230,12.30", run("""
                DCL P PIC'999V99';
                P = 12.3;
                PUT SKIP LIST(P);
                PUT SKIP LIST(TRIM(CHAR(P + 0)));
                """));
    }

    @Test
    @DisplayName("編集の字を持つ PICTURE はまだ持たないので翻訳で断る")
    void editingPicturesAreRefused() {
        PliCompiler.Result result = compile("", "DCL P PIC'ZZ9.99';");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("PICTURE"));
    }

    @Test
    @DisplayName("空白だけの字を数にすると 0 になる (CONVERSION は起きない)")
    void blanksConvertToZero() throws Exception {
        assertEquals(String.format("%14s", "1"), run("""
                DCL C CHAR(3) INIT('   ');
                DCL N FIXED BIN(31) INIT(1);
                N = N + C;
                PUT SKIP LIST(N);
                """));
    }
}
