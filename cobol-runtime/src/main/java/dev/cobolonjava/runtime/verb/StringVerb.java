package dev.cobolonjava.runtime.verb;

import java.util.List;

/**
 * {@code STRING} 文の意味論 (要件 FR-065)。
 *
 * <p>複数の送出項目を、区切り文字までの部分だけ取り出して受取項目へ連結する。
 *
 * <p><b>重要</b>: {@code STRING} は受取項目を空白で埋めない。ポインタが指す位置から
 * 転記した範囲だけが書き換わり、<b>それ以外の位置は元の内容が残る</b>。
 * 前回の内容が残ることを見落とすと、短い文字列を書き込んだときに前の値の尾が見えてしまう。
 */
public final class StringVerb {

    private StringVerb() {
    }

    /**
     * 送出項目 1 個の指定。
     *
     * @param value     送出する値
     * @param delimiter 区切り文字。{@code null} は {@code DELIMITED BY SIZE} を表し、値全体を送る
     */
    public record Source(byte[] value, byte[] delimiter) {

        public static Source bySize(byte[] value) {
            return new Source(value, null);
        }

        public static Source delimitedBy(byte[] value, byte[] delimiter) {
            return new Source(value, delimiter);
        }

        /** 実際に送出される部分の長さ。 */
        int transferLength() {
            if (delimiter == null || delimiter.length == 0) {
                return value.length;
            }
            int at = Region.indexOf(value, delimiter, 0);
            return at < 0 ? value.length : at;
        }
    }

    /**
     * 実行結果。
     *
     * @param target   転記後の受取項目
     * @param pointer  次に書き込む位置 (1 起点)。{@code WITH POINTER} が受け取る値
     * @param overflow オーバーフロー条件が立ったかどうか
     */
    public record Result(byte[] target, int pointer, boolean overflow) {
    }

    /**
     * {@code STRING} を実行する。
     *
     * @param target        受取項目の現在の内容。この配列は変更されない
     * @param startPointer  書き込みを始める位置 (1 起点)
     * @param sources       送出項目の並び
     */
    public static Result string(byte[] target, int startPointer, List<Source> sources) {
        byte[] out = target.clone();
        // ポインタが受取項目の範囲外なら、何も転記せずにオーバーフローとなる
        if (startPointer < 1 || startPointer > target.length) {
            return new Result(out, startPointer, true);
        }

        int position = startPointer - 1;
        for (Source source : sources) {
            int length = source.transferLength();
            for (int i = 0; i < length; i++) {
                if (position >= out.length) {
                    return new Result(out, position + 1, true);
                }
                out[position++] = source.value()[i];
            }
        }
        return new Result(out, position + 1, false);
    }
}
