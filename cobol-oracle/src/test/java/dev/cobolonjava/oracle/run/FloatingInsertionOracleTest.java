package dev.cobolonjava.oracle.run;

import static dev.cobolonjava.oracle.OracleSupport.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.oracle.OracleSupport;
import dev.cobolonjava.oracle.cases.EditValueClass;
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
 * <b>検証レベル V2</b>: 浮動挿入 ({@code $$$,$$9.99}、{@code ----9} など) を
 * 実機の {@code EDMK} 命令と突き合わせる (要件 FR-030、provisional.md P-014)。
 *
 * <h2>浮動挿入がどう実現されるか</h2>
 * <p>平の {@code ED} では浮動挿入は表現できない。記号を置く位置が値によって変わるためである。
 * {@code EDMK} は {@code ED} と同じ編集を行ったうえで、<b>最初の有効数字の位置</b>を
 * 汎用レジスタ 1 に残す。そこから 1 を引いた位置へ記号を書き込めば浮動挿入になる。
 *
 * <pre>
 * LA   1,&lt;有意性開始子の 1 つ後ろ&gt;   値がゼロで EDMK がレジスタを更新しない場合に備える
 * EDMK マスク,ソース                   編集し、最初の有効数字の位置をレジスタ 1 へ
 * BCTR 1,0                            1 つ手前へ
 * MVI  0(1),記号                       そこへ記号を書き込む
 * </pre>
 *
 * <p>これは参照実装のコンパイラが生成しているであろう命令列そのものである。
 * 本テストで {@code NumericEditor} の出力と一致することを確認できれば、P-014 の
 * 浮動挿入の部分が V2 へ上がる。
 *
 * <p>なお記号そのものの選択 ({@code +} なら符号に応じて {@code +} / {@code -}、
 * {@code -} なら負のときだけ {@code -}) は本テストでは Java 側で決めている。
 * 実機側で分岐させるには条件分岐命令が必要であり、検証の焦点である<b>記号を置く位置</b>とは
 * 別の話であるためである。位置は完全に実機が決めている。
 */
@Tag("V2")
class FloatingInsertionOracleTest {

    private static final CodePage CP = CodePages.IBM_1047;
    private static final int WORK_REGISTER = 1;
    private static final int CASES_PER_RUN = 12;

    private static HerculesRunner runner;

    @BeforeAll
    static void setUp() {
        runner = OracleSupport.requireHercules();
    }

    private record Case(String picture, EditValueClass value) {
        @Override
        public String toString() {
            return picture + " <- " + value;
        }
    }

    @Test
    @DisplayName("浮動する通貨記号がホストの EDMK と一致する (FR-030, P-014)")
    void floatingCurrencyMatchesHost() throws Exception {
        runCases("edmk-cur", buildCases("$$$,$$9.99", "$$$9.99", "$$$$9"));
    }

    @Test
    @DisplayName("浮動する符号がホストの EDMK と一致する (FR-030, P-014)")
    void floatingSignMatchesHost() throws Exception {
        runCases("edmk-sign", buildCases("----9", "---9.99", "+++9.99"));
    }

    private static List<Case> buildCases(String... pictures) {
        List<Case> cases = new ArrayList<>();
        for (String p : pictures) {
            for (EditValueClass v : EditValueClass.values()) {
                cases.add(new Case(p, v));
            }
        }
        return cases;
    }

    private void runCases(String prefix, List<Case> cases) throws Exception {
        for (int start = 0; start < cases.size(); start += CASES_PER_RUN) {
            List<Case> batch = cases.subList(start, Math.min(start + CASES_PER_RUN, cases.size()));
            runBatch(prefix + "-" + (start / CASES_PER_RUN), batch);
        }
    }

    private void runBatch(String name, List<Case> batch) throws Exception {
        HerculesCase c = new HerculesCase(name);
        List<Picture> pictures = new ArrayList<>();
        List<Integer> nibbles = new ArrayList<>();
        int[] maskAddr = new int[batch.size()];

        for (int i = 0; i < batch.size(); i++) {
            Case ec = batch.get(i);
            Picture p = PictureParser.parse(ec.picture());
            assertTrue(EditMask.usesFloatingInsertion(p), "浮動挿入の PICTURE でなければならない: " + p);

            Decimal value = ec.value().value(p.digits(), p.scale());
            byte[] source = PackedDecimal.encode(value, p.digits(), p.scale(), true);
            int sourceNibbles = source.length * 2 - 1;
            byte[] mask = EditMask.forPicture(p, CP, sourceNibbles);

            maskAddr[i] = c.data(mask);
            int sourceAddr = c.data(source);

            int preload = maskAddr[i] + EditMask.significanceStarterSuccessorOffset(p, sourceNibbles);
            byte symbol = CP.ch(EditMask.floatingCharacter(p, value.signum() < 0));

            c.emit(Insn.la(WORK_REGISTER, preload));
            c.emit(Insn.edmk(maskAddr[i], mask.length, sourceAddr));
            c.emit(Insn.bctr(WORK_REGISTER));
            c.emit(Insn.mvi(WORK_REGISTER, 0, symbol));

            pictures.add(p);
            nibbles.add(sourceNibbles);
        }
        for (int i = 0; i < batch.size(); i++) {
            c.dump(batch.get(i).toString(), maskAddr[i], pictures.get(i).size() + nibbles.get(i)
                    - pictures.get(i).digits() + 1);
        }

        HerculesResult r = runner.run(c);
        assertTrue(r.completed(), () -> String.format(
                "Hercules で割込みが発生した (待機 PSW アドレス 0x%X)。ログ:%n%s",
                r.waitPswAddress(), r.log()));

        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) {
            Case ec = batch.get(i);
            Picture p = pictures.get(i);
            int offset = EditMask.editedFieldOffset(p, nibbles.get(i));
            byte[] host = r.at(maskAddr[i] + offset, p.size());
            byte[] mine = NumericEditor.edit(ec.value().value(p.digits(), p.scale()), p, CP);
            if (!hex(host).equals(hex(mine))) {
                mismatches.add(String.format("%s%n    Hercules EDMK: [%s]%n    NumericEditor: [%s]",
                        ec, CP.decode(host), CP.decode(mine)));
            }
        }
        assertEquals(List.of(), mismatches, mismatches.size() + " 件が不一致");
    }
}
