package dev.cobolonjava.compiler.source;

import java.util.ArrayList;
import java.util.List;

/**
 * 正規化済みソースを、プリプロセッサが扱う字句の列へ分ける (要件 FR-090, FR-091)。
 *
 * <p>ここでの分割は構文解析のためのものではなく、{@code COPY ... REPLACING} と
 * {@code REPLACE} の語単位の照合を成り立たせるためのものである。したがって
 * COBOL の予約語も利用者定義語も同じ {@link TextWordKind#WORD} として扱う。
 */
public final class PreprocessorLexer {

    /** それ自体が 1 個の字句になる区切り文字。 */
    private static final String SEPARATOR_CHARS = ".,;()";

    private PreprocessorLexer() {
    }

    /** 正規化済みソースを字句の列へ分ける。 */
    public static List<TextWord> lex(NormalizedSource source) {
        List<TextWord> words = new ArrayList<>();
        String text = source.text();
        int i = 0;
        boolean sawSpace = false;

        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ' ') {
                sawSpace = true;
                i++;
                continue;
            }
            int start = i;
            TextWordKind kind;

            if (c == '=' && i + 1 < text.length() && text.charAt(i + 1) == '=') {
                kind = TextWordKind.PSEUDO_DELIMITER;
                i += 2;
            } else if (c == '\'' || c == '"') {
                kind = TextWordKind.LITERAL;
                i = scanLiteral(text, i, c, source);
            } else if (SEPARATOR_CHARS.indexOf(c) >= 0) {
                kind = TextWordKind.SEPARATOR;
                i++;
            } else {
                kind = TextWordKind.WORD;
                while (i < text.length() && isWordCharacter(text, i)) {
                    i++;
                }
            }

            words.add(build(source, text, start, i, kind, sawSpace));
            sawSpace = false;
        }
        return words;
    }

    /** 字句の列を 1 本の正規化済みソースへ戻す。 */
    public static NormalizedSource emit(List<TextWord> words) {
        NormalizedSource.Builder out = new NormalizedSource.Builder();
        for (TextWord word : words) {
            if (word.precededBySpace() && !out.isEmpty()) {
                out.append(' ', word.origin());
            }
            for (int i = 0; i < word.text().length(); i++) {
                out.append(word.text().charAt(i), word.originAt(i));
            }
        }
        return out.build();
    }

    private static boolean isWordCharacter(String text, int i) {
        char c = text.charAt(i);
        if (c == ' ' || c == '\'' || c == '"' || SEPARATOR_CHARS.indexOf(c) >= 0) {
            return false;
        }
        // 擬似テキストの区切りは語の一部にしない
        return !(c == '=' && i + 1 < text.length() && text.charAt(i + 1) == '=');
    }

    private static int scanLiteral(String text, int start, char quote, NormalizedSource source) {
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i) == quote) {
                if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new SourceFormatException(
                source.originOf(start) + ": a non-numeric literal is left unclosed");
    }

    private static TextWord build(NormalizedSource source, String text, int start, int end,
                                  TextWordKind kind, boolean precededBySpace) {
        Origin[] origins = new Origin[end - start];
        for (int i = 0; i < origins.length; i++) {
            origins[i] = source.originOf(start + i);
        }
        return new TextWord(text.substring(start, end), origins, kind, precededBySpace);
    }
}
