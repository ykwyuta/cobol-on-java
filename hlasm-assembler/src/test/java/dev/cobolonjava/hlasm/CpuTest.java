package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 命令を実行して結果を確かめる。
 *
 * <p>ここで測っているのは<b>実行</b>だけである。組み立てが正しいことは {@link AssemblerTest} と
 * {@link InsnAgreementTest} が別に測っている。2 つを混ぜないのは、誤った機械語を正しく実行した
 * 結果と突き合わせて「一致した」としないためである (設計 27 §3)。
 *
 * <p>ここでの期待値は Principles of Operation と、{@code cobol-runtime} が Hercules と
 * 突き合わせてある層から来ている。実行の一部は {@link HerculesExecutionOracleTest} で
 * 同じ機械語を Hercules に流して直接比較する。
 */
class CpuTest {

    /** 引数 1 つを渡して副プログラムを動かし、書き換わった引数を返す。 */
    private static byte[] run(byte[] argument, String... lines) {
        Assembler.Result result = Assembler.assemble("TEST.asm", String.join("\n", lines));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        Storage storage = Storage.copyOf(argument);
        DataView view = storage.view(0, argument.length);
        HlasmRuntime.execute(result.module(), null, new DataView[] {view});
        return storage.array();
    }

    private static int returnCode(String... lines) {
        Assembler.Result result = Assembler.assemble("TEST.asm", String.join("\n", lines));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        return HlasmRuntime.execute(result.module(), null, new DataView[0]);
    }

    // --- 標準リンケージ ---

    @Test
    @DisplayName("R1 が指す表から引数の番地を取り、書き換えが呼ぶ側に見える")
    void readsTheParameterListThroughRegisterOne() {
        byte[] result = run(new byte[] {0, 0, 0, 0},
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     2,0(0,1)         引数 1 の番地",
                "         MVI   0(2),X'5A'",
                "         BR    14",
                "         END");
        assertEquals((byte) 0x5A, result[0]);
    }

    @Test
    @DisplayName("引数表の最後の番地は上位ビットが立っている")
    void marksTheLastParameterWithTheHighBit() {
        byte[] result = run(new byte[4],
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     2,0(0,1)         引数表の 1 つ目",
                "         ST    2,0(0,2)         番地そのものを書き戻す",
                "         BR    14",
                "         END");
        assertTrue((result[0] & 0x80) != 0, "the high bit marks the end of the list");
    }

    @Test
    @DisplayName("R15 に置いた値が戻りコードになる")
    void returnsTheValueInRegisterFifteen() {
        assertEquals(8, returnCode(
                "TEST     CSECT",
                "         LA    15,8",
                "         BR    14",
                "         END"));
    }

    /**
     * 同じ記憶域を 2 つの引数として渡したとき、片方への書き込みがもう片方から見える。
     * 決定 0002 が保つと定めたエイリアシングであり、写しを取る実装では壊れる。
     */
    @Test
    @DisplayName("同じ記憶域を指す引数は番地空間でも重なる")
    void preservesAliasingBetweenArguments() {
        Assembler.Result result = Assembler.assemble("TEST.asm", String.join("\n",
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     2,0(0,1)         引数 1",
                "         L     3,4(0,1)         引数 2",
                "         MVI   0(2),X'11'",
                "         MVC   8(1,2),0(3)      引数 2 から読んで別の場所へ",
                "         BR    14",
                "         END"));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        Storage storage = Storage.allocate(16);
        DataView whole = storage.view(0, 16);
        DataView same = storage.view(0, 16);
        HlasmRuntime.execute(result.module(), null, new DataView[] {whole, same});
        // 引数 1 へ書いた X'11' が、引数 2 を通して読めた
        assertEquals((byte) 0x11, storage.array()[8]);
    }

    // --- 転記・比較・分岐 ---

    @Test
    @DisplayName("MVC は重なった領域を 1 バイトずつ左から写す")
    void movesOverlappingAreasLeftToRight() {
        // X'11' を 1 バイト置き、そこから 1 バイトずらして 7 バイト写すと領域全体に広がる
        byte[] result = run(new byte[8],
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     2,0(0,1)",
                "         MVI   0(2),X'11'",
                "         MVC   1(7,2),0(2)",
                "         BR    14",
                "         END");
        assertArrayEquals(new byte[] {0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x11}, result);
    }

    @Test
    @DisplayName("CLC の結果で分岐する")
    void branchesOnComparison() {
        byte[] result = run(new byte[] {1, 2},
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     2,0(0,1)",
                "         CLC   0(1,2),1(2)      1 と 2 を比べる",
                "         BNL   HIGH",
                "         MVI   0(2),X'AA'       低いほうへ来た",
                "         BR    14",
                "HIGH     MVI   0(2),X'BB'",
                "         BR    14",
                "         END");
        assertEquals((byte) 0xAA, result[0]);
    }

    @Test
    @DisplayName("BCT は 0 になるまで回る")
    void loopsWithBranchOnCount() {
        byte[] result = run(new byte[1],
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     2,0(0,1)",
                "         LA    3,5              5 回",
                "         SR    4,4",
                "LOOP     LA    4,1(0,4)         1 ずつ足す",
                "         BCT   3,LOOP",
                "         STC   4,0(0,2)",
                "         BR    14",
                "         END");
        assertEquals(5, result[0]);
    }

    /**
     * レジスタの値へ飛ぶ。行き先は実行時にしか決まらないので、各命令を JVM のラベルへ
     * 展開する方式では静的に閉じられない。命令インタプリタならそのまま扱える。
     */
    @Test
    @DisplayName("BALR と BR でレジスタの値へ飛べる")
    void branchesToAComputedAddress() {
        byte[] result = run(new byte[1],
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     2,0(0,1)",
                "         BAL   6,SUB            戻り番地を 6 に置いて呼ぶ",
                "         BR    14",
                "SUB      MVI   0(2),X'77'",
                "         BR    6                レジスタの値へ戻る",
                "         END");
        assertEquals((byte) 0x77, result[0]);
    }

    // --- EX (自己書き換え) ---

    /**
     * {@code EX} は対象の命令の 2 バイト目を実行時に差し替える。長さが実行時に決まる
     * {@code MVC} の定石であり、実資産にほぼ必ず出る。
     */
    @Test
    @DisplayName("EX は MVC の長さを実行時に差し替える")
    void executesWithARuntimeLength() {
        byte[] result = run(new byte[8],
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     2,0(0,1)",
                "         MVI   0(2),X'33'",
                "         LA    5,2              長さ 3 = 欄の値 2",
                "         EX    5,MOVEIT",
                "         BR    14",
                "MOVEIT   MVC   1(1,2),0(2)      長さは EX が差し替える",
                "         END");
        // 長さ 3 に差し替わったので 4 バイトが X'33' になる
        assertArrayEquals(new byte[] {0x33, 0x33, 0x33, 0x33, 0, 0, 0, 0}, result);
    }

    @Test
    @DisplayName("EX が EX を実行すれば実行例外である")
    void rejectsExecuteOfExecute() {
        HlasmRuntime.HlasmExecutionException failure = assertThrows(
                HlasmRuntime.HlasmExecutionException.class,
                () -> returnCode(
                        "TEST     CSECT",
                        "         USING TEST,15",
                        "         SR    5,5",
                        "         EX    5,INNER",
                        "         BR    14",
                        "INNER    EX    0,INNER",
                        "         END"));
        assertTrue(failure.getMessage().contains("S0C3"), failure.getMessage());
    }

    // --- 2 進算術 ---

    @Test
    @DisplayName("M は偶数・奇数の対に積を置く")
    void multipliesIntoARegisterPair() {
        assertEquals(6, returnCode(
                "TEST     CSECT",
                "         USING TEST,15",
                "         LA    5,3",
                "         LA    4,0",
                "         LA    6,2",
                "         MR    4,6",
                "         LR    15,5",
                "         BR    14",
                "         END"));
    }

    @Test
    @DisplayName("M に奇数のレジスタを与えれば仕様例外である")
    void rejectsOddRegisterForMultiply() {
        HlasmRuntime.HlasmExecutionException failure = assertThrows(
                HlasmRuntime.HlasmExecutionException.class,
                () -> returnCode(
                        "TEST     CSECT",
                        "         MR    5,6",
                        "         BR    14",
                        "         END"));
        assertTrue(failure.getMessage().contains("S0C6"), failure.getMessage());
    }

    @Test
    @DisplayName("D は商を奇数側、剰余を偶数側に置く")
    void dividesIntoARegisterPair() {
        assertEquals(1, returnCode(
                "TEST     CSECT",
                "         USING TEST,15",
                "         SR    4,4",
                "         LA    5,7",
                "         LA    6,3",
                "         DR    4,6",
                "         LR    15,4             剰余は 1",
                "         BR    14",
                "         END"));
    }

    @Test
    @DisplayName("TM はマスクで選んだビットを調べる")
    void testsBitsUnderMask() {
        byte[] result = run(new byte[] {(byte) 0xC0},
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     2,0(0,1)",
                "         TM    0(2),X'C0'       どちらも 1",
                "         BO    ALLONES",
                "         MVI   0(2),X'01'",
                "         BR    14",
                "ALLONES  MVI   0(2),X'02'",
                "         BR    14",
                "         END");
        assertEquals(2, result[0]);
    }

    // --- 10 進命令 ---

    @Test
    @DisplayName("AP はパック 10 進を足す")
    void addsPackedDecimal() {
        byte[] result = run(new byte[] {0x00, 0x12, 0x3C, 0x00, 0x04, 0x5C},
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     2,0(0,1)",
                "         AP    0(3,2),3(3,2)",
                "         BR    14",
                "         END");
        // 123 + 45 = 168
        assertArrayEquals(new byte[] {0x00, 0x16, (byte) 0x8C, 0x00, 0x04, 0x5C}, result);
    }

    /**
     * ゼロ結果の符号は命令ごとに違う。{@code AP} と {@code SP} は常に正になる。
     * これは Principles of Operation から読み取れず、Hercules での実測で分かったことである
     * (暫定判断 P-001)。{@code cobol-runtime} の {@link dev.cobolonjava.runtime.decimal.Decimal}
     * がその実測を持っているので、ここで書き直さずに委ねている。
     */
    @Test
    @DisplayName("AP のゼロ結果は、両方が負でも正の符号になる")
    void normalizesZeroToPositiveOnDecimalAdd() {
        byte[] result = run(new byte[] {0x00, 0x0D, 0x00, 0x0D},
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     2,0(0,1)",
                "         AP    0(2,2),2(2,2)",
                "         BR    14",
                "         END");
        assertEquals((byte) 0x0C, result[1]);
    }

    @Test
    @DisplayName("CP の結果で分岐する")
    void branchesOnDecimalComparison() {
        byte[] result = run(new byte[] {0x12, 0x3C, 0x45, 0x6C, 0x00},
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     2,0(0,1)",
                "         CP    0(2,2),2(2,2)",
                "         BL    LOW",
                "         MVI   4(2),X'01'",
                "         BR    14",
                "LOW      MVI   4(2),X'02'",
                "         BR    14",
                "         END");
        assertEquals(2, result[4]);
    }

    @Test
    @DisplayName("PACK はゾーン 10 進をパック 10 進にする")
    void packsZonedDecimal() {
        byte[] result = run(new byte[] {(byte) 0xF1, (byte) 0xF2, (byte) 0xC3, 0x00, 0x00},
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     2,0(0,1)",
                "         PACK  3(2,2),0(3,2)",
                "         BR    14",
                "         END");
        assertArrayEquals(new byte[] {(byte) 0xF1, (byte) 0xF2, (byte) 0xC3, 0x12, 0x3C}, result);
    }

    @Test
    @DisplayName("UNPK はパック 10 進をゾーン 10 進にする")
    void unpacksPackedDecimal() {
        byte[] result = run(new byte[] {0x12, 0x3C, 0x00, 0x00, 0x00},
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     2,0(0,1)",
                "         UNPK  2(3,2),0(2,2)",
                "         BR    14",
                "         END");
        assertArrayEquals(new byte[] {0x12, 0x3C, (byte) 0xF1, (byte) 0xF2, (byte) 0xC3}, result);
    }

    /**
     * 10 進数として読めないバイト列はデータ例外である。COBOL 資産が見慣れた {@code S0C7} は
     * これである。黙って 0 として読まない。
     */
    @Test
    @DisplayName("10 進として読めないバイト列は S0C7 になる")
    void raisesDataExceptionOnInvalidPackedDecimal() {
        HlasmRuntime.HlasmExecutionException failure = assertThrows(
                HlasmRuntime.HlasmExecutionException.class,
                () -> run(new byte[] {(byte) 0xAB, (byte) 0xCD},
                        "TEST     CSECT",
                        "         USING TEST,15",
                        "         L     2,0(0,1)",
                        "         AP    0(2,2),0(2,2)",
                        "         BR    14",
                        "         END"));
        assertTrue(failure.getMessage().contains("S0C7"), failure.getMessage());
    }

    @Test
    @DisplayName("CVD と CVB は 2 進と 10 進を往復する")
    void convertsBetweenBinaryAndDecimal() {
        assertEquals(1234, returnCode(
                "TEST     CSECT",
                "         USING TEST,15",
                "         L     3,VALUE",
                "         CVD   3,WORK",
                "         CVB   15,WORK",
                "         BR    14",
                "VALUE    DC    F'1234'",
                "WORK     DS    D",
                "         END"));
    }

    // --- 断ると決めたもの ---

    @Test
    @DisplayName("SVC は増分 3 の範囲として断る")
    void rejectsSupervisorCall() {
        HlasmRuntime.HlasmExecutionException failure = assertThrows(
                HlasmRuntime.HlasmExecutionException.class,
                () -> returnCode(
                        "TEST     CSECT",
                        "         SVC   35",
                        "         BR    14",
                        "         END"));
        assertTrue(failure.getMessage().contains("not supported yet"), failure.getMessage());
    }

    /**
     * 実行できない命令は演算例外 (S0C1) として断る。黙って近い振る舞いで通さない。
     *
     * <p>以前はここで {@code ED} を使っていたが、組み立てられる命令はすべて実行できるように
     * なった。命令コード 00 は z/Architecture に無い。
     */
    @Test
    @DisplayName("実行できない命令は、演算例外として断る")
    void refusesInstructionsItCannotExecute() {
        HlasmRuntime.HlasmExecutionException failure = assertThrows(
                HlasmRuntime.HlasmExecutionException.class,
                () -> returnCode(
                        "TEST     CSECT",
                        "         DC    X'0000'",
                        "         BR    14",
                        "         END"));
        assertTrue(failure.getMessage().contains("S0C1"), failure.getMessage());
    }

    @Test
    @DisplayName("区画の外を読めばアドレッシング例外である")
    void raisesAddressingExceptionOutsideEveryRegion() {
        HlasmRuntime.HlasmExecutionException failure = assertThrows(
                HlasmRuntime.HlasmExecutionException.class,
                () -> returnCode(
                        "TEST     CSECT",
                        "         SR    2,2",
                        "         L     3,0(0,2)         番地 0 を読む",
                        "         BR    14",
                        "         END"));
        assertTrue(failure.getMessage().contains("S0C5"), failure.getMessage());
    }

    /** 返ってこないプログラムは見捨てる。測定が 1 本で止まらないためである。 */
    @Test
    @DisplayName("返ってこないプログラムは命令数の上限で見捨てる")
    void abandonsAProgramThatNeverReturns() {
        Assembler.Result result = Assembler.assemble("TEST.asm", String.join("\n",
                "TEST     CSECT",
                "         USING TEST,15",
                "LOOP     B     LOOP",
                "         END"));
        assertTrue(result.succeeded(), () -> "diagnostics: " + result.diagnostics());
        AddressSpace.Builder builder = AddressSpace.builder();
        int base = builder.place("TEST", result.module().text());
        Cpu cpu = new Cpu(builder.build(), 1000);
        // USING TEST,15 で組み立てているので、ベースレジスタを入口の番地にしておく
        cpu.setRegister(15, base);
        RunawayProgramException failure =
                assertThrows(RunawayProgramException.class, () -> cpu.run(base));
        assertEquals(1000, failure.steps());
        assertEquals(1001, cpu.steps());
    }
}
