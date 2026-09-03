package dev.cobolonjava.oracle.machine;

import static dev.cobolonjava.oracle.OracleSupport.hex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.picture.Picture;
import dev.cobolonjava.runtime.picture.PictureParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 編集マスク生成の検証。
 *
 * <p>マスクそのものの正しさは、生成したマスクで {@code ED} を実行した結果が
 * {@code NumericEditor} と一致することによって V2 で裏付けられている
 * ({@code NumericEditingOracleTest})。ここでは Hercules がない環境でも
 * 生成結果が固定されるよう、バイト列を直接表明する。
 */
@Tag("V1")
class EditMaskTest {

    private static String mask(String picture) {
        Picture p = PictureParser.parse(picture);
        return hex(EditMask.forPicture(p, CodePages.IBM_1047));
    }

    @Test
    @DisplayName("有意性開始子は、常に表示する桁のひとつ手前の抑制位置に置かれる")
    void significanceStarterPlacement() {
        // ZZ9.99 -> 充填(40) Z(20) Z(21) 9(20) .(4B) 9(20) 9(20)
        // 3 番目の位置ではなく 2 番目の Z が有意性開始子になる。
        // ここを取り違えると値 0.45 が "  .45" となり "0.45" にならない。
        assertEquals("402021204B2020", mask("ZZ9.99"));
        assertEquals("4020206B2021204B2020", mask("ZZ,ZZ9.99"));
        assertEquals("402020214B2020", mask("ZZZ.99"));
    }

    @Test
    @DisplayName("小切手保護では充填文字がアスタリスクになる")
    void asteriskFill() {
        assertEquals("5C2021204B2020", mask("**9.99"));
    }

    @Test
    @DisplayName("CR / DB はメッセージ文字 2 個としてマスクに入る")
    void creditDebit() {
        assertEquals("402021204B2020C3D9", mask("ZZ9.99CR"));
        assertEquals("402021204B2020C4C2", mask("ZZ9.99DB"));
    }

    @Test
    @DisplayName("マスクの長さは編集項目より 1 バイト長い。先頭が充填文字であるため")
    void lengths() {
        Picture p = PictureParser.parse("ZZ,ZZ9.99");
        byte[] m = EditMask.forPicture(p, CodePages.IBM_1047);
        assertEquals(p.size(), EditMask.editedFieldLength(m));
        assertEquals(p.digits(), EditMask.digitPositions(m));
    }

    @Test
    @DisplayName("平の ED では表現できない PICTURE は明示的に拒否する")
    void unsupportedPictures() {
        // 浮動挿入
        assertThrows(UnsupportedOperationException.class, () -> mask("$$$,$$9.99"));
        // 固定符号
        assertThrows(UnsupportedOperationException.class, () -> mask("+9(4)"));
        // 常に表示する桁がない (全桁抑制)。COBOL 固有の「値がゼロなら項目全体を抑制」の
        // 規則を持ち、参照実装も ED 単独では実現していないと考えられる
        assertThrows(UnsupportedOperationException.class, () -> mask("ZZZZ"));
        // 先頭の桁位置が抑制対象でない。参照実装は UNPK と挿入で処理していると考えられる
        assertThrows(UnsupportedOperationException.class, () -> mask("9(3).99"));
    }
}
