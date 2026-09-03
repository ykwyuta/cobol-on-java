package dev.cobolonjava.oracle.run;

import static dev.cobolonjava.oracle.OracleSupport.bytes;
import static dev.cobolonjava.oracle.OracleSupport.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.oracle.OracleSupport;
import dev.cobolonjava.oracle.machine.Insn;
import dev.cobolonjava.oracle.script.HerculesCase;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.decimal.CobolRounding;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BinaryOperator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>検証レベル V2</b>: cobol-runtime の 10 進演算を、Hercules 上で実行した
 * z/Architecture の 10 進命令と<b>バイト列で突き合わせる</b> (要件 4.2 節, 4.4 節)。
 *
 * <p>これが本プロジェクトにおける互換性の裏付けの中心である。期待値は仕様書の解釈ではなく、
 * 命令を実際に実行した結果である。
 *
 * <p>1 回の Hercules 起動で複数の演算をまとめて実行する。プロセス起動のコストが
 * 演算そのものより桁違いに大きいためである。
 */
@Tag("V2")
class DecimalArithmeticOracleTest {

    /** テストに用いるパック10進項目のバイト長。5 桁 + 符号ニブル。 */
    private static final int FIELD_BYTES = 3;
    private static final int FIELD_DIGITS = FIELD_BYTES * 2 - 1;

    private static HerculesRunner runner;

    @BeforeAll
    static void setUp() {
        runner = OracleSupport.requireHercules();
    }

    private interface Binary {
        byte[] insn(int addr1, int len1, int addr2, int len2);
    }

    /** 1 件の演算の指定。 */
    private record Op(String label, String operand1Hex, String operand2Hex) {
    }

    /**
     * 複数の 2 項演算を 1 回の Hercules 実行でまとめて処理し、第 1 オペランドの結果を返す。
     */
    private List<byte[]> runAll(String caseName, Binary insn, List<Op> ops) throws Exception {
        HerculesCase c = new HerculesCase(caseName);
        int[] resultAddr = new int[ops.size()];
        int[] resultLen = new int[ops.size()];
        for (int i = 0; i < ops.size(); i++) {
            byte[] op1 = bytes(ops.get(i).operand1Hex());
            byte[] op2 = bytes(ops.get(i).operand2Hex());
            int a1 = c.data(op1);
            int a2 = c.data(op2);
            c.emit(insn.insn(a1, op1.length, a2, op2.length));
            resultAddr[i] = a1;
            resultLen[i] = op1.length;
        }
        for (int i = 0; i < ops.size(); i++) {
            c.dump(ops.get(i).label(), resultAddr[i], resultLen[i]);
        }
        HerculesResult r = runner.run(c);
        assertTrue(r.completed(), () -> String.format(
                "Hercules で割込みが発生した (待機 PSW アドレス 0x%X)。ログ:%n%s",
                r.waitPswAddress(), r.log()));

        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            out.add(r.at(resultAddr[i], resultLen[i]));
        }
        return out;
    }

    /** ランタイム側で同じ演算を行い、パック10進のバイト列にする。 */
    private static byte[] viaRuntime(String op1Hex, String op2Hex, BinaryOperator<Decimal> operation) {
        Decimal a = PackedDecimal.decode(bytes(op1Hex), 0, NumProcMode.NOPFD);
        Decimal b = PackedDecimal.decode(bytes(op2Hex), 0, NumProcMode.NOPFD);
        return PackedDecimal.encode(operation.apply(a, b), FIELD_DIGITS, 0, true);
    }

    private void assertMatches(String caseName, Binary insn, List<Op> ops,
                               BinaryOperator<Decimal> operation) throws Exception {
        List<byte[]> herculesResults = runAll(caseName, insn, ops);
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            String expected = hex(herculesResults.get(i));
            String actual = hex(viaRuntime(op.operand1Hex(), op.operand2Hex(), operation));
            assertEquals(expected, actual, () -> String.format(
                    "%s: %s %s -> Hercules=%s, cobol-runtime=%s",
                    caseName, op.operand1Hex(), op.operand2Hex(), expected, actual));
        }
    }

    @Test
    @DisplayName("AP: 加算の結果がホストと一致する。ゼロ結果の符号は常に正 (FR-040)")
    void addMatchesHost() throws Exception {
        assertMatches("ap", Insn::ap, List.of(
                new Op("5+5", "00005C", "00005C"),
                new Op("5-5", "00005C", "00005D"),
                new Op("-0 + -0", "00000D", "00000D"),
                new Op("+0 + -0", "00000C", "00000D"),
                new Op("-5 + -5", "00005D", "00005D"),
                new Op("12345 + 54321", "12345C", "54321C"),
                new Op("99999 + 0", "99999C", "00000C"),
                new Op("符号ニブル A を受理", "00005A", "00005C"),
                new Op("符号ニブル B を受理", "00005B", "00005C"),
                new Op("符号ニブル F を受理", "00005F", "00005C")
        ), Decimal::add);
    }

    @Test
    @DisplayName("SP: 減算の結果がホストと一致する。ゼロ結果の符号は常に正 (FR-040)")
    void subtractMatchesHost() throws Exception {
        assertMatches("sp", Insn::sp, List.of(
                new Op("5-5", "00005C", "00005C"),
                new Op("-0 - +0", "00000D", "00000C"),
                new Op("0-5", "00000C", "00005C"),
                new Op("5-12345", "00005C", "12345C"),
                new Op("-5 - -5", "00005D", "00005D")
        ), Decimal::subtract);
    }

    @Test
    @DisplayName("MP: 積がゼロでも符号は代数の規則で決まる。負のゼロが生じる (FR-040)")
    void multiplyMatchesHost() throws Exception {
        // MP は第 1 オペランドの上位 L2 バイトがゼロでなければならない
        assertMatches("mp", Insn::mp, List.of(
                new Op("+0 x +5", "00000C", "5C"),
                new Op("+0 x -5", "00000C", "5D"),
                new Op("-0 x +5", "00000D", "5C"),
                new Op("-0 x -5", "00000D", "5D"),
                new Op("123 x 4", "00123C", "4C"),
                new Op("123 x -4", "00123C", "4D")
        ), Decimal::multiply);
    }

    @Test
    @DisplayName("ZAP: ゼロは正へ正規化され、非ゼロは元の符号を保つ")
    void zapMatchesHost() throws Exception {
        List<Op> ops = List.of(
                new Op("-0 を転記", "000000", "00000D"),
                new Op("+0 を転記", "000000", "00000C"),
                new Op("-5 を転記", "000000", "00005D"),
                new Op("+12345 を転記", "000000", "12345C")
        );
        List<byte[]> results = runAll("zap", Insn::zap, ops);
        // ZAP の意味論はランタイムの「復号して符号を保ったまま再符号化」とは異なる。
        // ゼロを正へ正規化する点が違うため、ここではホストの挙動そのものを表明する。
        assertEquals("00000C", hex(results.get(0)), "ZAP は負のゼロを正へ正規化する");
        assertEquals("00000C", hex(results.get(1)));
        assertEquals("00005D", hex(results.get(2)), "非ゼロの符号は保たれる");
        assertEquals("12345C", hex(results.get(3)));
    }

    @Test
    @DisplayName("SRP: 丸めがホストと一致する。丸め桁 5 は四捨五入、0 は切り捨て (FR-042)")
    void shiftAndRoundMatchesHost() throws Exception {
        // 3 バイト = 5 桁の項目に、小数 1 桁の値を入れて右へ 1 桁シフトする
        record Case(String label, String valueHex, int roundingDigit, CobolRounding rounding) {
        }
        List<Case> cases = List.of(
                new Case("2.5 四捨五入", "00025C", 5, CobolRounding.NEAREST_AWAY_FROM_ZERO),
                new Case("2.5 切り捨て", "00025C", 0, CobolRounding.TRUNCATION),
                new Case("-2.5 四捨五入", "00025D", 5, CobolRounding.NEAREST_AWAY_FROM_ZERO),
                new Case("-2.5 切り捨て", "00025D", 0, CobolRounding.TRUNCATION),
                new Case("3.5 四捨五入", "00035C", 5, CobolRounding.NEAREST_AWAY_FROM_ZERO),
                new Case("0.5 四捨五入", "00005C", 5, CobolRounding.NEAREST_AWAY_FROM_ZERO),
                new Case("-0.5 四捨五入", "00005D", 5, CobolRounding.NEAREST_AWAY_FROM_ZERO),
                new Case("0.4 四捨五入", "00004C", 5, CobolRounding.NEAREST_AWAY_FROM_ZERO)
        );

        HerculesCase c = new HerculesCase("srp");
        int[] addr = new int[cases.size()];
        for (int i = 0; i < cases.size(); i++) {
            byte[] v = bytes(cases.get(i).valueHex());
            addr[i] = c.data(v);
            c.emit(Insn.srp(addr[i], v.length, -1, cases.get(i).roundingDigit()));
        }
        for (int i = 0; i < cases.size(); i++) {
            c.dump(cases.get(i).label(), addr[i], FIELD_BYTES);
        }
        HerculesResult r = runner.run(c);
        assertTrue(r.completed(), () -> "Hercules で割込みが発生した。ログ:\n" + r.log());

        for (int i = 0; i < cases.size(); i++) {
            Case cs = cases.get(i);
            String expected = hex(r.at(addr[i], FIELD_BYTES));
            // ランタイム側: 小数 1 桁の値を整数へ丸める
            Decimal value = PackedDecimal.decode(bytes(cs.valueHex()), 1, NumProcMode.NOPFD);
            String actual = hex(PackedDecimal.encode(
                    value.rescale(0, cs.rounding()), FIELD_DIGITS, 0, true));
            assertEquals(expected, actual, () -> String.format(
                    "SRP %s: Hercules=%s, cobol-runtime=%s", cs.label(), expected, actual));
        }
    }
}
