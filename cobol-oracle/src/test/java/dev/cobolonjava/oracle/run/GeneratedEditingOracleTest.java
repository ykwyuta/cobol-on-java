package dev.cobolonjava.oracle.run;

import static dev.cobolonjava.oracle.OracleSupport.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.oracle.OracleSupport;
import dev.cobolonjava.oracle.cases.EditPictureCaseGenerator;
import dev.cobolonjava.oracle.machine.EditMask;
import dev.cobolonjava.oracle.machine.Insn;
import dev.cobolonjava.oracle.script.HerculesCase;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.data.PackedDecimal;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.picture.NumericEditor;
import dev.cobolonjava.runtime.picture.Picture;
import dev.cobolonjava.runtime.picture.PictureParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>検証レベル V2</b>: 機械的に生成した PICTURE と値の組み合わせで、
 * {@code NumericEditor} を実機の {@code ED} 命令と突き合わせる (要件 FR-030, NFR-041)。
 *
 * <p>PICTURE の形は pairwise で縮約し、各 PICTURE に対して値の境界は全列挙する
 * (決定事項 D-15)。
 */
@Tag("V2")
class GeneratedEditingOracleTest {

    private static final CodePage CP = CodePages.IBM_1047;
    private static final int CASES_PER_RUN = 25;

    private static HerculesRunner runner;

    @BeforeAll
    static void setUp() {
        runner = OracleSupport.requireHercules();
    }

    @Test
    @DisplayName("生成された全ケースで数値編集がホストと一致する (FR-030, NFR-041)")
    void generatedEditingMatchesHost() throws Exception {
        List<EditPictureCaseGenerator.Case> cases = EditPictureCaseGenerator.generate();
        assertTrue(cases.size() >= 50, "生成されたケース数: " + cases.size());

        for (int start = 0; start < cases.size(); start += CASES_PER_RUN) {
            List<EditPictureCaseGenerator.Case> batch =
                    cases.subList(start, Math.min(start + CASES_PER_RUN, cases.size()));
            runBatch("gen-ed-" + (start / CASES_PER_RUN), batch);
        }

        System.out.printf("編集の合成ジェネレータ: PICTURE %d 種 x 値 %d 種 = %d 件を検証%n",
                EditPictureCaseGenerator.pictures().size(),
                dev.cobolonjava.oracle.cases.EditValueClass.values().length, cases.size());
    }

    private void runBatch(String name, List<EditPictureCaseGenerator.Case> batch) throws Exception {
        HerculesCase c = new HerculesCase(name);
        List<Picture> pictures = new ArrayList<>();
        List<byte[]> masks = new ArrayList<>();
        List<Integer> sourceNibbles = new ArrayList<>();
        int[] maskAddr = new int[batch.size()];

        for (int i = 0; i < batch.size(); i++) {
            var ec = batch.get(i);
            Picture p = PictureParser.parse(ec.picture());
            Decimal value = ec.value().value(p.digits(), p.scale());
            byte[] source = PackedDecimal.encode(value, p.digits(), p.scale(), true);
            int nibbles = source.length * 2 - 1;
            byte[] mask = EditMask.forPicture(p, CP, nibbles);

            maskAddr[i] = c.data(mask);
            int sourceAddr = c.data(source);
            c.emit(Insn.ed(maskAddr[i], mask.length, sourceAddr));

            pictures.add(p);
            masks.add(mask);
            sourceNibbles.add(nibbles);
        }
        for (int i = 0; i < batch.size(); i++) {
            c.dump(batch.get(i).toString(), maskAddr[i], masks.get(i).length);
        }

        HerculesResult r = runner.run(c);
        assertTrue(r.completed(), () -> String.format(
                "Hercules で割込みが発生した (待機 PSW アドレス 0x%X)。ログ:%n%s",
                r.waitPswAddress(), r.log()));

        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            var ec = batch.get(i);
            Picture p = pictures.get(i);
            int offset = EditMask.editedFieldOffset(p, sourceNibbles.get(i));
            byte[] host = r.at(maskAddr[i] + offset, p.size());
            byte[] mine = NumericEditor.edit(ec.value().value(p.digits(), p.scale()), p, CP);
            if (!hex(host).equals(hex(mine))) {
                mismatches.add(String.format("%s%n    Hercules ED  : [%s]%n    NumericEditor: [%s]",
                        ec, CP.decode(host), CP.decode(mine)));
            }
        }
        assertEquals(List.of(), mismatches,
                mismatches.size() + " 件が不一致");
    }
}
