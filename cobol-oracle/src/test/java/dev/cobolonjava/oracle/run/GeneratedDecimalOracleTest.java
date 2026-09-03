package dev.cobolonjava.oracle.run;

import static dev.cobolonjava.oracle.OracleSupport.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.oracle.OracleSupport;
import dev.cobolonjava.oracle.cases.DecimalCase;
import dev.cobolonjava.oracle.cases.DecimalCaseGenerator;
import dev.cobolonjava.oracle.script.HerculesCase;
import dev.cobolonjava.runtime.data.NumProcMode;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.decimal.Decimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>検証レベル V2</b>: 合成ジェネレータ (要件 NFR-041) が生成したテストケースを
 * すべて Hercules で実行し、ランタイムの結果とバイト列で突き合わせる。
 *
 * <p>手で選んだ代表値ではなく、境界値の全列挙と 2-way カバリングアレイによって
 * 機械的に導かれた組み合わせを対象にする。人力では到達できない網羅性を得ることが目的である。
 */
@Tag("V2")
class GeneratedDecimalOracleTest {

    /**
     * 1 回の Hercules 実行に載せるケース数。
     *
     * <p>命令列は {@code 0x200} から {@code 0x380} までの 384 バイトに収める必要があり、
     * SS 形式の命令は 1 個 6 バイトである。終了用の {@code LPSWE} のぶんを残して 50 とする。
     */
    private static final int CASES_PER_RUN = 50;

    private static HerculesRunner runner;

    @BeforeAll
    static void setUp() {
        runner = OracleSupport.requireHercules();
    }

    @Test
    @DisplayName("生成された全ケースでランタイムとホストの結果が一致する (NFR-041)")
    void generatedCasesMatchHost() throws Exception {
        List<DecimalCase> cases = DecimalCaseGenerator.generate();
        assertTrue(cases.size() >= 121,
                "pairwise の行数は最大 2 因子の積 (11 x 11) 以上のはず: " + cases.size());

        int checked = 0;
        for (int start = 0; start < cases.size(); start += CASES_PER_RUN) {
            List<DecimalCase> batch = cases.subList(start, Math.min(start + CASES_PER_RUN, cases.size()));
            checked += runBatch("gen-" + (start / CASES_PER_RUN), batch);
        }
        assertEquals(cases.size(), checked);

        System.out.printf("合成ジェネレータ: %d 件を検証 (完全列挙なら %d 件)%n",
                cases.size(), DecimalCaseGenerator.exhaustiveCount());
    }

    /** 1 バッチを実行し、突き合わせたケース数を返す。 */
    private int runBatch(String name, List<DecimalCase> batch) throws Exception {
        HerculesCase c = new HerculesCase(name);
        int[] target = new int[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            DecimalCase dc = batch.get(i);
            byte[] left = dc.leftBytes();
            byte[] right = dc.rightBytes();
            target[i] = c.data(left);
            int rightAddr = c.data(right);
            c.emit(dc.operation().instruction(target[i], left.length, rightAddr, right.length));
        }
        for (int i = 0; i < batch.size(); i++) {
            c.dump(batch.get(i).toString(), target[i], batch.get(i).byteLength());
        }

        HerculesResult r = runner.run(c);
        assertTrue(r.completed(), () -> String.format(
                "Hercules で割込みが発生した (待機 PSW アドレス 0x%X)。ログ:%n%s",
                r.waitPswAddress(), r.log()));

        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            DecimalCase dc = batch.get(i);
            String host = hex(r.at(target[i], dc.byteLength()));

            Decimal left = PackedDecimal.decode(dc.leftBytes(), 0, NumProcMode.NOPFD);
            Decimal right = PackedDecimal.decode(dc.rightBytes(), 0, NumProcMode.NOPFD);
            String mine = hex(PackedDecimal.encode(
                    dc.operation().apply(left, right), dc.digits(), 0, true));

            if (!host.equals(mine)) {
                mismatches.add(String.format("%s: %s %s -> Hercules=%s, cobol-runtime=%s",
                        dc, hex(dc.leftBytes()), hex(dc.rightBytes()), host, mine));
            }
        }
        assertTrue(mismatches.isEmpty(),
                () -> mismatches.size() + " 件が不一致:\n" + String.join("\n", mismatches));
        return batch.size();
    }
}
