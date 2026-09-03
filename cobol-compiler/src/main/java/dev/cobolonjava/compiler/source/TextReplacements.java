package dev.cobolonjava.compiler.source;

import java.util.ArrayList;
import java.util.List;

/**
 * 語の列に対する置換の照合と差し替え (要件 FR-090, FR-091)。
 *
 * <p>{@code COPY ... REPLACING} と {@code REPLACE} で共有する。適用の対象は違うが、
 * 照合と差し替えの規則は同じである。
 */
public final class TextReplacements {

    private TextReplacements() {
    }

    /**
     * 置換を適用する。各位置で置換の指定を<b>書かれた順に試し、最初に一致したものを使う</b>。
     *
     * <p>順序が結果を変える。{@code ==A B== BY ==X==} と {@code ==A== BY ==Y==} を
     * 書く順を入れ替えると、{@code A B} が {@code X} になるか {@code Y B} になるかが変わる。
     */
    public static List<TextWord> apply(List<TextWord> body, List<TextReplacement> replacements) {
        if (replacements.isEmpty()) {
            return body;
        }
        List<TextWord> out = new ArrayList<>();
        int i = 0;
        while (i < body.size()) {
            TextReplacement matched = null;
            for (TextReplacement replacement : replacements) {
                if (matchesAt(body, i, replacement.from())) {
                    matched = replacement;
                    break;
                }
            }
            if (matched == null) {
                out.add(body.get(i));
                i++;
                continue;
            }
            List<TextWord> to = matched.to();
            for (int k = 0; k < to.size(); k++) {
                TextWord word = to.get(k);
                // 差し込む列の先頭は、置き換えられた語の空白の扱いを引き継ぐ
                out.add(k == 0 ? word.withPrecededBySpace(body.get(i).precededBySpace()) : word);
            }
            i += matched.from().size();
        }
        return out;
    }

    /** 指定位置から語の列が一致するかどうか。 */
    public static boolean matchesAt(List<TextWord> body, int at, List<TextWord> pattern) {
        if (pattern.isEmpty() || at + pattern.size() > body.size()) {
            return false;
        }
        for (int k = 0; k < pattern.size(); k++) {
            if (!body.get(at + k).matches(pattern.get(k))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 置換の被演算子を読む。擬似テキスト {@code ==...==} は語の列、それ以外は 1 語である。
     *
     * @param start 被演算子の開始位置
     * @return 読み取った語の列と、被演算子の最後の語の位置
     */
    public static Operand readOperand(List<TextWord> words, int start, Origin origin) {
        if (start >= words.size()) {
            throw new SourceFormatException(origin + ": a replacement operand is missing");
        }
        if (words.get(start).kind() != TextWordKind.PSEUDO_DELIMITER) {
            return new Operand(List.of(words.get(start)), start);
        }
        List<TextWord> collected = new ArrayList<>();
        int i = start + 1;
        while (i < words.size() && words.get(i).kind() != TextWordKind.PSEUDO_DELIMITER) {
            collected.add(words.get(i));
            i++;
        }
        if (i >= words.size()) {
            throw new SourceFormatException(origin + ": pseudo-text is not terminated by ==");
        }
        return new Operand(collected, i);
    }

    /**
     * 読み取った被演算子。
     *
     * @param words    語の列
     * @param endIndex 被演算子の最後の語の位置
     */
    public record Operand(List<TextWord> words, int endIndex) {
    }
}
