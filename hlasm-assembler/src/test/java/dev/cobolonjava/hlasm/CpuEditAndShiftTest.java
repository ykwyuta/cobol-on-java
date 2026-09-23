package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 設計 27 §6.1 で後回しにしていた命令 ({@code ED} / {@code EDMK} / {@code TRT} / {@code MVO} /
 * {@code SRP} / {@code SLDA} / {@code SRDA} / {@code IPM} / {@code SPM}) と、プログラムマスクの実行。
 *
 * <p>期待値は Principles of Operation の記述から手で導いたものである。<b>実機と突き合わせて
 * いない</b>。z/OS probe の {@code PRBRUNA} の {@code E01}〜{@code E07} と {@code ASMPM} が、
 * 同じ問いを実機に投げる (暫定判断 P-173、P-174)。
 */
class CpuEditAndShiftTest {

    private static byte[] run(byte[] argument, String... body) {
        Assembler.Result result = Assembler.assemble("TEST.asm", String.join("\n", program(body)));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        Storage storage = Storage.copyOf(argument);
        DataView view = storage.view(0, argument.length);
        HlasmRuntime.execute(result.module(), null, new DataView[] {view});
        return storage.array();
    }

    /** 引数の番地を R3 に置いてから本体を流す。R1 と R2 は TRT / EDMK が書き換えるからである。 */
    private static List<String> program(String... body) {
        List<String> lines = new ArrayList<>();
        lines.add("TEST     CSECT");
        lines.add("         USING TEST,15");
        lines.add("         L     3,0(0,1)");
        lines.addAll(List.of(body));
        lines.add("         BR    14");
        lines.add("         END");
        return lines;
    }

    private static String failureOf(byte[] argument, String... body) {
        return assertThrows(HlasmRuntime.HlasmExecutionException.class,
                () -> run(argument, body)).getMessage();
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int k = 0; k < values.length; k++) {
            out[k] = (byte) values[k];
        }
        return out;
    }

    /** 条件コードとマスクを IPM で取り出し、引数の {@code offset} のバイトに置く命令列。 */
    private static String[] storeConditionCode(int offset) {
        return new String[] {
            "         IPM   4",
            "         SRL   4,24",
            "         STC   4," + offset + "(3)"};
    }

    private static int conditionCode(byte stored) {
        return (stored >> 4) & 0x3;
    }

    private static String[] concat(String[]... parts) {
        List<String> all = new ArrayList<>();
        for (String[] part : parts) {
            all.addAll(List.of(part));
        }
        return all.toArray(String[]::new);
    }

    private static String[] lines(String... lines) {
        return lines;
    }

    // --- ED / EDMK ---

    @Test
    @DisplayName("ED は先頭の 0 を埋め字にし、有効数字が立ったあとの句読点を残す")
    void editsWithDigitSelectorsAndPunctuation() {
        byte[] argument = bytes(
                0x40, 0x20, 0x20, 0x6B, 0x20, 0x21, 0x20, 0x4B, 0x20, 0x20, // 模様 ' dd,d9d.dd'
                0x00, 0x12, 0x34, 0x5C, // +12345
                0x00);
        byte[] result = run(argument, concat(
                lines("         ED    0(10,3),10(3)"), storeConditionCode(14)));
        // "    123.45"。',' は有効数字が立つ前なので埋め字になる
        assertArrayEquals(bytes(0x40, 0x40, 0x40, 0x40, 0xF1, 0xF2, 0xF3, 0x4B, 0xF4, 0xF5),
                Arrays.copyOf(result, 10));
        assertEquals(0x24, result[14] & 0xFF, "CC 2 (plus) and the default mask X'4'");
    }

    @Test
    @DisplayName("ED の CR は負のときだけ残り、正なら埋め字になる")
    void keepsTheCreditSymbolOnlyForNegativeValues() {
        int[] pattern = {0x5C, 0x20, 0x20, 0x20, 0x21, 0x20, 0x4B, 0x20, 0x20, 0x40, 0xC3, 0xD9};
        byte[] negative = new byte[17];
        byte[] positive = new byte[17];
        for (int k = 0; k < pattern.length; k++) {
            negative[k] = (byte) pattern[k];
            positive[k] = (byte) pattern[k];
        }
        System.arraycopy(bytes(0x00, 0x00, 0x12, 0x3D), 0, negative, 12, 4);
        System.arraycopy(bytes(0x00, 0x00, 0x12, 0x3C), 0, positive, 12, 4);
        String[] body = concat(lines("         ED    0(12,3),12(3)"), storeConditionCode(16));

        byte[] minus = run(negative, body);
        // "*****1.23 CR"。有効数字の開始 (X'21') のあとの '.' は残る
        assertArrayEquals(bytes(0x5C, 0x5C, 0x5C, 0x5C, 0x5C, 0xF1, 0x4B, 0xF2, 0xF3, 0x40, 0xC3, 0xD9),
                Arrays.copyOf(minus, 12));
        assertEquals(1, conditionCode(minus[16]), "CC 1 (minus)");

        byte[] plus = run(positive, body);
        assertArrayEquals(bytes(0x5C, 0x5C, 0x5C, 0x5C, 0x5C, 0xF1, 0x4B, 0xF2, 0xF3, 0x5C, 0x5C, 0x5C),
                Arrays.copyOf(plus, 12));
        assertEquals(2, conditionCode(plus[16]), "CC 2 (plus)");
    }

    @Test
    @DisplayName("ED の値が 0 なら条件コード 0 で、有効数字の開始のあとの 0 は表示する")
    void setsConditionCodeZeroForAZeroField() {
        byte[] argument = bytes(0x5C, 0x20, 0x20, 0x20, 0x21, 0x20, 0x4B, 0x20, 0x20,
                0x00, 0x00, 0x00, 0x0C, 0x00);
        byte[] result = run(argument, concat(
                lines("         ED    0(9,3),9(3)"), storeConditionCode(13)));
        assertArrayEquals(bytes(0x5C, 0x5C, 0x5C, 0x5C, 0x5C, 0xF0, 0x4B, 0xF0, 0xF0),
                Arrays.copyOf(result, 9));
        assertEquals(0, conditionCode(result[13]));
    }

    @Test
    @DisplayName("ED の元で桁の要る位置に符号があればデータ例外")
    void raisesDataExceptionOnASignWhereADigitIsNeeded() {
        String message = failureOf(bytes(0x40, 0x20, 0x20, 0x20, 0xC1, 0x2C),
                "         ED    0(4,3),4(3)");
        assertTrue(message.contains("S0C7"), message);
    }

    @Test
    @DisplayName("EDMK は最初の有効な桁の番地を R1 に置く")
    void marksTheFirstSignificantDigit() {
        byte[] argument = bytes(0x40, 0x20, 0x20, 0x20, 0x20, 0x20, 0x00, 0x12, 0x3C, 0x00);
        byte[] result = run(argument,
                "         SR    1,1",
                "         EDMK  0(6,3),6(3)",
                "         SR    1,3",
                "         STC   1,9(3)");
        assertArrayEquals(bytes(0x40, 0x40, 0x40, 0xF1, 0xF2, 0xF3), Arrays.copyOf(result, 6));
        assertEquals(3, result[9], "the offset of the digit 1");
    }

    // --- TRT ---

    @Test
    @DisplayName("TRT は 0 でない関数バイトの番地を R1、関数バイトを R2 に置く")
    void translatesAndTests() {
        byte[] argument = bytes(0xC1, 0xC2, 0x6B, 0xC3, 0xC4, 0, 0, 0);
        byte[] result = run(argument, concat(lines(
                "         MVI   TABLE+X'6B',X'04'",
                "         SR    2,2",
                "         TRT   0(5,3),TABLE"), storeConditionCode(7), lines(
                "         SR    1,3",
                "         STC   1,5(3)",
                "         STC   2,6(3)",
                "         BR    14",
                "TABLE    DC    256X'00'")));
        assertEquals(2, result[5], "the comma is the third byte");
        assertEquals(4, result[6], "the function byte");
        assertEquals(1, conditionCode(result[7]), "CC 1: stopped before the last byte");
    }

    @Test
    @DisplayName("TRT が何も見つけなければ条件コード 0 で、レジスタは変えない")
    void leavesTheRegistersAloneWhenNothingIsFound() {
        byte[] result = run(bytes(0xC1, 0xC2, 0, 0), concat(lines(
                "         LA    1,7",
                "         TRT   0(2,3),TABLE"), storeConditionCode(3), lines(
                "         STC   1,2(3)",
                "         BR    14",
                "TABLE    DC    256X'00'")));
        assertEquals(7, result[2]);
        assertEquals(0, conditionCode(result[3]));
    }

    // --- MVO ---

    @Test
    @DisplayName("MVO は第 1 演算項の右端のニブルを残して、その左へ桁を置く")
    void movesWithOffset() {
        byte[] result = run(bytes(0x77, 0x88, 0x9C, 0x12, 0x34),
                "         MVO   0(3,3),3(2,3)");
        assertArrayEquals(bytes(0x01, 0x23, 0x4C, 0x12, 0x34), result);
    }

    @Test
    @DisplayName("MVO で入りきらない左の桁は捨てる")
    void dropsTheDigitsThatDoNotFit() {
        byte[] result = run(bytes(0x00, 0x0F, 0x12, 0x34, 0x56),
                "         MVO   0(2,3),2(3,3)");
        assertArrayEquals(bytes(0x45, 0x6F, 0x12, 0x34, 0x56), result);
    }

    // --- SRP と 10 進のマスク ---

    @Test
    @DisplayName("SRP は左へ 2 桁動かす")
    void shiftsLeft() {
        byte[] result = run(bytes(0x00, 0x12, 0x3C), "         SRP   0(3,3),2,0");
        assertArrayEquals(bytes(0x12, 0x30, 0x0C), result);
    }

    @Test
    @DisplayName("SRP は右へ動かすとき、押し出した桁の最上位に丸めの桁を足す")
    void shiftsRightAndRounds() {
        assertArrayEquals(bytes(0x01, 0x23, 0x5C),
                run(bytes(0x12, 0x34, 0x5C), "         SRP   0(3,3),64-1,5"));
        // 12345 を右へ 2 桁: 押し出す最上位の桁は 4 であり、4 + 5 は繰り上がらない
        assertArrayEquals(bytes(0x00, 0x12, 0x3D),
                run(bytes(0x12, 0x34, 0x5D), "         SRP   0(3,3),64-2,5"));
    }

    @Test
    @DisplayName("SRP で 0 になった負の値は正の 0 になる")
    void makesAZeroResultPositive() {
        byte[] result = run(bytes(0x00, 0x00, 0x4D, 0), concat(
                lines("         SRP   0(3,3),64-1,5"), storeConditionCode(3)));
        assertArrayEquals(bytes(0x00, 0x00, 0x0C), Arrays.copyOf(result, 3));
        assertEquals(0, conditionCode(result[3]));
    }

    @Test
    @DisplayName("SRP の左へのあふれは、既定のマスクでは S0CA になる")
    void raisesDecimalOverflowUnderTheDefaultMask() {
        String message = failureOf(bytes(0x12, 0x34, 0x5C), "         SRP   0(3,3),1,0");
        assertTrue(message.contains("S0CA"), message);
    }

    @Test
    @DisplayName("10 進オーバーフローのマスクを下ろせば、上を捨てた値と条件コード 3 になる")
    void truncatesWithConditionCodeThreeWhenTheMaskIsOff() {
        byte[] result = run(bytes(0x12, 0x34, 0x5C, 0), concat(lines(
                "         SR    5,5",
                "         SPM   5",
                "         SRP   0(3,3),1,0"), storeConditionCode(3)));
        assertArrayEquals(bytes(0x23, 0x45, 0x0C), Arrays.copyOf(result, 3));
        assertEquals(0x30, result[3] & 0xFF, "CC 3 and an empty mask");
    }

    @Test
    @DisplayName("AP のあふれも、マスクを下ろせば条件コード 3 で続く")
    void decimalAddFollowsTheMaskToo() {
        byte[] result = run(bytes(0x99, 0x9C, 0x00, 0x1C, 0), concat(lines(
                "         SR    5,5",
                "         SPM   5",
                "         AP    0(2,3),2(2,3)"), storeConditionCode(4)));
        assertArrayEquals(bytes(0x00, 0x0C), Arrays.copyOf(result, 2));
        assertEquals(3, conditionCode(result[4]));
    }

    @Test
    @DisplayName("MP の被乗数に乗数の長さ分の 0 がなければデータ例外")
    void requiresLeadingZerosInTheMultiplicand() {
        String message = failureOf(bytes(0x12, 0x3C, 0x2C), "         MP    0(2,3),2(1,3)");
        assertTrue(message.contains("S0C7"), message);
    }

    @Test
    @DisplayName("DP の商が入りきらなければ 10 進除算例外")
    void raisesDecimalDivideWhenTheQuotientDoesNotFit() {
        String message = failureOf(bytes(0x12, 0x34, 0x5C, 0x1C), "         DP    0(3,3),3(1,3)");
        assertTrue(message.contains("S0CB"), message);
    }

    // --- SLDA / SRDA / SLA と固定小数点のマスク ---

    @Test
    @DisplayName("SLDA は 2 つのレジスタをまたいで動かす")
    void shiftsADoubleWordLeft() {
        byte[] result = run(new byte[8],
                "         SR    4,4",
                "         LA    5,1",
                "         SLDA  4,33",
                "         STM   4,5,0(3)");
        assertArrayEquals(bytes(0, 0, 0, 2, 0, 0, 0, 0), result);
    }

    @Test
    @DisplayName("SRDA は符号を広げる")
    void shiftsADoubleWordRightArithmetically() {
        byte[] result = run(new byte[9], concat(lines(
                "         L     4,MINUS1",
                "         L     5,MINUS8",
                "         SRDA  4,2",
                "         STM   4,5,0(3)"), storeConditionCode(8), lines(
                "         BR    14",
                "MINUS1   DC    F'-1'",
                "MINUS8   DC    F'-8'")));
        assertArrayEquals(bytes(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFE),
                Arrays.copyOf(result, 8));
        assertEquals(1, conditionCode(result[8]));
    }

    @Test
    @DisplayName("SLDA で符号と違うビットを押し出すと条件コード 3 で、符号は残る")
    void keepsTheSignOnDoubleOverflow() {
        byte[] result = run(new byte[9], concat(lines(
                "         L     4,HIGH",
                "         SR    5,5",
                "         SLDA  4,1",
                "         STM   4,5,0(3)"), storeConditionCode(8), lines(
                "         BR    14",
                "HIGH     DC    X'40000000'")));
        assertArrayEquals(new byte[8], Arrays.copyOf(result, 8));
        assertEquals(3, conditionCode(result[8]));
    }

    @Test
    @DisplayName("SLA は符号ビットを動かさない")
    void keepsTheSignOnSingleShift() {
        byte[] result = run(new byte[5], concat(lines(
                "         L     4,MINUS1",
                "         SLA   4,31",
                "         ST    4,0(3)"), storeConditionCode(4), lines(
                "         BR    14",
                "MINUS1   DC    F'-1'")));
        assertArrayEquals(bytes(0x80, 0, 0, 0), Arrays.copyOf(result, 4));
        assertEquals(1, conditionCode(result[4]), "no overflow: every bit shifted out equals the sign");
    }

    @Test
    @DisplayName("固定小数点のあふれは、既定では条件コード 3、マスクを立てれば S0C8")
    void raisesFixedOverflowOnlyUnderTheMask() {
        String[] overflow = lines(
                "         L     4,MAX",
                "         LA    5,1",
                "         AR    4,5");
        byte[] result = run(new byte[1], concat(overflow, storeConditionCode(0), lines(
                "         BR    14",
                "MAX      DC    X'7FFFFFFF'")));
        assertEquals(3, conditionCode(result[0]));

        String message = failureOf(new byte[1], concat(lines(
                "         L     6,MASK",
                "         SPM   6"), overflow, lines(
                "         BR    14",
                "MAX      DC    X'7FFFFFFF'",
                "MASK     DC    X'08000000'")));
        assertTrue(message.contains("S0C8"), message);
    }

    // --- IPM / SPM ---

    @Test
    @DisplayName("SPM で置いた条件コードとマスクを、IPM がそのまま取り出す")
    void roundTripsTheConditionCodeAndMask() {
        byte[] result = run(new byte[4],
                "         L     6,STATE",
                "         SPM   6",
                "         LA    7,X'FF'",
                "         IPM   7",
                "         ST    7,0(3)",
                "         BR    14",
                "STATE    DC    X'2C000000'");
        // 上位 2 ビットは 0、下位 24 ビットは元のまま
        assertArrayEquals(bytes(0x2C, 0x00, 0x00, 0xFF), result);
    }
}
