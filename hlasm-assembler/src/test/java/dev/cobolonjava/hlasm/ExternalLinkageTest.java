package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 番地定数の直し (再配置) と、{@code V(名前)} で引いた制御節を同じ番地空間に載せて呼ぶこと。
 *
 * <p>根拠は HLASM Language Reference の "Address constants" (A は節の中の番地、V は外部の名前の番地で
 * 載せる側が値を決める) と、標準リンケージ (R1 が引数表、R14 が戻り番地、R15 が入口)。
 */
class ExternalLinkageTest {

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(ExternalLinkageTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    /** 原文を全部翻訳して同じ読み込み器へ入れ、1 本目を返す。 */
    private static CobolProgram load(String... sources) throws ReflectiveOperationException {
        GeneratedLoader loader = new GeneratedLoader();
        CobolProgram first = null;
        for (String source : sources) {
            HlasmCompiler.Result result = HlasmCompiler.standard().compile("X.asm", source);
            assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
            Class<?> type = loader.define(result.className(), result.classFile());
            if (first == null) {
                first = (CobolProgram) type.getDeclaredConstructor().newInstance();
            }
        }
        return first;
    }

    private static String lines(String... lines) {
        return String.join("\n", lines);
    }

    private static ProgramContext context() {
        return ProgramContext.capturing(new ByteArrayOutputStream());
    }

    @Test
    @DisplayName("A(記号) は載せた番地を指す。変位のままなら番地として読めない")
    void relocatesAddressConstants() throws ReflectiveOperationException {
        CobolProgram program = load(lines(
                "MAINA    CSECT",
                "         USING MAINA,15",
                "         L     2,0(0,1)",
                "         L     3,ADDR",
                "         MVC   0(4,2),0(3)",
                "         SR    15,15",
                "         BR    14",
                "ADDR     DC    A(VALUE)",
                "VALUE    DC    C'OKAY'",
                "         END"));
        Storage caller = Storage.allocate(4);
        program.run(Storage.wrap(program.initialStorage()), context(), new DataView[] {caller.whole()});
        assertArrayEquals("OKAY".getBytes(dev.cobolonjava.runtime.codepage.CodePages.DEFAULT.charset()),
                caller.array());
    }

    /**
     * 呼ばれた側は呼ぶ側の作業場所を引数表から読み書きし、BASR の戻り番地へ戻る。2 度呼ぶと、
     * 呼ばれた側の節の中の数え上げは 1 回目の値から続く (同じ番地空間に 1 度だけ載せている)。
     */
    @Test
    @DisplayName("V(名前) で呼んだ HLASM の副プログラムは同じ番地空間で動き、戻ってくる")
    void callsAnExternalRoutineThroughAVTypeConstant() throws ReflectiveOperationException {
        CobolProgram program = load(lines(
                "CALLER   CSECT",
                "         USING CALLER,12",
                "         LR    12,15",
                "         ST    14,SAVE14",
                "         L     9,0(0,1)",
                "         LA    1,PLIST",
                "         L     15,=V(COUNTER)",
                "         BASR  14,15",
                "         LA    1,PLIST",
                "         L     15,=V(COUNTER)",
                "         BASR  14,15",
                "         MVC   0(4,9),WORK",
                "         L     14,SAVE14",
                "         SR    15,15",
                "         BR    14",
                "SAVE14   DS    F",
                "PLIST    DC    A(WORK+X'80000000')",
                "WORK     DC    F'0'",
                "         LTORG",
                "         END"), lines(
                "COUNTER  CSECT",
                "         USING COUNTER,15",
                "         L     2,0(0,1)",
                "         N     2,=X'7FFFFFFF'",
                "         L     3,COUNT",
                "         LA    3,1(0,3)",
                "         ST    3,COUNT",
                "         ST    3,0(0,2)",
                "         BR    14",
                "COUNT    DC    F'0'",
                "         LTORG",
                "         END"));
        Storage caller = Storage.allocate(4);
        program.run(Storage.wrap(program.initialStorage()), context(), new DataView[] {caller.whole()});
        assertArrayEquals(new byte[] {0, 0, 0, 2}, caller.array());
    }

    @Test
    @DisplayName("解決できない外部の名前は、載せる時点で断る")
    void rejectsAnUnresolvedExternalName() throws ReflectiveOperationException {
        CobolProgram program = load(lines(
                "LONELY   CSECT",
                "         USING LONELY,15",
                "         L     15,=V(NOWHERE)",
                "         BR    14",
                "         END"));
        HlasmRuntime.HlasmExecutionException failure = assertThrows(
                HlasmRuntime.HlasmExecutionException.class,
                () -> program.run(Storage.wrap(program.initialStorage()), context(), new DataView[0]));
        assertTrue(failure.getMessage().contains("NOWHERE"), failure.getMessage());
    }

    @Test
    @DisplayName("V は 0 で組み立て、載せる側が直す場所として名前を残す")
    void assemblesAVTypeConstantAsAnExternalReference() {
        Assembler.Result result = Assembler.assemble("T.asm", lines(
                "T        CSECT",
                "         DC    F'1'",
                "         DC    V(OTHER)",
                "         DC    A(T)",
                "         END"));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        ObjectModule module = result.module();
        assertEquals("00000000", module.hex(4, 4));
        assertEquals(List.of(new ObjectModule.Relocation(4, 4, "OTHER"),
                new ObjectModule.Relocation(8, 4, null)), module.relocations());
    }

    @Test
    @DisplayName("V の中に式は書けず、番地を 2 バイトに置くことも断る")
    void rejectsMalformedAddressConstants() {
        assertFalse(Assembler.assemble("T.asm", lines(
                "T        CSECT",
                "         DC    V(OTHER+4)",
                "         END")).succeeded());
        assertFalse(Assembler.assemble("T.asm", lines(
                "T        CSECT",
                "X        DC    Y(X)",
                "         END")).succeeded());
    }
}
