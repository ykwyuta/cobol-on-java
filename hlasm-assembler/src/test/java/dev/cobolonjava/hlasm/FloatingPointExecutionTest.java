package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 浮動小数点の命令を動かして、結果のバイト列を確かめる。
 *
 * <p>期待値は Principles of Operation の形式の定義から手で組んだものである。3 つの基数は
 * <b>同じ値でもバイト列が違い、丸めも違う</b>ので、基数を取り違えると必ず別の値になる。
 * 実機とは突き合わせていない。
 */
class FloatingPointExecutionTest {

    /** 16 バイトの引数を渡して動かし、書き換わった引数を 16 進で返す。 */
    private static String run(String... lines) {
        Assembler.Result result = Assembler.assemble("TEST.asm", String.join("\n", lines));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        Storage storage = Storage.copyOf(new byte[16]);
        HlasmRuntime.execute(result.module(), null, new DataView[] {storage.view(0, 16)});
        return HexFormat.of().withUpperCase().formatHex(storage.array());
    }

    private static String[] program(String... body) {
        String[] head = {
            "TEST     CSECT",
            "         USING TEST,12",
            "         LR    12,15",
            "         L     2,0(,1)",
        };
        String[] all = new String[head.length + body.length + 1];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        all[all.length - 1] = "         END";
        return all;
    }

    @Test
    @DisplayName("HFP の加算は 16 進の桁で正規化する")
    void addsHexadecimal() {
        assertArrayEquals(new String[] {"41400000000000000000000000000000"}, new String[] {run(
                program(
                "         LD    0,=D'1.5'",
                "         AD    0,=D'2.5'",
                "         STD   0,0(,2)",
                "         BR    14"))});
    }

    @Test
    @DisplayName("HFP の除算は切り捨てる (0.1 は 1999...9A ではなく 1999...99)")
    void truncatesHexadecimalDivision() {
        assertArrayEquals(new String[] {"40199999999999990000000000000000"}, new String[] {run(
                program(
                "         LD    0,=D'1'",
                "         DD    0,=D'10'",
                "         STD   0,0(,2)",
                "         BR    14"))});
    }

    @Test
    @DisplayName("BFP の乗算は IEEE の並びで返る")
    void multipliesBinary() {
        assertArrayEquals(new String[] {"C0180000000000000000000000000000"}, new String[] {run(
                program(
                "         LD    0,=DB'-1.5'",
                "         LD    2,=DB'4'",
                "         MDBR  0,2",
                "         STD   0,0(,2)",
                "         BR    14"))});
    }

    @Test
    @DisplayName("BFP の 1/3 は不正確で、FPC に旗が残る")
    void recordsInexactInTheControlRegister() {
        assertArrayEquals(new String[] {"3FD55555555555550008000000000000"}, new String[] {run(
                program(
                "         LD    0,=DB'1'",
                "         LD    4,=DB'3'",
                "         DDBR  0,4",
                "         STD   0,0(,2)",
                "         STFPC 8(2)",
                "         BR    14"))});
    }

    @Test
    @DisplayName("DFP の乗算は量子を保つ (1.20 × 2.0 = 2.400)")
    void keepsTheDecimalQuantum() {
        assertArrayEquals(new String[] {"222C000000000A000000000000000000"}, new String[] {run(
                program(
                "         LD    4,=DD'1.20'",
                "         LD    6,=DD'2.0'",
                "         MDTR  0,4,6",
                "         STD   0,0(,2)",
                "         BR    14"))});
    }

    @Test
    @DisplayName("PFPO は BFP の 0.1 を DFP の 16 桁へ丸め、不正確を条件コード 1 で知らせる")
    void convertsBinaryToDecimal() {
        // 0.1 (2 進) = 0.1000000000000000055511151231257827…。16 桁では 1000000000000000E-16。
        // 指数 -16 は偏り 398 を足して 382 (X'17E')、先頭の桁 1 と合わせて X'25F8' になる。
        // 続く語は IPM (条件コード 1 とプログラムマスク 4)、最後が FPC (不正確の旗)
        assertArrayEquals(new String[] {"25F80000000000001400000000080000"}, new String[] {run(
                program(
                "         LD    4,=DB'0.1'",
                "         L     0,=X'01090608'    DFP 長 ← BFP 長、最近接偶数",
                "         PFPO",
                "         IPM   3",
                "         STD   0,0(,2)",
                "         ST    3,8(,2)",
                "         STFPC 12(2)",
                "         BR    14"))});
    }

    @Test
    @DisplayName("PFPO は HFP の拡張形式を 2 本のレジスターへ返す")
    void convertsToExtendedHexadecimal() {
        assertArrayEquals(new String[] {"41180000000000003300000000000000"}, new String[] {run(
                program(
                "         LE    4,=EB'1.5'",
                "         L     0,=X'01020508'",
                "         PFPO",
                "         STD   0,0(,2)",
                "         STD   2,8(,2)",
                "         BR    14"))});
    }

    @Test
    @DisplayName("同じ基数どうしの PFPO は指定例外になる")
    void rejectsAConversionWithinOneRadix() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run(program(
                "         L     0,=X'01060508'",
                "         PFPO",
                "         BR    14")));
        assertTrue(failure.getMessage().contains("PFPO"), failure.getMessage());
    }

    @Test
    @DisplayName("NaN の演算は近い値を返さずに断る")
    void refusesArithmeticOnNotANumber() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> run(program(
                "         LD    0,=DB'(NAN)'",
                "         LD    2,=DB'1'",
                "         ADBR  0,2",
                "         BR    14")));
        assertTrue(failure.getMessage().contains("not supported yet"), failure.getMessage());
    }
}
