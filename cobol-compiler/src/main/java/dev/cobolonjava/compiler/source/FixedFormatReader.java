package dev.cobolonjava.compiler.source;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定形式ソースの読み取りと正規化 (要件 FR-002, FR-003, FR-094)。
 *
 * <p>ARC-8 の方針に従い、<b>文脈依存の処理はここへ集める</b>。後段の構文解析器には
 * カラムも継続行も見せない。
 *
 * <h2>継続の規則が中心である</h2>
 *
 * <p>継続行 (7 桁目が {@code -}) の扱いは 2 通りあり、<b>直前の行が文字定数の途中で
 * 終わっているかどうか</b>で変わる。
 *
 * <table>
 *   <caption>継続の 2 つの場合</caption>
 *   <tr><th>直前の行の状態</th><th>扱い</th></tr>
 *   <tr>
 *     <td>文字定数が閉じていない</td>
 *     <td>直前の行は<b>72 桁まで定数の一部</b>とみなす (物理行が短くても空白で埋める)。
 *         継続行の B 領域の最初の非空白は引用符でなければならず、それを読み飛ばして続きを連結する</td>
 *   </tr>
 *   <tr>
 *     <td>定数の外</td>
 *     <td>語の途中で切れているとみなし、継続行の最初の非空白から<b>区切りを入れずに</b>連結する</td>
 *   </tr>
 * </table>
 *
 * <p>1 番目の規則を落とすと、行末の空白を削ったソースと削っていないソースで
 * 文字定数の長さが変わってしまう。実務のソースは行末が削られていることが多いため、
 * ここは意識して実装する必要がある。
 *
 * <h2>行内注釈</h2>
 * <p>{@code *>} 以降は行の終わりまで注釈である。<b>文字定数の中の {@code *>} は注釈ではない</b>ため、
 * 引用符を追いながら切る。{@code MOVE '*>' TO X} を注釈として落とすと、黙って別のソースになる。
 */
public final class FixedFormatReader implements SourceReader {

    private final boolean debuggingMode;

    /**
     * @param debuggingMode {@code WITH DEBUGGING MODE} 相当。デバッグ行を有効にするかどうか
     */
    public FixedFormatReader(boolean debuggingMode) {
        this.debuggingMode = debuggingMode;
    }

    @Override
    public SourceFormat format() {
        return SourceFormat.FIXED;
    }

    /** デバッグ行を無効にした読み取り器。 */
    public static FixedFormatReader standard() {
        return new FixedFormatReader(false);
    }

    /** 物理行の並びをカラムで分解する。 */
    public List<SourceLine> split(String fileName, String source) {
        List<SourceLine> lines = new ArrayList<>();
        String[] raw = source.split("\\R", -1);
        for (int i = 0; i < raw.length; i++) {
            String line = raw[i];
            if (line.isEmpty()) {
                continue;
            }
            lines.add(splitLine(fileName, i + 1, line));
        }
        return lines;
    }

    private SourceLine splitLine(String fileName, int lineNumber, String raw) {
        char indicatorChar = raw.length() >= SourceLine.INDICATOR_COLUMN
                ? raw.charAt(SourceLine.INDICATOR_COLUMN - 1) : ' ';
        LineIndicator indicator;
        try {
            indicator = LineIndicator.of(indicatorChar);
        } catch (SourceFormatException e) {
            throw new SourceFormatException(new Origin(fileName, lineNumber, 1), "" + e.getMessage());
        }

        int from = Math.min(raw.length(), SourceLine.CONTENT_START_COLUMN - 1);
        int to = Math.min(raw.length(), SourceLine.MARGIN_COLUMN);
        String content = from < to ? raw.substring(from, to) : "";
        return new SourceLine(fileName, lineNumber, raw, indicator, content);
    }

    /** 物理行の並びを 1 本の正規化済みソースへまとめる。 */
    @Override
    public NormalizedSource normalize(String fileName, String source) {
        return normalize(split(fileName, source));
    }

    /** 分解済みの行を 1 本の正規化済みソースへまとめる。 */
    public NormalizedSource normalize(List<SourceLine> lines) {
        NormalizedSource.Builder out = new NormalizedSource.Builder();
        // 直前の行が文字定数の途中で終わっているか。継続の扱いを分ける
        char openQuote = 0;

        for (SourceLine line : lines) {
            switch (line.indicator()) {
                case COMMENT, EJECT -> {
                    continue;
                }
                case DEBUG -> {
                    if (!debuggingMode) {
                        continue;
                    }
                }
                default -> {
                }
            }

            if (line.indicator() == LineIndicator.CONTINUATION) {
                openQuote = appendContinuation(out, line, openQuote);
            } else {
                if (openQuote != 0) {
                    throw new SourceFormatException(new Origin(line.fileName(), line.lineNumber(), 1), "a non-numeric literal is left unclosed and the next line is not a"
                            + " continuation line");
                }
                openQuote = appendNormal(out, line);
            }
        }

        if (openQuote != 0) {
            throw new SourceFormatException("a non-numeric literal is left unclosed at end of source");
        }
        return out.build();
    }

    /** 通常の行を、区切りの空白を挟んで連結する。 */
    private char appendNormal(NormalizedSource.Builder out, SourceLine line) {
        // 行内注釈は本文の一部ではない。定数の中の *> は注釈ではないので、
        // 引用符を追いながら切る
        String content = SourceText.stripInlineComment(line.content(), (char) 0);
        char openQuote = SourceText.openQuoteAtEnd(content, (char) 0);
        if (openQuote != 0) {
            // 定数が閉じていないので 72 桁まで定数の一部として扱う
            content = line.contentPaddedToMargin();
        } else {
            content = SourceText.stripTrailing(content);
        }
        if (content.isBlank() && openQuote == 0) {
            return 0;
        }
        out.appendSeparator();
        int leading = SourceText.countLeadingSpaces(content);
        out.append(content.substring(leading), line.fileName(), line.lineNumber(),
                line.columnOf(leading));
        return openQuote;
    }

    /**
     * 継続行を連結する。
     *
     * @param openQuote 直前の行で開いたままの引用符。0 なら定数の外
     * @return この行の末尾で開いたままの引用符
     */
    private char appendContinuation(NormalizedSource.Builder out, SourceLine line, char openQuote) {
        String content = line.content();
        int first = SourceText.countLeadingSpaces(content);
        if (first >= content.length()) {
            throw new SourceFormatException(new Origin(line.fileName(), line.lineNumber(), 1), "a continuation line has no content in area B");
        }

        int start = first;
        if (openQuote != 0) {
            // 定数の継続。B 領域の最初の非空白は引用符でなければならず、それは定数に含めない
            if (content.charAt(first) != openQuote) {
                throw new SourceFormatException(new Origin(line.fileName(), line.lineNumber(), 1), "a continued non-numeric literal must resume with the quotation"
                        + " character " + openQuote);
            }
            start = first + 1;
        }

        String rest = SourceText.stripInlineComment(content.substring(start), openQuote);
        char resulting = SourceText.openQuoteAtEnd(rest, openQuote);
        if (resulting != 0) {
            // まだ閉じていないので、この行も 72 桁まで定数の一部になる
            rest = line.contentPaddedToMargin().substring(start);
        } else {
            rest = SourceText.stripTrailing(rest);
        }
        // 継続では区切りの空白を入れない。語も定数も直接つながる
        out.append(rest, line.fileName(), line.lineNumber(), line.columnOf(start));
        return resulting;
    }

}
