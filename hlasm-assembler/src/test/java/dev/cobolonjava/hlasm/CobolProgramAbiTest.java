package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 生成クラスが既存の呼出し ABI に着地することを確かめる。
 *
 * <p>調査レポートで「薄い殻と {@code CobolProgram} ABI はそのまま使える」と書いたところの裏づけである。
 * ここが通れば、既存のプログラム解決・{@code CALL}・JCL・CICS / IMS の領域が、
 * HLASM の副プログラムに対しても COBOL や PL/I と同じように働く。
 */
class CobolProgramAbiTest {

    /** 原文を翻訳し、生成クラスを読み込んで {@link CobolProgram} として返す。 */
    private static CobolProgram load(String... lines) throws ReflectiveOperationException {
        HlasmCompiler.Result result = HlasmCompiler.standard()
                .compile("BUMP.asm", String.join("\n", lines));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        GeneratedLoader loader = new GeneratedLoader();
        Class<?> type = loader.define(result.className(), result.classFile());
        return (CobolProgram) type.getDeclaredConstructor().newInstance();
    }

    /**
     * COBOL の {@code CALL 'BUMP' USING WS-COUNT} に相当する呼び方である。
     * 呼ぶ側の記憶域をそのまま渡し、戻ったあと同じビューから読む。
     */
    @Test
    @DisplayName("COBOL からの CALL と同じ形で呼べ、引数の書き換えが呼ぶ側から見える")
    void isCallableThroughTheSharedAbi() throws ReflectiveOperationException {
        CobolProgram program = load(
                "BUMP     CSECT",
                "         USING BUMP,15",
                "         L     2,0(0,1)         引数 1 の番地",
                "         AP    0(3,2),ONE       パック 10 進に 1 を足す",
                "         SR    15,15",
                "         BR    14",
                "ONE      DC    PL1'1'",
                "         END");

        // 呼ぶ側の記憶域。先頭 3 バイトがパック 10 進の引数である
        Storage caller = Storage.copyOf(new byte[] {0x00, 0x12, 0x3C, (byte) 0xFF});
        DataView argument = caller.view(0, 3);
        program.run(Storage.wrap(program.initialStorage()), null, new DataView[] {argument});

        // 123 + 1 = 124。引数の外は触っていない
        assertArrayEquals(new byte[] {0x00, 0x12, 0x4C, (byte) 0xFF}, caller.array());
    }

    @Test
    @DisplayName("生成クラスのプログラム名は制御節の名前である")
    void namesTheProgramAfterTheControlSection() throws ReflectiveOperationException {
        CobolProgram program = load(
                "BUMP     CSECT",
                "         BR    14",
                "         END");
        assertEquals("BUMP", program.name());
    }

    @Test
    @DisplayName("クラス名は制御節の名前から決まる")
    void derivesTheClassNameFromTheControlSection() {
        HlasmCompiler.Result result = HlasmCompiler.standard().compile("X.asm", String.join("\n",
                "BUMP     CSECT",
                "         BR    14",
                "         END"));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        assertEquals("hlasm.generated.BUMP", result.className());
    }

    /**
     * 引数の個数も長さも原文からは分からない。R1 の表を何個読むかを決めるのはプログラム自身である。
     * 分からないものを「1 個」などと決めて署名に書くと、呼ぶ前の検査が嘘の根拠で通る。
     */
    @Test
    @DisplayName("署名は引数を持たない。原文から引数の個数は決まらない")
    void carriesNoParametersInTheSignature() throws ReflectiveOperationException {
        CobolProgram program = load(
                "BUMP     CSECT",
                "         BR    14",
                "         END");
        assertEquals("BUMP", program.programSignature().programId().value());
        assertTrue(program.programSignature().parameters().isEmpty());
    }

    @Test
    @DisplayName("作業場所は制御節が持つので、呼ぶ側の記憶域は要らない")
    void needsNoCallerStorage() throws ReflectiveOperationException {
        CobolProgram program = load(
                "BUMP     CSECT",
                "         BR    14",
                "         END");
        assertEquals(0, program.initialStorage().length);
    }

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(CobolProgramAbiTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
