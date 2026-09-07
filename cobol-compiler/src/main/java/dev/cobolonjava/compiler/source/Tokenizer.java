package dev.cobolonjava.compiler.source;

import java.util.ArrayList;
import java.util.List;

/**
 * 正規化済みソースを構文解析器へ渡すトークン列へ分ける (方針 ARC-8、未決事項 Q-15 の決定)。
 *
 * <p>プリプロセッサの最後の仕事である。ここで<b>「島」を切り出し</b>、文脈依存性を
 * 構文解析器から締め出す。
 *
 * <h2>区切りピリオドは「空白が続くピリオド」である</h2>
 * <p>COBOL のピリオドは、<b>直後に空白が来るときだけ</b>区切り文字になる。
 * そうでなければ数字定数の小数点である。この規則ひとつで {@code 1.5} と
 * {@code MOVE A TO B.} が分かれる。読点と semicolon も同じ規則に従う。
 * 括弧はつねに区切り文字である。
 *
 * <h2>PICTURE 句の文字列は丸ごと切り出す</h2>
 * <p>{@code PIC 99.99.} の 2 つのピリオドは、字面では区別できない。
 * {@code PICTURE} / {@code PIC} を見たら、続く 1 個の文字列を
 * <b>空白または区切り文字が来るまで</b>読み、{@link SourceTokenKind#PICTURE_STRING} とする。
 * 上の規則により、末尾の {@code .} は区切りピリオドになり文字列には入らない。
 * これは参照実装の規定 (PICTURE 文字列は分離符の空白・読点・semicolon・ピリオドで終わる) と
 * 一致する。したがって<b>小数点で終わる PICTURE は書けない</b>。
 *
 * <h2>EXEC ブロックは中身を見ない</h2>
 * <p>{@code EXEC SQL ... END-EXEC} の中身は COBOL ではない。丸ごと 1 個の
 * {@link SourceTokenKind#EXEC_BLOCK} とし、専用のトランスレータへ渡す (要件 FR-150, FR-160)。
 * 終端は<b>語として現れる {@code END-EXEC}</b> であり、文字定数の中に現れたものは数えない。
 * これで {@code EXEC SQL ... WHERE C = 'END-EXEC' ... END-EXEC} が正しく閉じる。
 */
public final class Tokenizer {

    /**
     * それ自体が 1 個のトークンになり、つねに区切り文字である文字。
     * コロンは部分参照 {@code WS-A(3:2)} を書くためのものである。
     */
    private static final String ALWAYS_SEPARATOR = "():";

    /** 直後に空白が続くときだけ区切り文字になる文字。 */
    private static final String CONDITIONAL_SEPARATOR = ".,;";

    private static final String EXEC = "EXEC";
    private static final String END_EXEC = "END-EXEC";

    private final NormalizedSource source;
    private final String text;
    private final List<SourceToken> tokens = new ArrayList<>();
    private int i;

    private Tokenizer(NormalizedSource source) {
        this.source = source;
        this.text = source.text();
    }

    /** 正規化済みソースをトークン列へ分ける。 */
    public static List<SourceToken> tokenize(NormalizedSource source) {
        Tokenizer tokenizer = new Tokenizer(source);
        tokenizer.run();
        return tokenizer.tokens;
    }

    private void run() {
        while (true) {
            skipSpaces();
            if (i >= text.length()) {
                return;
            }
            char c = text.charAt(i);

            if (c == '\'' || c == '"') {
                emit(SourceTokenKind.LITERAL, i, scanLiteral(i));
                continue;
            }
            if (ALWAYS_SEPARATOR.indexOf(c) >= 0 || isSeparatorPunctuation(i)) {
                emit(SourceTokenKind.SEPARATOR, i, i + 1);
                continue;
            }

            int start = i;
            int end = wordEnd(start);
            if (equalsIgnoreCase(start, end, EXEC)) {
                emit(SourceTokenKind.EXEC_BLOCK, start, execBlockEnd(start));
                continue;
            }
            emit(SourceTokenKind.WORD, start, end);
            if (equalsIgnoreCase(start, end, "PIC") || equalsIgnoreCase(start, end, "PICTURE")) {
                scanPictureClause();
            }
        }
    }

    /**
     * {@code PICTURE} 句の残り、すなわち省略可能な {@code IS} と文字列を読む。
     * 呼び出し時点で {@code PICTURE} / {@code PIC} は出力済みである。
     */
    private void scanPictureClause() {
        Origin clause = tokens.get(tokens.size() - 1).origin();
        skipSpaces();
        if (i < text.length() && !isSeparatorPunctuation(i)
                && ALWAYS_SEPARATOR.indexOf(text.charAt(i)) < 0) {
            int end = wordEnd(i);
            if (equalsIgnoreCase(i, end, "IS")) {
                emit(SourceTokenKind.WORD, i, end);
                skipSpaces();
            }
        }
        int start = i;
        int end = pictureEnd(start);
        if (end == start) {
            throw new SourceFormatException(clause, "PICTURE requires a character-string");
        }
        emit(SourceTokenKind.PICTURE_STRING, start, end);
    }

    /** PICTURE 文字列の終わり。空白か区切り文字で終わる。括弧は文字列の一部である。 */
    private int pictureEnd(int start) {
        int j = start;
        while (j < text.length() && text.charAt(j) != ' ' && !isSeparatorPunctuation(j)) {
            j++;
        }
        return j;
    }

    /** {@code EXEC} ブロックの終わり (終端の {@code END-EXEC} を含む)。 */
    private int execBlockEnd(int start) {
        int j = start + EXEC.length();
        while (j < text.length()) {
            char c = text.charAt(j);
            if (c == '\'' || c == '"') {
                // 文字定数の中の END-EXEC は終端ではない
                j = scanLiteral(j);
                continue;
            }
            if (c == ' ' && isEndExecAt(j + 1)) {
                return j + 1 + END_EXEC.length();
            }
            j++;
        }
        throw new SourceFormatException(source.originOf(start),
                "EXEC block is not terminated by END-EXEC");
    }

    /** 位置 {@code j} に語としての {@code END-EXEC} があるか。 */
    private boolean isEndExecAt(int j) {
        if (!equalsIgnoreCase(j, Math.min(j + END_EXEC.length(), text.length()), END_EXEC)) {
            return false;
        }
        int after = j + END_EXEC.length();
        if (after >= text.length()) {
            return true;
        }
        char c = text.charAt(after);
        return c == ' ' || ALWAYS_SEPARATOR.indexOf(c) >= 0 || CONDITIONAL_SEPARATOR.indexOf(c) >= 0;
    }

    /** 語の終わり。空白・引用符・括弧・区切りの句読点で終わる。 */
    private int wordEnd(int start) {
        int j = start;
        while (j < text.length()) {
            char c = text.charAt(j);
            if (c == ' ' || c == '\'' || c == '"' || ALWAYS_SEPARATOR.indexOf(c) >= 0) {
                break;
            }
            if (isSeparatorPunctuation(j)) {
                break;
            }
            j++;
        }
        return j;
    }

    /** 位置 {@code j} の文字が区切りの句読点か。直後に空白が来るときだけそうなる。 */
    private boolean isSeparatorPunctuation(int j) {
        if (CONDITIONAL_SEPARATOR.indexOf(text.charAt(j)) < 0) {
            return false;
        }
        return j + 1 >= text.length() || text.charAt(j + 1) == ' ';
    }

    private int scanLiteral(int start) {
        char quote = text.charAt(start);
        int j = start + 1;
        while (j < text.length()) {
            if (text.charAt(j) == quote) {
                if (j + 1 < text.length() && text.charAt(j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        throw new SourceFormatException(source.originOf(start),
                "a non-numeric literal is left unclosed");
    }

    private boolean equalsIgnoreCase(int start, int end, String word) {
        return end - start == word.length() && text.regionMatches(true, start, word, 0, word.length());
    }

    private void skipSpaces() {
        while (i < text.length() && text.charAt(i) == ' ') {
            i++;
        }
    }

    private void emit(SourceTokenKind kind, int start, int end) {
        Origin[] origins = new Origin[end - start];
        for (int k = 0; k < origins.length; k++) {
            origins[k] = source.originOf(start + k);
        }
        tokens.add(new SourceToken(kind, text.substring(start, end), origins));
        i = end;
    }
}
