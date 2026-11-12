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
            i += replaceAt(body, i, replacements, out);
        }
        return out;
    }

    /**
     * {@code at} の位置で最初に当たる置換を 1 つ適用し、結果を {@code out} へ足す。
     * 当たらなければその語をそのまま足す。
     *
     * @return 消費した語の数
     */
    static int replaceAt(List<TextWord> body, int at, List<TextReplacement> replacements,
                         List<TextWord> out) {
        TextWord here = body.get(at);
        for (TextReplacement replacement : replacements) {
            if (replacement.part() != TextReplacement.Part.WHOLE) {
                TextWord partial = partially(here, replacement);
                if (partial != null) {
                    out.add(partial);
                    return 1;
                }
                continue;
            }
            int length = matchLength(body, at, replacement.from());
            if (length < 0) {
                continue;
            }
            List<TextWord> to = replacement.to();
            for (int k = 0; k < to.size(); k++) {
                TextWord word = to.get(k);
                // 差し込む列の先頭は、置き換えられた語の空白の扱いを引き継ぐ
                out.add(k == 0 ? word.withPrecededBySpace(here.precededBySpace()) : word);
            }
            // 読み飛ばした区切りのぶん、擬似テキストより長く消費することがある
            return length;
        }
        out.add(here);
        return 1;
    }

    /**
     * 語の頭・尻を置き換える。当たらなければ {@code null}。
     *
     * <p>語の全体が当たるときも置き換える。置換後が空で語がまるごと消えるときだけは
     * 置き換えない——語が無くなると、置換ではなく削除になってしまう。
     */
    private static TextWord partially(TextWord word, TextReplacement replacement) {
        if (word.kind() != TextWordKind.WORD) {
            return null;
        }
        String from = replacement.from().get(0).text();
        String to = replacement.to().isEmpty() ? "" : replacement.to().get(0).text();
        String text = word.text();
        boolean leading = replacement.part() == TextReplacement.Part.LEADING;
        if (text.length() < from.length() || (to.isEmpty() && text.length() == from.length())
                || !text.regionMatches(true, leading ? 0 : text.length() - from.length(),
                        from, 0, from.length())) {
            return null;
        }
        String replaced = leading ? to + text.substring(from.length())
                : text.substring(0, text.length() - from.length()) + to;
        Origin[] origins = new Origin[replaced.length()];
        java.util.Arrays.fill(origins, word.origin());
        return new TextWord(replaced, origins, TextWordKind.WORD, word.precededBySpace());
    }

    /**
     * 置換の 1 組を読む。{@code [LEADING | TRAILING] 被演算子 BY 被演算子} である。
     *
     * @param statement 診断に書く文の名前 ({@code REPLACE} か {@code REPLACING})
     * @param pseudoOnly 被演算子は擬似テキストに限るか ({@code REPLACE} はそうである。
     *                   {@code COPY ... REPLACING} が 1 語の指定も許すのとは違う)
     */
    static Pair readPair(List<TextWord> words, int start, Origin origin, String statement,
                         boolean pseudoOnly) {
        int i = start;
        TextReplacement.Part part = TextReplacement.Part.WHOLE;
        if (words.get(i).isWord("LEADING") || words.get(i).isWord("TRAILING")) {
            part = words.get(i).isWord("LEADING")
                    ? TextReplacement.Part.LEADING : TextReplacement.Part.TRAILING;
            i++;
        }
        boolean partial = part != TextReplacement.Part.WHOLE;
        Operand from = operand(words, i, origin, statement, pseudoOnly || partial);
        if (from.words().isEmpty()) {
            // 空の擬似テキストは、どの語にも当たらない。書き間違いである
            throw new SourceFormatException(origin,
                    statement + ": pseudo-text to be replaced must not be empty");
        }
        i = from.endIndex() + 1;
        if (i >= words.size() || !words.get(i).isWord("BY")) {
            throw new SourceFormatException(origin, statement + " requires BY after an operand");
        }
        i++;
        Operand to = operand(words, i, origin, statement, pseudoOnly || partial);
        if (partial && (from.words().size() != 1 || to.words().size() > 1
                || from.words().get(0).kind() != TextWordKind.WORD
                || (!to.words().isEmpty() && to.words().get(0).kind() != TextWordKind.WORD))) {
            throw new SourceFormatException(origin, statement
                    + " LEADING / TRAILING requires a single partial word on each side");
        }
        return new Pair(new TextReplacement(from.words(), to.words(), part), to.endIndex());
    }

    private static Operand operand(List<TextWord> words, int at, Origin origin,
                                   String statement, boolean pseudoOnly) {
        if (pseudoOnly && (at >= words.size()
                || words.get(at).kind() != TextWordKind.PSEUDO_DELIMITER)) {
            throw new SourceFormatException(origin,
                    statement + " operands must be pseudo-text enclosed in ==");
        }
        return readOperand(words, at, origin);
    }

    /** 読み取った 1 組と、その最後の語の位置。 */
    record Pair(TextReplacement replacement, int endIndex) {
    }

    /** 指定位置から語の列が一致するかどうか。 */
    public static boolean matchesAt(List<TextWord> body, int at, List<TextWord> pattern) {
        return matchLength(body, at, pattern) >= 0;
    }

    /**
     * 指定位置から語の列が一致するなら、<b>本文を何語ぶん消費するか</b>を返す。
     * 一致しなければ {@code -1}。
     *
     * <p>コンマとセミコロンの区切りは、照合では<b>空白と同じ</b>ものとして扱う
     * (85 規格 XII 3.4 一般規則 6(b))。だから擬似テキストからは取り除き、本文では
     * 読み飛ばす。{@code ==MOVE;  "FAIL"  , TO==} は {@code MOVE  , "FAIL";      TO} に
     * 当たらなければならない (SM208A REP-TEST-8)。
     *
     * <p>読み飛ばすのは語と語の<b>あいだ</b>だけである。先頭でも読み飛ばすと、
     * 一致に含まれないはずのコンマまで消してしまう。消費した語数を返すのは、
     * 読み飛ばした区切りのぶん擬似テキストより長くなりうるためである。
     */
    public static int matchLength(List<TextWord> body, int at, List<TextWord> pattern) {
        List<TextWord> wanted = withoutSeparators(pattern);
        if (wanted.isEmpty() || at >= body.size() || isSpaceSeparator(body.get(at))) {
            return -1;
        }
        int i = at;
        for (int k = 0; k < wanted.size(); k++) {
            while (k > 0 && i < body.size() && isSpaceSeparator(body.get(i))) {
                i++;
            }
            if (i >= body.size() || !body.get(i).matches(wanted.get(k))) {
                return -1;
            }
            i++;
        }
        return i - at;
    }

    /** 照合では空白と同じ扱いになる区切り文字か。終止符は<b>含まない</b>。 */
    private static boolean isSpaceSeparator(TextWord word) {
        return word.isSeparator(',') || word.isSeparator(';');
    }

    private static List<TextWord> withoutSeparators(List<TextWord> pattern) {
        List<TextWord> out = new ArrayList<>(pattern.size());
        for (TextWord word : pattern) {
            if (!isSpaceSeparator(word)) {
                out.add(word);
            }
        }
        return out;
    }

    /**
     * 置換の被演算子を読む。
     *
     * <p>擬似テキスト {@code ==...==} は語の列である。それ以外は<b>一意名 1 個</b>で
     * あって、1 語とは限らない。規格は修飾 ({@code OF} / {@code IN}) と添字を許している。
     *
     * <pre>
     * REPLACING FALSE-DATA-1 BY TRUE-Q-04 OF TRUE-Q-03 IN TRUE-Q-02
     *           FALSE-DATA-3 BY Z (2, 1, 1)
     * </pre>
     *
     * <p>1 語しか読まないと、続く {@code OF} を次の被演算子と読んでしまい
     * 「{@code BY} が無い」と断ってしまう (SM202A / SM206A がそれで落ちていた)。
     *
     * @param start 被演算子の開始位置
     * @return 読み取った語の列と、被演算子の最後の語の位置
     */
    public static Operand readOperand(List<TextWord> words, int start, Origin origin) {
        if (start >= words.size()) {
            throw new SourceFormatException(origin, "a replacement operand is missing");
        }
        if (words.get(start).kind() != TextWordKind.PSEUDO_DELIMITER) {
            return readName(words, start, origin);
        }
        List<TextWord> collected = new ArrayList<>();
        int i = start + 1;
        while (i < words.size() && words.get(i).kind() != TextWordKind.PSEUDO_DELIMITER) {
            collected.add(words.get(i));
            i++;
        }
        if (i >= words.size()) {
            throw new SourceFormatException(origin, "pseudo-text is not terminated by ==");
        }
        return new Operand(collected, i);
    }

    /** 修飾と添字を含む一意名 1 個を読む。 */
    private static Operand readName(List<TextWord> words, int start, Origin origin) {
        List<TextWord> collected = new ArrayList<>();
        collected.add(words.get(start));
        int i = start + 1;
        while (i + 1 < words.size()
                && (words.get(i).isWord("OF") || words.get(i).isWord("IN"))) {
            collected.add(words.get(i));
            collected.add(words.get(i + 1));
            i += 2;
        }
        if (i < words.size() && words.get(i).isSeparator('(')) {
            int depth = 0;
            while (i < words.size()) {
                TextWord word = words.get(i);
                collected.add(word);
                i++;
                if (word.isSeparator('(')) {
                    depth++;
                } else if (word.isSeparator(')')) {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            if (depth != 0) {
                throw new SourceFormatException(origin,
                        "a replacement operand has an unclosed subscript");
            }
        }
        return new Operand(collected, i - 1);
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
