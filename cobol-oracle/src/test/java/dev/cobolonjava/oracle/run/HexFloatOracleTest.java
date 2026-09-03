package dev.cobolonjava.oracle.run;

import static dev.cobolonjava.oracle.OracleSupport.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.oracle.OracleSupport;
import dev.cobolonjava.oracle.machine.Insn;
import dev.cobolonjava.oracle.script.HerculesCase;
import dev.cobolonjava.runtime.hfp.HexFloat;
import dev.cobolonjava.runtime.hfp.HexFloatArithmetic;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BinaryOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>検証レベル V2</b>: IBM 16 進浮動小数点 (HFP) を実機の HFP 命令と突き合わせる (要件 FR-032)。
 *
 * <p>ここで確かめたいことは 2 つある。
 *
 * <ol>
 *   <li>{@link HexFloat} のビット表現が実機と一致していること</li>
 *   <li>ランタイム側の演算 (厳密に計算してから切り捨て) が実機の演算と一致するかどうか。
 *       ハードウェアは指数を揃える際に桁を捨てるため、<b>一致しない可能性がある</b>。
 *       一致しないならそれ自体が知見である</li>
 * </ol>
 */
@Tag("V2")
class HexFloatOracleTest {

    /** 除算の中間精度。切り捨ての位置に影響しないだけの桁数を取る。 */
    private static final MathContext DIVIDE_PRECISION = new MathContext(60, RoundingMode.DOWN);

    private static final int FP_REGISTER = 0;

    private static HerculesRunner runner;

    @BeforeAll
    static void setUp() {
        runner = OracleSupport.requireHercules();
    }

    private record Op(String name,
                      java.util.function.BiFunction<Integer, Integer, byte[]> longInstruction,
                      BinaryOperator<byte[]> runtime) {
    }

    private static final List<Op> OPERATIONS = List.of(
            new Op("AD", (r, a) -> Insn.ad(r, a), HexFloatArithmetic::addLong),
            new Op("SD", (r, a) -> Insn.sd(r, a), HexFloatArithmetic::subtractLong),
            new Op("MD", (r, a) -> Insn.md(r, a), HexFloatArithmetic::multiplyLong),
            new Op("DD", (r, a) -> Insn.dd(r, a), HexFloatArithmetic::divideLong));

    /** 16 進浮動小数点で厳密に表せる値。表現と演算の両方が素直に一致するはずの組。 */
    private static final List<String> EXACT_VALUES =
            List.of("1", "2", "3", "0.5", "0.25", "16", "-1", "-0.5", "256", "0.0625");

    @Test
    @DisplayName("厳密に表せる値どうしの長形式 HFP 演算が実機と一致する (FR-032)")
    void exactValuesMatchHost() throws Exception {
        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; i < EXACT_VALUES.size(); i++) {
            String a = EXACT_VALUES.get(i);
            String b = EXACT_VALUES.get((i + 3) % EXACT_VALUES.size());
            pairs.add(new String[] {a, b});
        }
        for (Op op : OPERATIONS) {
            assertMatches(op, pairs);
        }
    }

    @Test
    @DisplayName("厳密に表せない値を含む長形式 HFP 演算が実機と一致する (FR-032)")
    void inexactValuesMatchHost() throws Exception {
        List<String[]> pairs = List.of(
                new String[] {"0.1", "0.2"},
                new String[] {"1", "3"},
                new String[] {"1", "7"},
                new String[] {"123.456", "789.012"},
                new String[] {"0.1", "0.1"},
                new String[] {"1000000", "3"});
        for (Op op : OPERATIONS) {
            assertMatches(op, pairs);
        }
    }

    @Test
    @DisplayName("指数差が大きい場合や桁落ちする場合も実機と一致する (FR-032)")
    void adversarialCasesMatchHost() throws Exception {
        // ハードウェアは加減算で指数を揃える際に桁を捨てる (ガード桁は 1 桁だけ残る)。
        // 「厳密に計算してから切り捨てる」実装と食い違うとすれば、この種の入力である。
        List<String[]> pairs = List.of(
                // 指数差が大きく、小さいほうが揃えたときにほぼ消える
                new String[] {"1", "0.00000000000000001"},
                new String[] {"1", "0.000000000000000001"},
                new String[] {"16", "0.0000000000000001"},
                // 桁落ち (近い値どうしの減算)
                new String[] {"1", "0.9999999999999999"},
                new String[] {"1.0000000000000001", "1"},
                // 指数差が極端で、小さいほうが完全に消える
                new String[] {"1", "1e-30"},
                new String[] {"1e30", "1"});
        for (Op op : OPERATIONS) {
            assertMatches(op, pairs);
        }
    }

    @Test
    @DisplayName("短形式 (COMP-1) の表現が実機のロード・格納と一致する (FR-032)")
    void shortFormLoadStoreMatchesHost() throws Exception {
        HerculesCase c = new HerculesCase("hfp-short");
        List<String> values = List.of("1", "-1", "0.5", "16", "0.1", "15");
        int[] target = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            int src = c.data(HexFloat.encodeShort(new BigDecimal(values.get(i))));
            target[i] = c.reserve(HexFloat.SHORT_BYTES);
            c.emit(Insn.le(FP_REGISTER, src));
            c.emit(Insn.ste(FP_REGISTER, target[i]));
        }
        for (int i = 0; i < values.size(); i++) {
            c.dump(values.get(i), target[i], HexFloat.SHORT_BYTES);
        }
        HerculesResult r = runner.run(c);
        assertTrue(r.completed(), () -> "Hercules で割込みが発生した。ログ:\n" + r.log());

        for (int i = 0; i < values.size(); i++) {
            byte[] expected = HexFloat.encodeShort(new BigDecimal(values.get(i)));
            assertEquals(hex(expected), hex(r.at(target[i], HexFloat.SHORT_BYTES)),
                    "短形式のビット表現が実機と一致しない: " + values.get(i));
        }
    }

    private void assertMatches(Op op, List<String[]> pairs) throws Exception {
        HerculesCase c = new HerculesCase("hfp-" + op.name().toLowerCase());
        int[] target = new int[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            int left = c.data(HexFloat.encodeLong(new BigDecimal(pairs.get(i)[0])));
            int right = c.data(HexFloat.encodeLong(new BigDecimal(pairs.get(i)[1])));
            target[i] = c.reserve(HexFloat.LONG_BYTES);
            c.emit(Insn.ld(FP_REGISTER, left));
            c.emit(op.longInstruction().apply(FP_REGISTER, right));
            c.emit(Insn.std(FP_REGISTER, target[i]));
        }
        for (int i = 0; i < pairs.size(); i++) {
            c.dump(op.name() + " " + pairs.get(i)[0] + "," + pairs.get(i)[1],
                    target[i], HexFloat.LONG_BYTES);
        }

        HerculesResult r = runner.run(c);
        assertTrue(r.completed(), () -> String.format(
                "Hercules で割込みが発生した (待機 PSW アドレス 0x%X)。ログ:%n%s",
                r.waitPswAddress(), r.log()));

        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            String a = pairs.get(i)[0];
            String b = pairs.get(i)[1];
            String host = hex(r.at(target[i], HexFloat.LONG_BYTES));

            byte[] left = HexFloat.encodeLong(new BigDecimal(a));
            byte[] right = HexFloat.encodeLong(new BigDecimal(b));
            String mine = hex(op.runtime().apply(left, right));

            if (!host.equals(mine)) {
                mismatches.add(String.format("%s %s,%s -> Hercules=%s, cobol-runtime=%s",
                        op.name(), a, b, host, mine));
            }
        }
        assertEquals(List.of(), mismatches,
                op.name() + ": " + mismatches.size() + " 件が不一致");
    }
}
