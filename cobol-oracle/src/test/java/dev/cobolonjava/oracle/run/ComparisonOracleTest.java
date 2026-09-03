package dev.cobolonjava.oracle.run;

import static dev.cobolonjava.oracle.OracleSupport.bytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.oracle.OracleSupport;
import dev.cobolonjava.oracle.machine.Insn;
import dev.cobolonjava.oracle.script.HerculesCase;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.verb.Compare;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>検証レベル V2</b>: 数値比較を、Hercules 上で実行した {@code CP} 命令の
 * 条件コードと突き合わせる (要件 FR-046)。
 *
 * <p>比較結果は記憶域ではなく条件コードに残るため、そのままでは読み出せない。
 * {@code IPM} でレジスタへ取り出し、{@code ST} で記憶域へ格納してから採取している。
 */
@Tag("V2")
class ComparisonOracleTest {

    /** 条件コードを保持する汎用レジスタ。 */
    private static final int CC_REGISTER = 1;

    private static HerculesRunner runner;

    @BeforeAll
    static void setUp() {
        runner = OracleSupport.requireHercules();
    }

    /** 1 件の比較。 */
    private record Pair(String leftHex, String rightHex) {
        @Override
        public String toString() {
            return leftHex + " vs " + rightHex;
        }
    }

    @Test
    @DisplayName("CP の条件コードが数値比較の結果と一致する (FR-046)")
    void comparisonMatchesHost() throws Exception {
        List<Pair> pairs = List.of(
                new Pair("12345C", "12345C"),
                new Pair("12345C", "12346C"),
                new Pair("12346C", "12345C"),
                new Pair("00000C", "00000D"),
                new Pair("00005D", "00005C"),
                new Pair("00005D", "00005D"),
                new Pair("99999C", "00001C"),
                new Pair("00001D", "99999D"),
                // 桁数の違う項目どうしの比較
                new Pair("12345C", "345C"),
                new Pair("00345C", "345C")
        );

        HerculesCase c = new HerculesCase("cp");
        int[] ccAddr = new int[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            byte[] left = bytes(pairs.get(i).leftHex());
            byte[] right = bytes(pairs.get(i).rightHex());
            int a1 = c.data(left);
            int a2 = c.data(right);
            ccAddr[i] = c.reserve(4);
            c.emit(Insn.cp(a1, left.length, a2, right.length));
            c.emit(Insn.ipm(CC_REGISTER));
            c.emit(Insn.st(CC_REGISTER, ccAddr[i]));
        }
        for (int i = 0; i < pairs.size(); i++) {
            c.dump("cc " + pairs.get(i), ccAddr[i], 4);
        }

        HerculesResult r = runner.run(c);
        assertTrue(r.completed(), () -> "Hercules で割込みが発生した。ログ:\n" + r.log());

        for (int i = 0; i < pairs.size(); i++) {
            Pair p = pairs.get(i);
            // IPM が格納したワードの先頭バイトのビット 2〜3 が条件コードである
            int hostConditionCode = (r.at(ccAddr[i], 4)[0] >> 4) & 0x03;

            Decimal left = PackedDecimal.decode(bytes(p.leftHex()), 0, NumProcMode.NOPFD);
            Decimal right = PackedDecimal.decode(bytes(p.rightHex()), 0, NumProcMode.NOPFD);
            int mine = Compare.toConditionCode(Compare.numeric(left, right));

            assertEquals(hostConditionCode, mine, () -> String.format(
                    "%s: Hercules CC=%d, cobol-runtime CC=%d", p, hostConditionCode, mine));
        }
    }

    @Test
    @DisplayName("負のゼロと正のゼロは実機でも等しいと判定される (FR-046)")
    void negativeZeroComparesEqualOnHost() throws Exception {
        HerculesCase c = new HerculesCase("cp-zero");
        int a1 = c.data(bytes("00000D"));
        int a2 = c.data(bytes("00000C"));
        int cc = c.reserve(4);
        c.emit(Insn.cp(a1, 3, a2, 3));
        c.emit(Insn.ipm(CC_REGISTER));
        c.emit(Insn.st(CC_REGISTER, cc));
        c.dump("cc", cc, 4);

        HerculesResult r = runner.run(c);
        assertTrue(r.completed());
        assertEquals(0, (r.at(cc, 4)[0] >> 4) & 0x03,
                "-0 と +0 は等しい (条件コード 0)");
    }
}
