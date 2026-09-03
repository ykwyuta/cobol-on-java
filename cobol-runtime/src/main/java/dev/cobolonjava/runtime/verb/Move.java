package dev.cobolonjava.runtime.verb;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.CobolRounding;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.picture.NumericEditor;
import dev.cobolonjava.runtime.picture.Picture;
import dev.cobolonjava.runtime.storage.DataView;
import java.util.Arrays;

/**
 * {@code MOVE} 文の意味論のうち、英数字項目に関する部分 (要件 FR-060)。
 *
 * <p>集団項目への転記もここに含まれる。集団項目は「配下の基本項目が占めるバイト範囲そのもの」であり、
 * 転記は英数字項目として無変換にバイト単位で作用する (要件 FR-020)。
 */
public final class Move {

    private Move() {
    }

    /**
     * 英数字転記。既定では左詰めで、余った右側は空白で埋め、あふれた右側は切り捨てる。
     *
     * @param justifiedRight {@code JUSTIFIED RIGHT} 指定。右詰めになり、あふれるのは左側になる
     */
    public static byte[] alphanumeric(byte[] source, int targetLength,
                                      boolean justifiedRight, CodePage codePage) {
        byte[] out = new byte[targetLength];
        Arrays.fill(out, codePage.space());
        if (justifiedRight) {
            int n = Math.min(source.length, targetLength);
            System.arraycopy(source, source.length - n, out, targetLength - n, n);
        } else {
            int n = Math.min(source.length, targetLength);
            System.arraycopy(source, 0, out, 0, n);
        }
        return out;
    }

    /**
     * 数値転記。小数点で位置を合わせ、収まらない上位桁は切り捨て、足りない桁は 0 で埋める。
     *
     * <p>{@code MOVE} には {@code ROUNDED} がないため、小数部は常に切り捨てられる。
     * 上位桁のあふれも黙って切り捨てられる。これは算術文で {@code ON SIZE ERROR} を
     * 指定しない場合と同じ挙動である。
     */
    public static void numeric(Decimal source, NumericItem target, DataView view) {
        target.store(view, source.rescale(target.picture().scale(), CobolRounding.TRUNCATION));
    }

    /**
     * 数字編集項目への転記。編集結果のバイト列を書き込む。
     *
     * @throws IllegalArgumentException 受取項目が数字編集項目でない場合
     */
    public static void toNumericEdited(Decimal source, Picture target, DataView view,
                                       CodePage codePage) {
        byte[] edited = NumericEditor.edit(source, target, codePage);
        if (edited.length != view.length()) {
            throw new IllegalArgumentException(
                    "view length " + view.length() + " does not match the edited picture size "
                            + edited.length);
        }
        view.setBytes(edited);
    }
}
