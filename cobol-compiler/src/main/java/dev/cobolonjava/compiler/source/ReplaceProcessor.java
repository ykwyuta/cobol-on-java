package dev.cobolonjava.compiler.source;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code REPLACE} 文の処理 (要件 FR-091)。
 *
 * <pre>
 * REPLACE ==擬似テキスト== BY ==擬似テキスト== [...] .
 * REPLACE OFF.
 * </pre>
 *
 * <h2>COPY との違い</h2>
 * <p>{@code COPY ... REPLACING} が<b>そのコピー句の中だけ</b>に効くのに対し、
 * {@code REPLACE} は<b>その文より後ろのソース全体</b>に効き、次の {@code REPLACE} または
 * {@code REPLACE OFF} まで有効であり続ける。したがって処理は状態を持つ走査になる。
 *
 * <h2>COPY のあとに適用する</h2>
 * <p>COBOL は {@code COPY} をすべて処理してから {@code REPLACE} を適用すると定めている。
 * つまり <b>{@code REPLACE} はコピー句から展開された語にも効く</b>。
 * 順序を逆にすると、コピー句の中身が置換の対象から漏れる。
 *
 * <h2>置換した結果は再走査しない</h2>
 * <p>差し込んだ語は、そのまま出力へ送る。再び置換の対象にはしない。
 * 再走査すると、{@code ==A== BY ==A B==} のような指定で終わらなくなる。
 */
public final class ReplaceProcessor {

    private ReplaceProcessor() {
    }

    /** 正規化済みソースに {@code REPLACE} を適用する。 */
    public static NormalizedSource apply(NormalizedSource source) {
        return PreprocessorLexer.emit(apply(PreprocessorLexer.lex(source)));
    }

    /** 語の列に {@code REPLACE} を適用する。 */
    public static List<TextWord> apply(List<TextWord> words) {
        List<TextWord> out = new ArrayList<>();
        List<TextReplacement> active = List.of();
        int i = 0;

        while (i < words.size()) {
            if (words.get(i).isWord("REPLACE")) {
                ReplaceStatement statement = parse(words, i);
                active = statement.replacements();
                // REPLACE 文そのものは出力に残さない
                i = statement.endIndex() + 1;
                continue;
            }
            if (active.isEmpty()) {
                out.add(words.get(i));
                i++;
                continue;
            }

            // 読み飛ばした区切りのぶん、擬似テキストより長く消費することがある
            i += TextReplacements.replaceAt(words, i, active, out);
        }
        return out;
    }

    private static ReplaceStatement parse(List<TextWord> words, int start) {
        Origin origin = words.get(start).origin();
        int i = start + 1;
        if (i >= words.size()) {
            throw new SourceFormatException(origin, "REPLACE requires operands or OFF");
        }

        if (words.get(i).isWord("OFF")) {
            i++;
            requirePeriod(words, i, origin);
            return new ReplaceStatement(List.of(), i);
        }

        List<TextReplacement> replacements = new ArrayList<>();
        while (i < words.size() && !words.get(i).isSeparator('.')) {
            TextReplacements.Pair pair =
                    TextReplacements.readPair(words, i, origin, "REPLACE", true);
            replacements.add(pair.replacement());
            i = pair.endIndex() + 1;
        }

        if (replacements.isEmpty()) {
            throw new SourceFormatException(origin, "REPLACE requires at least one operand pair");
        }
        requirePeriod(words, i, origin);
        return new ReplaceStatement(replacements, i);
    }

    private static void requirePeriod(List<TextWord> words, int i, Origin origin) {
        if (i >= words.size() || !words.get(i).isSeparator('.')) {
            throw new SourceFormatException(origin, "REPLACE must be terminated by a period");
        }
    }

    private record ReplaceStatement(List<TextReplacement> replacements, int endIndex) {
    }
}
