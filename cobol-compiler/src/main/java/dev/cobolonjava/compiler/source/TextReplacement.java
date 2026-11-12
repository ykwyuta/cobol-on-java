package dev.cobolonjava.compiler.source;

import java.util.List;

/**
 * {@code COPY ... REPLACING} と {@code REPLACE} に共通する置換の 1 組。
 *
 * @param from 置換対象の語の列
 * @param to   置換後の語の列
 * @param part 語の全体を置き換えるのか、語の頭・尻だけを置き換えるのか
 */
public record TextReplacement(List<TextWord> from, List<TextWord> to, Part part) {

    /**
     * 置き換える範囲。
     *
     * <p>{@code LEADING} / {@code TRAILING} は<b>語の一部</b>を置き換える (2002 規格、
     * Enterprise COBOL の部分語置換)。{@code REPLACE LEADING ==OLD-== BY ==NEW-==} は
     * {@code OLD-TOTAL} を {@code NEW-TOTAL} にする。
     */
    public enum Part {
        WHOLE, LEADING, TRAILING
    }

    public TextReplacement {
        from = List.copyOf(from);
        to = List.copyOf(to);
    }

    public TextReplacement(List<TextWord> from, List<TextWord> to) {
        this(from, to, Part.WHOLE);
    }
}
