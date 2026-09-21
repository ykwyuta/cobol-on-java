package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.oracle.machine.Insn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 組み立てた機械語を、{@code cobol-oracle} の {@link Insn} と突き合わせる。
 *
 * <p>{@link Insn} は Hercules へ流す命令列を組むために別に書かれたもので、その出力で実行した
 * 結果は実際に Hercules と突き合わせ済みである (暫定判断 P-001 のゼロ結果の符号など)。
 * つまり<b>裏づけのある機械語</b>であり、こちらの表が正しいかを外から測る基準になる。
 *
 * <p>2 つの表が食い違ったまま気付かないと、レポート §5.2 に書いた落とし穴に落ちる。
 * 誤った機械語を Hercules も自分のインタプリタも同じように実行し、一致してしまう。
 * だから実行の一致とは別に、<b>組み立てそのもの</b>をここで測る。
 *
 * <p>{@link Insn} はベースレジスタ 0 を使う。原文でも明示的にベース 0 を書いて揃える。
 */
class InsnAgreementTest {

    private static byte[] assemble(String instruction) {
        Assembler.Result result = Assembler.assemble("TEST.asm", String.join("\n",
                "TEST     CSECT",
                "         " + instruction,
                "         END"));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        return result.module().text();
    }

    @Test
    @DisplayName("10 進の加減乗除とゼロ設定・比較が一致する")
    void agreesOnDecimalArithmetic() {
        assertArrayEquals(Insn.ap(0x100, 4, 0x200, 3), assemble("AP    256(4,0),512(3,0)"));
        assertArrayEquals(Insn.sp(0x100, 4, 0x200, 3), assemble("SP    256(4,0),512(3,0)"));
        assertArrayEquals(Insn.mp(0x100, 8, 0x200, 2), assemble("MP    256(8,0),512(2,0)"));
        assertArrayEquals(Insn.dp(0x100, 8, 0x200, 2), assemble("DP    256(8,0),512(2,0)"));
        assertArrayEquals(Insn.zap(0x100, 4, 0x200, 3), assemble("ZAP   256(4,0),512(3,0)"));
        assertArrayEquals(Insn.cp(0x100, 4, 0x200, 4), assemble("CP    256(4,0),512(4,0)"));
    }

    @Test
    @DisplayName("パックとアンパックと桁ずらしが一致する")
    void agreesOnPackAndUnpack() {
        assertArrayEquals(Insn.pack(0x100, 5, 0x200, 9), assemble("PACK  256(5,0),512(9,0)"));
        assertArrayEquals(Insn.unpk(0x100, 9, 0x200, 5), assemble("UNPK  256(9,0),512(5,0)"));
        assertArrayEquals(Insn.mvo(0x100, 4, 0x200, 3), assemble("MVO   256(4,0),512(3,0)"));
    }

    @Test
    @DisplayName("転記・比較・変換・編集が一致する")
    void agreesOnCharacterInstructions() {
        assertArrayEquals(Insn.mvc(0x100, 16, 0x200), assemble("MVC   256(16,0),512(0)"));
        assertArrayEquals(Insn.clc(0x100, 16, 0x200), assemble("CLC   256(16,0),512(0)"));
        assertArrayEquals(Insn.tr(0x100, 8, 0x200), assemble("TR    256(8,0),512(0)"));
        assertArrayEquals(Insn.ed(0x100, 12, 0x200), assemble("ED    256(12,0),512(0)"));
        assertArrayEquals(Insn.edmk(0x100, 12, 0x200), assemble("EDMK  256(12,0),512(0)"));
    }

    /**
     * {@code SRP} の第 2 演算項は番地ではなく桁移動量である。{@link Insn} は下位 6 ビットの
     * 2 の補数として渡す。原文では変位そのものを書くので、右シフトは 64 から引いた値になる。
     */
    @Test
    @DisplayName("SRP の長さと丸め桁が一致する")
    void agreesOnShiftAndRound() {
        assertArrayEquals(Insn.srp(0x100, 4, 2, 5), assemble("SRP   256(4,0),2(0),5"));
        assertArrayEquals(Insn.srp(0x100, 4, -3, 5), assemble("SRP   256(4,0),61(0),5"));
    }
}
