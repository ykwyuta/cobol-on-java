package dev.cobolonjava.compiler.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 展開後ソースのリスト出力 (要件 FR-094, FR-182)。
 *
 * <p>プリプロセッサの出力は改行を持たない 1 本の文字列である。これをそのまま見ても
 * 何が起きたか分からない。リストは<b>元のソース行ごとに折り返し、出自を添えて</b>示す。
 *
 * <p>{@code COPY} で展開された行にはコピー句のファイル名が出る。移行作業では
 * 「展開後に何が入っているか」を確認する必要があり、その道具になる。
 * {@code COPY ... SUPPRESS} が指定されたコピー句の行は、リストからだけ落ちる
 * (展開結果は変わらない)。
 *
 * <h2>折り返しの位置は出自の変わり目である</h2>
 * <p>正規化後のテキストには行の区切りが残っていないため、<b>文字の出自 (ファイルと行) が
 * 変わったところ</b>を折り返しの位置とする。結果として、コピー句から来た部分は
 * コピー句の行の並びとして現れる。
 */
public final class SourceListing {

    /** コピー句から展開された行に付ける印。参照実装のリストに倣っている。 */
    private static final char COPIED_MARKER = 'C';

    private SourceListing() {
    }

    /**
     * リストの 1 行。
     *
     * @param number 出力上の行番号 (1 起点)
     * @param text   その行の内容
     * @param origin 先頭の文字の出自
     */
    public record ListingLine(int number, String text, Origin origin) {
    }

    /** 正規化済みソースを、出自の変わり目で折り返した行の並びにする。 */
    public static List<ListingLine> lines(NormalizedSource source) {
        List<ListingLine> lines = new ArrayList<>();
        if (source.length() == 0) {
            return lines;
        }

        StringBuilder current = new StringBuilder();
        Origin currentOrigin = source.originOf(0);

        for (int i = 0; i < source.length(); i++) {
            Origin origin = source.originOf(i);
            if (!sameSourceLine(origin, currentOrigin)) {
                addLine(lines, current, currentOrigin);
                current.setLength(0);
                currentOrigin = origin;
            }
            current.append(source.text().charAt(i));
        }
        addLine(lines, current, currentOrigin);
        return lines;
    }

    /**
     * リストを文字列として組み立てる。
     *
     * <p>主ソースの名前は正規化済みソースからは決められない。先頭の行がコピー句から
     * 来ていることがあるためである。したがって呼び出し側から受け取る。
     *
     * @param primaryFile 主ソースのファイル名。これ以外のファイルから来た行に印を付ける
     */
    public static String render(NormalizedSource source, String primaryFile) {
        return render(source, primaryFile, Set.of());
    }

    /**
     * リストを文字列として組み立て、{@code COPY ... SUPPRESS} で指定されたコピー句を除く。
     *
     * @param suppressedFiles 印字しないファイル名 ({@link CopyExpander#suppressedFiles()})
     */
    public static String render(NormalizedSource source, String primaryFile,
                                Set<String> suppressedFiles) {
        StringBuilder out = new StringBuilder();
        int number = 0;
        for (ListingLine line : lines(source)) {
            if (suppressedFiles.contains(line.origin().fileName())) {
                continue;
            }
            // 抑止した行を飛ばしたぶんだけ番号を詰める。番号は「リストの何行目か」である
            number++;
            boolean copied = !Objects.equals(line.origin().fileName(), primaryFile);
            out.append(String.format("%5d %c %-20s %s%n",
                    number,
                    copied ? COPIED_MARKER : ' ',
                    line.origin().fileName() + ":" + line.origin().line(),
                    line.text()));
        }
        return out.toString();
    }

    private static boolean sameSourceLine(Origin a, Origin b) {
        return a.line() == b.line() && Objects.equals(a.fileName(), b.fileName());
    }

    private static void addLine(List<ListingLine> lines, StringBuilder text, Origin origin) {
        // 語と語の区切りに置かれた空白は、元のソース上の特定の文字に対応しない。
        // どちらの行に寄せられるかは経路によって変わる (連結の直後か、字句の再構成か) ため、
        // 両端の空白を落として揺れを消す。元の桁は Origin が保っている
        String content = stripSpaces(text.toString());
        if (content.isEmpty()) {
            return;
        }
        lines.add(new ListingLine(lines.size() + 1, content, origin));
    }

    private static String stripSpaces(String s) {
        int begin = 0;
        int end = s.length();
        while (end > begin && s.charAt(end - 1) == ' ') {
            end--;
        }
        while (begin < end && s.charAt(begin) == ' ') {
            begin++;
        }
        return s.substring(begin, end);
    }
}
