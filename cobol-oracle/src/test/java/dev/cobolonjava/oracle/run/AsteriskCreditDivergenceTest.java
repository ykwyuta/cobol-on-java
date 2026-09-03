package dev.cobolonjava.oracle.run;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>検証レベル V2</b>: {@code *} による小切手保護と {@code CR} / {@code DB} を
 * 組み合わせた場合の差異を記録する (provisional.md P-016)。
 *
 * <p>このテストは「一致する」ことではなく<b>一致しないこと、およびその理由</b>を表明する。
 * 合成ジェネレータからこの組み合わせを除いている根拠を、推測ではなく実測に基づかせるためである。
 *
 * <p>{@code ED} 命令は、値が正のときに末尾のメッセージ文字を<b>充填文字</b>で置き換える。
 * 充填文字が {@code *} である小切手保護では {@code "**"} になる。一方 COBOL の規則では、
 * 値が正またはゼロのとき {@code CR} / {@code DB} の 2 桁は<b>空白</b>になる。
 * したがって参照実装は、この組み合わせに対して平の {@code ED} 命令だけでは実現していない。
 */
@Tag("V2")
class AsteriskCreditDivergenceTest {

    private static final CodePage CP = CodePages.IBM_1047;

    private static HerculesRunner runner;

    @BeforeAll
    static void setUp() {
        runner = OracleSupport.requireHercules();
    }

    @Test
    @DisplayName("ED は正数のとき CR を充填文字で置き換える。COBOL の規則は空白であり食い違う")
    void edUsesFillCharacterWhereCobolRequiresSpaces() throws Exception {
        Picture picture = PictureParser.parse("**9.99CR");
        Decimal positive = Decimal.parse("12.34");

        byte[] source = PackedDecimal.encode(positive, picture.digits(), picture.scale(), true);
        int nibbles = source.length * 2 - 1;
        byte[] mask = EditMask.forPicture(picture, CP, nibbles);

        HerculesCase c = new HerculesCase("star-cr");
        int maskAddr = c.data(mask);
        int sourceAddr = c.data(source);
        c.emit(Insn.ed(maskAddr, mask.length, sourceAddr));
        c.dump("edited", maskAddr, mask.length);

        HerculesResult r = runner.run(c);
        assertTrue(r.completed());

        int offset = EditMask.editedFieldOffset(picture, nibbles);
        String host = CP.decode(r.at(maskAddr + offset, picture.size()));
        String mine = CP.decode(NumericEditor.edit(positive, picture, CP));

        assertEquals("*12.34**", host,
                "ED は CR の 2 桁を充填文字 (ここでは *) で埋める");
        assertEquals("*12.34  ", mine,
                "COBOL の規則では正数のとき CR の 2 桁は空白になる");
    }

    @Test
    @DisplayName("負数では両者が一致する。差が出るのは正数のときだけである")
    void negativeValuesAgree() throws Exception {
        Picture picture = PictureParser.parse("**9.99CR");
        Decimal negative = Decimal.parse("-12.34");

        byte[] source = PackedDecimal.encode(negative, picture.digits(), picture.scale(), true);
        int nibbles = source.length * 2 - 1;
        byte[] mask = EditMask.forPicture(picture, CP, nibbles);

        HerculesCase c = new HerculesCase("star-cr-negative");
        int maskAddr = c.data(mask);
        int sourceAddr = c.data(source);
        c.emit(Insn.ed(maskAddr, mask.length, sourceAddr));
        c.dump("edited", maskAddr, mask.length);

        HerculesResult r = runner.run(c);
        assertTrue(r.completed());

        int offset = EditMask.editedFieldOffset(picture, nibbles);
        String host = CP.decode(r.at(maskAddr + offset, picture.size()));
        String mine = CP.decode(NumericEditor.edit(negative, picture, CP));
        assertEquals(host, mine, "負数では CR が表示され、充填文字の違いが現れない");
        assertEquals("*12.34CR", host);
    }
}
