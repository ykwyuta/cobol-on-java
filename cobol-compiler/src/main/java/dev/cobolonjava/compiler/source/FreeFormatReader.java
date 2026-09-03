package dev.cobolonjava.compiler.source;

/**
 * 自由形式ソースの読み取りと正規化 (要件 FR-002)。
 *
 * <p>カラムの区分がない。行の全体が本文であり、注釈は {@code *>} で書く。
 * 出力の形は固定形式とまったく同じ ({@link NormalizedSource}) であるため、
 * 後段はどちらの形式で書かれたかを知らない。
 *
 * <h2>行の区切りは語の区切りである</h2>
 * <p>固定形式と違い、文の途中で行を変えるのに継続の印は要らない。行が変われば
 * 語の区切りが入る。継続が要るのは<b>文字定数を途中で切るとき</b>だけである。
 *
 * <h2>定数の継続は行末のハイフンで示す</h2>
 * <p>行の末尾の非空白文字が {@code -} のとき、その直前までが定数の内容になる。
 * ハイフンが要るのは<b>末尾の空白を定数に含められるようにする</b>ためである。
 * ハイフンがなければ、行末の空白が定数の一部かどうかを、ソースの見た目から決められない。
 * 次の行は、空白を挟んでよいが、同じ引用符から再開しなければならない。
 *
 * <p>定数の外での行末ハイフンは継続とみなさない。{@code COMPUTE A = B -} で行を変える
 * 書き方を壊さないためである (暫定判断 P-022)。
 */
public final class FreeFormatReader implements SourceReader {

    /** 定数の継続を示す、行末の文字。 */
    private static final char CONTINUATION = '-';

    private FreeFormatReader() {
    }

    /** 自由形式の読み取り器。 */
    public static FreeFormatReader standard() {
        return new FreeFormatReader();
    }

    @Override
    public NormalizedSource normalize(String fileName, String source) {
        NormalizedSource.Builder out = new NormalizedSource.Builder();
        String[] lines = source.split("\\R", -1);
        // 直前の行が文字定数の途中で終わっているか
        char openQuote = 0;
        int openLine = 0;

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            int number = i + 1;
            if (openQuote != 0) {
                openQuote = appendContinuation(out, fileName, number, raw, openQuote);
                if (openQuote != 0) {
                    openLine = number;
                }
                continue;
            }
            char result = appendNormal(out, fileName, number, raw);
            if (result != 0) {
                openQuote = result;
                openLine = number;
            }
        }

        if (openQuote != 0) {
            throw new SourceFormatException(fileName + ":" + openLine
                    + ": a non-numeric literal is left unclosed at end of source");
        }
        return out.build();
    }

    private char appendNormal(NormalizedSource.Builder out, String fileName, int number,
                              String raw) {
        String content = SourceText.stripInlineComment(raw, (char) 0);
        char openQuote = SourceText.openQuoteAtEnd(content, (char) 0);
        if (openQuote != 0) {
            content = content.substring(0, requireContinuationHyphen(fileName, number, content));
        } else {
            content = SourceText.stripTrailing(content);
        }
        if (content.isBlank()) {
            return 0;
        }
        out.appendSeparator();
        int leading = SourceText.countLeadingSpaces(content);
        out.append(content.substring(leading), fileName, number, leading + 1);
        return openQuote;
    }

    private char appendContinuation(NormalizedSource.Builder out, String fileName, int number,
                                    String raw, char openQuote) {
        int first = SourceText.countLeadingSpaces(raw);
        if (first >= raw.length()) {
            throw new SourceFormatException(fileName + ":" + number
                    + ": a continued non-numeric literal must resume on the next line");
        }
        if (raw.charAt(first) != openQuote) {
            throw new SourceFormatException(fileName + ":" + number
                    + ": a continued non-numeric literal must resume with the quotation character "
                    + openQuote);
        }

        int start = first + 1;
        String rest = SourceText.stripInlineComment(raw.substring(start), openQuote);
        char resulting = SourceText.openQuoteAtEnd(rest, openQuote);
        if (resulting != 0) {
            rest = rest.substring(0, requireContinuationHyphen(fileName, number, rest));
        } else {
            rest = SourceText.stripTrailing(rest);
        }
        // 継続では区切りの空白を入れない。定数の内容が直接つながる
        out.append(rest, fileName, number, start + 1);
        return resulting;
    }

    /** 定数が閉じていない行は、末尾のハイフンで継続を示さなければならない。 */
    private static int requireContinuationHyphen(String fileName, int number, String content) {
        int last = SourceText.lastNonBlankIndex(content);
        if (last < 0 || content.charAt(last) != CONTINUATION) {
            throw new SourceFormatException(fileName + ":" + number
                    + ": a non-numeric literal that crosses a line must end the line with '"
                    + CONTINUATION + "'");
        }
        return last;
    }
}
