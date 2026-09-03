package dev.cobolonjava.runtime.picture;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.picture.Picture.Cell;
import dev.cobolonjava.runtime.picture.Picture.Kind;

/**
 * 英数字編集 (要件 FR-030, FR-060)。
 *
 * <p>送出データを {@code A} / {@code X} の文字位置へ左から順に詰め、
 * 挿入文字 ({@code B} は空白、{@code 0} はゼロ、{@code /} は斜線) をその位置へ置く。
 *
 * <p>送出データが足りない場合、残りの文字位置は空白で埋まる。多い場合は切り捨てる。
 * これは英数字転記の規則と同じである。
 *
 * <p>数値編集とは異なり、単一の機械語命令には対応しない。参照実装は {@code MVC} と
 * {@code MVI} の組み合わせで実現していると考えられるため、この処理は検証レベル V1 に留まる
 * (設計文書 20 の「V2 にできない領域について」を参照)。
 */
public final class AlphanumericEditor {

    private AlphanumericEditor() {
    }

    /**
     * 送出データを英数字編集項目のバイト列へ変換する。
     *
     * @throws IllegalArgumentException PICTURE が英数字編集項目でない場合
     */
    public static byte[] edit(byte[] source, Picture picture, CodePage codePage) {
        if (picture.category() != Picture.Category.ALPHANUMERIC_EDITED) {
            throw new IllegalArgumentException("not an alphanumeric-edited picture: " + picture);
        }
        byte[] out = new byte[picture.size()];
        int outIndex = 0;
        int sourceIndex = 0;

        for (Cell c : picture.cells()) {
            switch (c.kind()) {
                case ALPHA, ALNUM -> {
                    // 送出データが尽きたら空白で埋める
                    out[outIndex++] = sourceIndex < source.length
                            ? source[sourceIndex++] : codePage.space();
                }
                case INSERT -> out[outIndex++] = codePage.ch(c.literal());
                default -> throw new IllegalStateException(
                        "unexpected cell kind in alphanumeric editing: " + c.kind());
            }
        }
        return out;
    }
}
