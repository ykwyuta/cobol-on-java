package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.storage.DataView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 64 ビットの汎用レジスターと、番地定数の再配置を確かめる。
 *
 * <p>どれも<b>狭いほうの作りでは別の値になる</b>形で書いてある。レジスターが 32 ビットしか
 * 無ければ上位へ送った値は消え、番地定数を再配置しなければ制御節の中の変位を番地として
 * 読むことになる。期待値は Principles of Operation から来ている。実機とは突き合わせていない。
 */
class ZArchitectureTest {

    private static int returnCode(String... lines) {
        Assembler.Result result = Assembler.assemble("TEST.asm", String.join("\n", lines));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        return HlasmRuntime.execute(result.module(), null, new DataView[0]);
    }

    @Test
    @DisplayName("上位 32 ビットへ送った値が、戻したときに残っている")
    void keepsTheHighHalfOfARegister() {
        assertEquals(16, returnCode(
                "TEST     CSECT",
                "         USING TEST,15",
                "         LGHI  2,1",
                "         SLLG  2,2,40           32 ビットなら消える",
                "         SRLG  3,2,36",
                "         LR    15,3",
                "         BR    14",
                "         END"));
    }

    @Test
    @DisplayName("STG は 8 バイトを書き、負の値の上位は 1 で埋まる")
    void storesAllEightBytes() {
        assertEquals(-1, returnCode(
                "TEST     CSECT",
                "         USING TEST,12",
                "         LR    12,15",
                "         LGHI  2,-2",
                "         STG   2,AREA",
                "         L     15,AREA          上位の語",
                "         BR    14",
                "AREA     DC    D'0'",
                "         END"));
    }

    @Test
    @DisplayName("32 ビットの演算は上位を触らない")
    void leavesTheHighHalfAloneInThirtyTwoBitArithmetic() {
        assertEquals(7, returnCode(
                "TEST     CSECT",
                "         USING TEST,15",
                "         LGHI  2,7",
                "         SLLG  2,2,32",
                "         LA    2,99             下位だけが変わる",
                "         SRLG  3,2,32",
                "         LR    15,3",
                "         BR    14",
                "         END"));
    }

    @Test
    @DisplayName("番地定数は載せた場所に合わせて直される")
    void relocatesAddressConstants() {
        assertEquals(77, returnCode(
                "TEST     CSECT",
                "         USING TEST,12",
                "         LR    12,15",
                "         L     2,=A(DATA)",
                "         L     15,0(,2)         変位のままなら制御節の外を読む",
                "         BR    14",
                "DATA     DC    F'77'",
                "         END"));
    }
}
