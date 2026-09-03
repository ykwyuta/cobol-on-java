package dev.cobolonjava.compiler.source;

import java.util.List;

/**
 * 置換の 1 組 (要件 FR-090, FR-091)。
 *
 * <p>{@code COPY ... REPLACING} と {@code REPLACE} はどちらも語の列を語の列で置き換える。
 * 適用の対象 (コピー句の中か、以降のソース全体か) が違うだけで、照合と差し替えの規則は同じである。
 *
 * @param from 置き換えられる語の列
 * @param to   差し込む語の列。空でもよい (指定した語を取り除くことになる)
 */
public record TextReplacement(List<TextWord> from, List<TextWord> to) {

    public TextReplacement {
        from = List.copyOf(from);
        to = List.copyOf(to);
    }
}
