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
            } else if ((c == 'X' || c == 'x') && i + 1 < text.length()
                    && (text.charAt(i + 1) == '\'' || text.charAt(i + 1) == '"')) {
                // 16 進定数は 1 個の定数である。X を語として切ると REPLACING ==X== が掴んでしまう
                kind = TextWordKind.LITERAL;
                i = scanLiteral(text, i + 1, text.charAt(i + 1), source);
            } else if ((c == 'N' || c == 'n') && i + 1 < text.length()
                    && (text.charAt(i + 1) == '\'' || text.charAt(i + 1) == '"')) {
                // 国字定数 N'..' も 1 個の定数である
                kind = TextWordKind.LITERAL;
                i = scanLiteral(text, i + 1, text.charAt(i + 1), source);
            } else if ((c == 'N' || c == 'n') && i + 2 < text.length()
                    && (text.charAt(i + 1) == 'X' || text.charAt(i + 1) == 'x')
                    && (text.charAt(i + 2) == '\'' || text.charAt(i + 2) == '"')) {
                kind = TextWordKind.LITERAL;
                i = scanLiteral(text, i + 2, text.charAt(i + 2), source);
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
        if (insideNumber(text, i)) {
            return true;
        }
        if (c == ' ' || c == '\'' || c == '"' || SEPARATOR_CHARS.indexOf(c) >= 0) {
            return false;
        }
        // 擬似テキストの区切りは語の一部にしない
        return !(c == '=' && i + 1 < text.length() && text.charAt(i + 1) == '=');
    }

    /**
     * 数の途中の小数点 (と、小数点として使うコンマ) か。
     *
     * <p>規格は「終止符・コンマ・セミコロンは<b>すぐあとに空白が続くとき</b>区切りである」
     * と決めている。{@code +000004.99} の中の点は数の一部であり、区切りではない。
     * 切ってしまうと、残った {@code .99} が<b>次の文へ紛れ込む</b>
     * (SM202A の COPY REPLACING がそれで壊れていた)。
     *
     * <p>数字に挟まれているかどうかで見分ける。文の終止符が数字のすぐ後ろに来ることは
     * あるが、そのときは<b>後ろが数字ではない</b>ので区切りのままである。
     */
    private static boolean insideNumber(String text, int i) {
        char c = text.charAt(i);
        return (c == '.' || c == ',')
                && i > 0 && Character.isDigit(text.charAt(i - 1))
                && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1));
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
        throw new SourceFormatException(source.originOf(start),
                "a non-numeric literal is left unclosed");
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
