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

            TextReplacement matched = null;
            for (TextReplacement replacement : active) {
                if (TextReplacements.matchesAt(words, i, replacement.from())) {
                    matched = replacement;
                    break;
                }
            }
            if (matched == null) {
                out.add(words.get(i));
                i++;
                continue;
            }
            List<TextWord> to = matched.to();
            for (int k = 0; k < to.size(); k++) {
                TextWord word = to.get(k);
                out.add(k == 0 ? word.withPrecededBySpace(words.get(i).precededBySpace()) : word);
            }
            i += matched.from().size();
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
            TextReplacements.Operand from = TextReplacements.readOperand(words, i, origin);
            if (words.get(i).kind() != TextWordKind.PSEUDO_DELIMITER) {
                // REPLACE の被演算子は擬似テキストでなければならない。
                // COPY ... REPLACING が 1 語の指定も許すのとは違う
                throw new SourceFormatException(origin,
                        "REPLACE operands must be pseudo-text enclosed in ==");
            }
            i = from.endIndex() + 1;
            if (i >= words.size() || !words.get(i).isWord("BY")) {
                throw new SourceFormatException(origin, "REPLACE requires BY after an operand");
            }
            i++;
            TextReplacements.Operand to = TextReplacements.readOperand(words, i, origin);
            i = to.endIndex() + 1;
            replacements.add(new TextReplacement(from.words(), to.words()));
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
