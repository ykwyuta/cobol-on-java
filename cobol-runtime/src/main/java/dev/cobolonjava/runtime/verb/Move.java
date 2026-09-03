package dev.cobolonjava.runtime.verb;

import dev.cobolonjava.runtime.codepage.CodePage;
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
}
