package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.storage.DataView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * アセンブラーの言語のうち、以前は断っていた書き方。
 *
 * <p>どれも HLASM Language Reference が許しているのに、こちらが狭く決めていたものである
 * (覚え書き 3)。動かして値を見るので、受け付けただけで別の値を組み立てれば落ちる。
 */
class AssemblerLanguageTest {

    private static int returnCode(String... lines) {
        Assembler.Result result = Assembler.assemble("TEST.asm", String.join("\n", lines));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        // OS のサービス (SVC) は実行単位が受けるので、実行単位を渡して動かす
        return HlasmRuntime.execute(result.module(),
                dev.cobolonjava.runtime.program.ProgramContext.standard(), new DataView[0]);
    }

    private static String diagnosticOf(String... lines) {
        Assembler.Result result = Assembler.assemble("TEST.asm", String.join("\n", lines));
        assertFalse(result.succeeded(), "the source must be rejected");
        return result.diagnostics().toString();
    }

    @Test
    @DisplayName("EQU はあとで定義される記号を使える")
    void resolvesForwardEquates() {
        assertEquals(17, returnCode(
                "TEST     CSECT",
                "         LA    15,FORWARD",
                "         BR    14",
                "FORWARD  EQU   NEXT+5",
                "NEXT     EQU   12",
                "         END"));
    }

    @Test
    @DisplayName("どこにも定義されない記号の EQU は断る")
    void rejectsAnEquateThatNeverResolves() {
        assertTrue(diagnosticOf(
                "TEST     CSECT",
                "         BR    14",
                "X        EQU   NOWHERE+1",
                "         END").contains("undefined symbol"));
    }

    @Test
    @DisplayName("同じ名前の CSECT を書くと、その節の続きに戻る")
    void resumesAControlSection() {
        assertEquals(8, returnCode(
                "TEST     CSECT",
                "         LA    15,SIZE",
                "         BR    14",
                "MAP      DSECT",
                "A        DS    F",
                "B        DS    F",
                "SIZE     EQU   *-MAP",
                "TEST     CSECT",
                "         DC    F'0'",
                "         END"));
    }

    @Test
    @DisplayName("演算項なしの ORG は、2 周目でもその時点の最高位置へ戻る")
    void returnsToTheHighWaterMarkOfTheMoment() {
        assertEquals(0x01020304, returnCode(
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     15,AREA",
                "         BR    14",
                "AREA     DC    8X'00'",
                "         ORG   AREA",
                "         DC    X'01020304'",
                "         ORG",
                "TAIL     DC    F'-1'",
                "         END"));
    }

    @Test
    @DisplayName("位取りつきの固定小数点と、その S' と I'")
    void scalesFixedPointConstants() {
        // FS2'1.5' は 1.5 × 4 = 6。S' は 2、I' は 32 − 2 − 1 = 29
        assertEquals(6 * 10000 + 2 * 100 + 29, returnCode(
                "TEST     CSECT",
                "         USING TEST,12",
                "         LR    12,15",
                "         L     15,SCALED",
                "         MH    15,=H'100'",
                "         AHI   15,S'SCALED",
                "         MH    15,=H'100'",
                "         AHI   15,I'SCALED",
                "         BR    14",
                "SCALED   DC    FS2'1.5'",
                "         END"));
    }

    @Test
    @DisplayName("リテラルの長さ属性")
    void takesTheLengthOfALiteral() {
        assertEquals(2, returnCode(
                "TEST     CSECT",
                "         LA    15,L'=H'7'",
                "         BR    14",
                "         END"));
    }

    @Test
    @DisplayName("OPSYN は付け替えた時点の命令を指す")
    void renamesOperationCodes() {
        assertEquals(10, returnCode(
                "OLDADD   OPSYN AHI",
                "AHI      OPSYN SR",
                "TEST     CSECT",
                "         LA    15,3",
                "         OLDADD 15,7            もとの AHI (加算)",
                "         BR    14",
                "         END"));
    }

    @Test
    @DisplayName("乗算と除算の第 1 演算項が奇数のレジスターなら断る")
    void rejectsAnOddRegisterPair() {
        assertTrue(diagnosticOf(
                "TEST     CSECT",
                "         MR    3,4",
                "         BR    14",
                "         END").contains("even register"));
    }

    @Test
    @DisplayName("16 ビットの即値に入らない値は断る")
    void rejectsAnImmediateOutOfRange() {
        assertTrue(diagnosticOf(
                "TEST     CSECT",
                "         LGHI  2,32768",
                "         BR    14",
                "         END").contains("immediate"));
    }

    @Test
    @DisplayName("GETMAIN で得た記憶域に書いて読める。POST は ECB に完了の印を置く")
    void obtainsStorageAndPostsAnEventControlBlock() {
        assertEquals(0x40000000 + 0x1234, returnCode(
                "TEST     CSECT",
                "         USING TEST,12",
                "         LR    12,15",
                "         LR    10,14",
                "         GETMAIN RU,LV=16",
                "         LR    8,1",
                "         MVC   0(4,8),=X'00001234'",
                "         POST  ECB",
                "         L     15,ECB",
                "         A     15,0(,8)",
                "         LR    9,15",
                "         FREEMAIN RU,LV=16,A=(8)",
                "         LR    15,9",
                "         BR    10",
                "ECB      DC    F'0'",
                "         END"));
    }

    @Test
    @DisplayName("SAM24 のあいだは実効番地が 24 ビットで切れる")
    void truncatesAddressesInTwentyFourBitMode() {
        assertEquals(0x00345678, returnCode(
                "TEST     CSECT",
                "         USING TEST,12",
                "         LR    12,15",
                "         L     2,=X'12345678'",
                "         SAM24",
                "         LA    15,0(,2)",
                "         SAM31",
                "         BR    14",
                "         END"));
    }
}
