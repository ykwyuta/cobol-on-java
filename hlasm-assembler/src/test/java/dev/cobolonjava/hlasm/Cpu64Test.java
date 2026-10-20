package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * z/Architecture の 64 ビットの命令と、ZHVS が使う追加の命令。
 *
 * <p>期待値は Principles of Operation の各命令の説明から書いた。この環境に Hercules が無いので、実機の
 * 代わりの外の基準とは突き合わせていない (P-175 の続き)。
 */
class Cpu64Test {

    /** 本体を動かし、R9 が指す 64 バイトの作業域を 16 進で返す。 */
    private static String run(String... body) {
        List<String> source = new ArrayList<>();
        source.add("TEST     CSECT");
        source.add("         USING TEST,15");
        source.add("         L     9,0(0,1)");
        for (String line : body) {
            source.add("         " + line);
        }
        source.add("         BR    14");
        source.add("         LTORG");
        source.add("         END");
        Assembler.Result result = Assembler.assemble("T.asm", String.join("\n", source));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        Storage area = Storage.allocate(64);
        HlasmRuntime.execute(result.module(), null, new DataView[] {area.whole()});
        return HexFormat.of().withUpperCase().formatHex(area.array());
    }

    /** 作業域の先頭 n バイト。 */
    private static String first(int bytes, String... body) {
        return run(body).substring(0, bytes * 2);
    }

    /** IPM で取り出した条件コード。 */
    private static int conditionCode(String... body) {
        List<String> lines = new ArrayList<>(List.of(body));
        lines.add("IPM   7");
        lines.add("SRL   7,28");
        lines.add("ST    7,0(0,9)");
        return Integer.parseInt(first(4, lines.toArray(String[]::new)), 16) & 3;
    }

    @Test
    @DisplayName("32 ビットの命令はレジスタの上位 32 ビットを変えない")
    void thirtyTwoBitInstructionsKeepTheHighHalf() {
        assertEquals("FFFFFFFF00000005", first(8, "LGHI  2,-1", "LHI   2,5", "STG   2,0(0,9)"));
        assertEquals("FFFFFFFFFFFFFFFF", first(8, "LGHI  2,-1", "STG   2,0(0,9)"));
        assertEquals("0000000080000000", first(8, "IILF  2,X'80000000'", "LLGFR 3,2", "STG   3,0(0,9)"));
        assertEquals("FFFFFFFF80000000", first(8, "IILF  2,X'80000000'", "LGFR  3,2", "STG   3,0(0,9)"));
    }

    @Test
    @DisplayName("64 ビットの加算はあふれで条件コード 3、論理加算は桁上がりを ALCGR へ渡す")
    void addsInSixtyFourBits() {
        assertEquals(3, conditionCode("LG    2,=XL8'7FFFFFFFFFFFFFFF'", "AGHI  2,1"));
        assertEquals(2, conditionCode("LG    2,=XL8'FFFFFFFFFFFFFFFF'", "LGHI  3,1", "ALGR  2,3"));
        assertEquals("0000000000000008", first(8, "LG    2,=XL8'FFFFFFFFFFFFFFFF'", "LGHI  3,1",
                "ALGR  2,3", "LGHI  4,5", "LGHI  5,2", "ALCGR 4,5", "STG   4,0(0,9)"));
    }

    @Test
    @DisplayName("ICM はマスクのバイトだけを入れ、左端の入れたビットで条件コードを決める")
    void insertsCharactersUnderMask() {
        assertEquals("11803301", first(4, "L     2,=X'11223344'", "ICM   2,B'0101',=X'8001'",
                "ST    2,0(0,9)"));
        assertEquals(1, conditionCode("ICM   2,B'0101',=X'8001'"));
        assertEquals(2, conditionCode("ICM   2,B'0011',=X'0001'"));
        assertEquals(0, conditionCode("ICM   2,B'1111',=F'0'"));
        assertEquals("AABBCCDD00000000", first(8, "LGHI  2,0", "ICMH  2,B'1111',=X'AABBCCDD'",
                "STG   2,0(0,9)"));
    }

    @Test
    @DisplayName("シフト、回転、RISBG、FLOGR、POPCNT")
    void shiftsAndBitOperations() {
        assertEquals("0000000100000000", first(8, "LGHI  3,1", "SLLG  2,3,32", "STG   2,0(0,9)"));
        assertEquals("5566778811223344", first(8, "LG    3,=XL8'1122334455667788'", "RLLG  2,3,32",
                "STG   2,0(0,9)"));
        assertEquals("0000000055667788", first(8, "LGHI  2,-1", "LG    3,=XL8'1122334455667788'",
                "RISBG 2,3,32,191,0", "STG   2,0(0,9)"));
        assertEquals("00000000000000370000000000000000", first(16, "LGHI  4,256", "FLOGR 2,4",
                "STG   2,0(0,9)", "STG   3,8(0,9)"));
        assertEquals("0801020400000107", first(8, "LG    3,=XL8'FF01030F0000807F'", "POPCNT 2,3",
                "STG   2,0(0,9)"));
        assertEquals("0000000000000017", first(8, "LG    3,=XL8'FF01030F0000807F'", "POPCNT 2,3,8",
                "STG   2,0(0,9)"));
    }

    @Test
    @DisplayName("TP は符号と数字の正しさを別々に条件コードへ出す")
    void testsPackedDecimal() {
        assertEquals(0, conditionCode("MVC   0(2,9),=X'123C'", "TP    0(2,9)"));
        assertEquals(1, conditionCode("MVC   0(2,9),=X'1234'", "TP    0(2,9)"));
        assertEquals(2, conditionCode("MVC   0(2,9),=X'1A3C'", "TP    0(2,9)"));
        assertEquals(3, conditionCode("MVC   0(2,9),=X'A2B4'", "TP    0(2,9)"));
    }

    @Test
    @DisplayName("CS は等しければ置き換え、違えば記憶域の値を読む。LAAG は古い値を返す")
    void interlockedUpdates() {
        assertEquals("0000000700000005", first(8, "MVC   0(4,9),=F'5'", "L     2,=F'5'", "L     3,=F'7'",
                "CS    2,3,0(9)", "ST    2,4(0,9)"));
        assertEquals("0000000500000005", first(8, "MVC   0(4,9),=F'5'", "L     2,=F'6'", "L     3,=F'7'",
                "CS    2,3,0(9)", "ST    2,4(0,9)"));
        assertEquals("00000000000000020000000000000007", first(16, "MVC   0(8,9),=XL8'07'",
                "LGHI  3,-5", "LAAG  2,3,0(9)", "STG   2,8(0,9)"));
    }

    @Test
    @DisplayName("RXY の変位は負にもなる")
    void longDisplacementIsSigned() {
        assertEquals("0000002A0000002A", first(8, "LA    8,32(0,9)", "MVC   0(4,9),=F'42'",
                "LY    3,-32(0,8)", "ST    3,4(0,9)"));
    }

    @Test
    @DisplayName("BASR が置く戻り番地には 31 ビットの番地方式の印 (ビット 32) が立つ")
    void branchAndSaveMarksTheAddressingMode() {
        int linked = (int) Long.parseLong(first(4, "BASR  2,0", "ST    2,0(0,9)"), 16);
        assertTrue(linked < 0, Integer.toHexString(linked));
        int under24 = (int) Long.parseLong(first(4, "SAM24", "BASR  2,0", "SAM31", "ST    2,0(0,9)"), 16);
        assertEquals(0, under24 & 0xFF000000, Integer.toHexString(under24));
    }

    @Test
    @DisplayName("即値・変位・長さ・マスクの範囲を越えたものは組み立てで断る")
    void rejectsOperandsOutOfRange() {
        for (String operation : new String[] {"LGHI  2,32768", "LY    2,524288(3)", "TP    A(17)",
                "ICM   2,16,A"}) {
            Assembler.Result result = Assembler.assemble("T.asm", String.join("\n",
                    "TEST     CSECT",
                    "         USING TEST,15",
                    "         " + operation,
                    "         BR    14",
                    "A        DS    16X",
                    "         END"));
            assertFalse(result.succeeded(), operation);
        }
    }
}
