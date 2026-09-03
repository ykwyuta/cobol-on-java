package dev.cobolonjava.oracle.run;

import static dev.cobolonjava.oracle.OracleSupport.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.oracle.OracleSupport;
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
 * <b>検証レベル V2</b>: {@code NumericEditor} の数値編集を、Hercules 上で実行した
 * {@code ED} 命令の結果と<b>バイト列で突き合わせる</b> (要件 FR-030、要件定義書 4.3 節)。
 *
 * <p>ホストの数値編集は文字どおり {@code ED} 命令である。したがってこの比較は、
 * 編集結果が帳票やファイルに現れるバイト列として参照実装と一致することの直接の裏付けになる。
 */
@Tag("V2")
class NumericEditingOracleTest {

    private static final CodePage CP = CodePages.IBM_1047;

    private static HerculesRunner runner;

    @BeforeAll
    static void setUp() {
        runner = OracleSupport.requireHercules();
    }

    /** 1 件の編集。 */
    private record EditCase(String picture, String value) {
        @Override
        public String toString() {
            return picture + " <- " + value;
        }
    }

    /**
     * 複数の編集を 1 回の Hercules 実行でまとめて処理し、
     * それぞれの結果を {@code NumericEditor} の出力と突き合わせる。
     */
    private void assertMatchesHost(String caseName, List<EditCase> cases) throws Exception {
        HerculesCase c = new HerculesCase(caseName);
        List<Picture> pictures = new ArrayList<>();
        List<byte[]> masks = new ArrayList<>();
        int[] maskAddr = new int[cases.size()];

        for (int i = 0; i < cases.size(); i++) {
            EditCase ec = cases.get(i);
            Picture p = PictureParser.parse(ec.picture());
            byte[] mask = EditMask.forPicture(p, CP);

            // ED はソースの符号ニブルに達すると止まる。マスクの桁位置の数と
            // ソースの数字ニブルの数は一致していなければならない。
            // パック10進項目の数字ニブル数は 2 * バイト長 - 1 であるため、
            // 桁数が奇数の PICTURE のみを対象にする。
            assertTrue(p.digits() % 2 == 1,
                    "この検証は桁数が奇数の PICTURE のみを対象にする: " + ec.picture());
            assertEquals(p.digits(), EditMask.digitPositions(mask),
                    "マスクの桁位置の数が PICTURE の桁数と一致しない: " + ec.picture());

            byte[] source = PackedDecimal.encode(
                    Decimal.parse(ec.value()), p.digits(), p.scale(), true);

            maskAddr[i] = c.data(mask);
            int sourceAddr = c.data(source);
            c.emit(Insn.ed(maskAddr[i], mask.length, sourceAddr));
            pictures.add(p);
            masks.add(mask);
        }
        for (int i = 0; i < cases.size(); i++) {
            c.dump(cases.get(i).toString(), maskAddr[i], masks.get(i).length);
        }

        HerculesResult r = runner.run(c);
        assertTrue(r.completed(), () -> String.format(
                "Hercules で割込みが発生した (待機 PSW アドレス 0x%X)。ログ:%n%s",
                r.waitPswAddress(), r.log()));

        for (int i = 0; i < cases.size(); i++) {
            EditCase ec = cases.get(i);
            // ED の結果の先頭バイトは充填文字であり、COBOL の編集項目には含まれない。
            // 項目の内容はオフセット 1 以降である。
            byte[] host = r.at(maskAddr[i] + 1, EditMask.editedFieldLength(masks.get(i)));
            byte[] mine = NumericEditor.edit(Decimal.parse(ec.value()), pictures.get(i), CP);
            assertEquals(pictures.get(i).size(), host.length,
                    "ED の結果からマスク先頭の充填文字を除いた長さは PICTURE のバイト長に等しい");
            assertEquals(hex(host), hex(mine), () -> String.format(
                    "%s%n  Hercules ED  : [%s]%n  NumericEditor: [%s]",
                    ec, CP.decode(host), CP.decode(mine)));
        }
    }

    @Test
    @DisplayName("ゼロ抑制 (Z) がホストの ED と一致する (FR-030)")
    void zeroSuppressionMatchesHost() throws Exception {
        assertMatchesHost("ed-z", List.of(
                new EditCase("ZZ,ZZ9.99", "1234.56"),
                new EditCase("ZZ,ZZ9.99", "0.05"),
                new EditCase("ZZ,ZZ9.99", "0"),
                new EditCase("ZZ,ZZ9.99", "99999.99"),
                new EditCase("ZZ9.99", "0.45"),
                new EditCase("ZZ9.99", "0"),
                new EditCase("ZZ9.99", "123.45"),
                new EditCase("ZZZ.99", "0.45"),
                new EditCase("ZZZ.99", "0")
        ));
    }

    @Test
    @DisplayName("小切手保護 (*) がホストの ED と一致する (FR-030)")
    void asteriskProtectionMatchesHost() throws Exception {
        assertMatchesHost("ed-star", List.of(
                new EditCase("**,**9.99", "12.34"),
                new EditCase("**,**9.99", "1234.56"),
                new EditCase("**,**9.99", "0"),
                new EditCase("**9.99", "0.45")
        ));
    }

    @Test
    @DisplayName("CR / DB が負数のときだけ現れることがホストの ED と一致する (FR-030)")
    void creditDebitMatchesHost() throws Exception {
        assertMatchesHost("ed-crdb", List.of(
                new EditCase("ZZ,ZZ9.99CR", "-1234.56"),
                new EditCase("ZZ,ZZ9.99CR", "1234.56"),
                new EditCase("ZZ9.99DB", "-12.34"),
                new EditCase("ZZ9.99DB", "12.34")
        ));
    }

    @Test
    @DisplayName("単純挿入がホストの ED と一致する (FR-030)")
    void simpleInsertionMatchesHost() throws Exception {
        assertMatchesHost("ed-insert", List.of(
                new EditCase("Z,ZZZ,ZZ9", "1234567"),
                new EditCase("Z,ZZZ,ZZ9", "12"),
                new EditCase("Z,ZZZ,ZZ9", "0")
        ));
    }
}
