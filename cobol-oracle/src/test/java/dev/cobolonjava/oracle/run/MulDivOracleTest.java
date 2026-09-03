package dev.cobolonjava.oracle.run;

import static dev.cobolonjava.oracle.OracleSupport.bytes;
import static dev.cobolonjava.oracle.OracleSupport.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.oracle.OracleSupport;
import dev.cobolonjava.oracle.cases.MulDivCase;
import dev.cobolonjava.oracle.cases.MulDivCaseGenerator;
import dev.cobolonjava.oracle.machine.Insn;
import dev.cobolonjava.oracle.script.HerculesCase;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.decimal.CobolRounding;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>検証レベル V2</b>: {@code MP} / {@code DP} を合成ジェネレータで生成した組み合わせで検証する
 * (要件 FR-044, NFR-041)。
 *
 * <p>{@code DP} は商と剰余を 1 つの項目に並べて格納する。上位 {@code l1 - l2} バイトが商、
 * 下位 {@code l2} バイトが剰余である。COBOL の {@code DIVIDE ... REMAINDER} はこの形に対応する。
 */
@Tag("V2")
class MulDivOracleTest {

    private static final int CASES_PER_RUN = 40;

    /** 10 進除算例外の割込みコード。COBOL から見た {@code S0CB} に相当する。 */
    private static final String DECIMAL_DIVIDE_EXCEPTION = "000B";
    private static final int INTERRUPTION_CODE_ADDRESS = 0x8E;

    private static HerculesRunner runner;

    @BeforeAll
    static void setUp() {
        runner = OracleSupport.requireHercules();
    }

    @Test
    @DisplayName("MP: 生成された全ケースでランタイムとホストの積が一致する (FR-044)")
    void multiplyMatchesHost() throws Exception {
        List<MulDivCase> cases = MulDivCaseGenerator.multiplyCases();
        assertTrue(cases.size() >= 11 * 11, "pairwise の行数: " + cases.size());
        runAllBatches("mp", cases, MulDivOracleTest::expectedProduct);
    }

    @Test
    @DisplayName("DP: 生成された全ケースでランタイムとホストの商・剰余が一致する (FR-044)")
    void divideMatchesHost() throws Exception {
        List<MulDivCase> cases = MulDivCaseGenerator.divideCases();
        assertTrue(cases.size() >= 9 * 11, "pairwise の行数: " + cases.size());
        runAllBatches("dp", cases, MulDivOracleTest::expectedQuotientAndRemainder);
    }

    @Test
    @DisplayName("ゼロ除算は 10 進除算例外を起こす。COBOL の S0CB に相当する (FR-141)")
    void divisionByZeroRaisesDecimalDivideException() throws Exception {
        HerculesCase c = new HerculesCase("s0cb");
        int a1 = c.data(bytes("0012345C"));
        int a2 = c.data(bytes("0C"));
        c.emit(Insn.dp(a1, 4, a2, 1));
        c.dump("interruption code", INTERRUPTION_CODE_ADDRESS, 2);

        HerculesResult r = runner.run(c);
        assertTrue(r.programCheck(), "ゼロ除算では割込みが起きるはずである");
        assertEquals(DECIMAL_DIVIDE_EXCEPTION, hex(r.at(INTERRUPTION_CODE_ADDRESS, 2)),
                "割込みコードは 10 進除算例外 (0x000B) でなければならない");

        // ランタイムも同じ入力を検出する
        Decimal dividend = PackedDecimal.decode(bytes("0012345C"), 0, NumProcMode.NOPFD);
        Decimal divisor = PackedDecimal.decode(bytes("0C"), 0, NumProcMode.NOPFD);
        assertTrue(divisor.isZero());
        org.junit.jupiter.api.Assertions.assertThrows(
                dev.cobolonjava.runtime.decimal.DecimalDivideException.class,
                () -> dividend.divide(divisor, 0, CobolRounding.TRUNCATION));
    }

    private interface Expectation {
        byte[] compute(MulDivCase c);
    }

    private void runAllBatches(String prefix, List<MulDivCase> cases, Expectation expectation)
            throws Exception {
        for (int start = 0; start < cases.size(); start += CASES_PER_RUN) {
            List<MulDivCase> batch = cases.subList(start, Math.min(start + CASES_PER_RUN, cases.size()));
            runBatch(prefix + "-" + (start / CASES_PER_RUN), batch, expectation);
        }
    }

    private void runBatch(String name, List<MulDivCase> batch, Expectation expectation)
            throws Exception {
        HerculesCase c = new HerculesCase(name);
        int[] target = new int[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            MulDivCase mc = batch.get(i);
            byte[] left = mc.leftBytes();
            byte[] right = mc.rightBytes();
            target[i] = c.data(left);
            int rightAddr = c.data(right);
            c.emit(mc.operation().instruction(target[i], mc.l1(), rightAddr, mc.l2()));
        }
        for (int i = 0; i < batch.size(); i++) {
            c.dump(batch.get(i).toString(), target[i], batch.get(i).l1());
        }

        HerculesResult r = runner.run(c);
        assertTrue(r.completed(), () -> String.format(
                "Hercules で割込みが発生した (待機 PSW アドレス 0x%X)。ログ:%n%s",
                r.waitPswAddress(), r.log()));

        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            MulDivCase mc = batch.get(i);
            String host = hex(r.at(target[i], mc.l1()));
            String mine = hex(expectation.compute(mc));
            if (!host.equals(mine)) {
                mismatches.add(String.format("%s: %s %s -> Hercules=%s, cobol-runtime=%s",
                        mc, hex(mc.leftBytes()), hex(mc.rightBytes()), host, mine));
            }
        }
        assertTrue(mismatches.isEmpty(),
                () -> mismatches.size() + " 件が不一致:\n" + String.join("\n", mismatches));
    }

    /** {@code MP} の期待値。積が第 1 オペランドの項目全体を置き換える。 */
    private static byte[] expectedProduct(MulDivCase mc) {
        Decimal left = PackedDecimal.decode(mc.leftBytes(), 0, NumProcMode.NOPFD);
        Decimal right = PackedDecimal.decode(mc.rightBytes(), 0, NumProcMode.NOPFD);
        return PackedDecimal.encode(left.multiply(right), mc.l1() * 2 - 1, 0, true);
    }

    /**
     * {@code DP} の期待値。上位 {@code l1 - l2} バイトに商、下位 {@code l2} バイトに剰余が入る。
     */
    private static byte[] expectedQuotientAndRemainder(MulDivCase mc) {
        Decimal dividend = PackedDecimal.decode(mc.leftBytes(), 0, NumProcMode.NOPFD);
        Decimal divisor = PackedDecimal.decode(mc.rightBytes(), 0, NumProcMode.NOPFD);

        int quotientBytes = mc.l1() - mc.l2();
        byte[] quotient = PackedDecimal.encode(
                dividend.divide(divisor, 0, CobolRounding.TRUNCATION), quotientBytes * 2 - 1, 0, true);
        byte[] remainder = PackedDecimal.encode(
                dividend.remainder(divisor, 0), mc.l2() * 2 - 1, 0, true);

        byte[] out = new byte[mc.l1()];
        System.arraycopy(quotient, 0, out, 0, quotientBytes);
        System.arraycopy(remainder, 0, out, quotientBytes, mc.l2());
        return out;
    }
}
