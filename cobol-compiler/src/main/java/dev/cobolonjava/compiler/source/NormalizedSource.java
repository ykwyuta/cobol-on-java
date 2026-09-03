package dev.cobolonjava.compiler.source;

import java.util.ArrayList;
import java.util.List;

/**
 * プリプロセッサが出力する正規化済みソース (要件 FR-094)。
 *
 * <p>固定形式のカラムを取り除き、継続行を連結し、注釈行を落とした 1 本の文字列である。
 * <b>1 文字ごとに元のソース上の位置を保持する</b>ため、後段の構文解析器が報告する誤りを
 * 元のファイル・行・桁へ戻せる。
 *
 * <p>位置情報を「行単位」ではなく「文字単位」で持つのは、継続行と {@code COPY} 展開があるためである。
 * 正規化後の 1 行が元の複数行・複数ファイルにまたがるので、行単位では戻せない。
 */
public final class NormalizedSource {

    private final String text;
    private final Origin[] origins;

    private NormalizedSource(String text, Origin[] origins) {
        this.text = text;
        this.origins = origins;
    }

    public String text() {
        return text;
    }

    public int length() {
        return text.length();
    }

    /**
     * 指定位置の文字が元のソースのどこから来たかを返す。
     *
     * @throws IndexOutOfBoundsException 範囲外の位置を指定した場合
     */
    public Origin originOf(int offset) {
        if (offset < 0 || offset >= origins.length) {
            throw new IndexOutOfBoundsException(
                    "offset " + offset + " is outside the normalized source of length " + origins.length);
        }
        return origins[offset];
    }

    /** 組み立て用。 */
    public static final class Builder {

        private final StringBuilder text = new StringBuilder();
        private final List<Origin> origins = new ArrayList<>();

        /** 1 文字を、出自を伴って追加する。 */
        public Builder append(char c, Origin origin) {
            text.append(c);
            origins.add(origin);
            return this;
        }

        /** 文字列を、先頭の文字の桁から連続する出自を伴って追加する。 */
        public Builder append(String s, String fileName, int line, int startColumn) {
            for (int i = 0; i < s.length(); i++) {
                append(s.charAt(i), new Origin(fileName, line, startColumn + i));
            }
            return this;
        }

        /**
         * 区切りの空白を追加する。元のソース上の特定の文字に対応しないため、
         * 直前の文字と同じ出自を与える。空の場合は何もしない。
         */
        public Builder appendSeparator() {
            if (text.isEmpty()) {
                return this;
            }
            append(' ', origins.get(origins.size() - 1));
            return this;
        }

        public boolean isEmpty() {
            return text.isEmpty();
        }

        public NormalizedSource build() {
            return new NormalizedSource(text.toString(), origins.toArray(new Origin[0]));
        }
    }
}
