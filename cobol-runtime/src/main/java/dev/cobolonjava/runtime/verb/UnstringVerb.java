package dev.cobolonjava.runtime.verb;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code UNSTRING} 文の意味論 (要件 FR-065)。
 *
 * <p>送出項目を区切り文字で分割し、受取項目へ順に転記する。
 *
 * <p>受取項目への転記は英数字転記の規則に従う。左詰めで、余った右側は空白で埋め、
 * あふれた右側は切り捨てる。{@code STRING} が受取項目を埋めないのとは対照的である。
 */
public final class UnstringVerb {

    private UnstringVerb() {
    }

    /**
     * 区切り文字の指定。
     *
     * @param value 区切り文字
     * @param all   {@code ALL} 指定。連続する区切り文字を 1 個として扱う
     */
    public record Delimiter(byte[] value, boolean all) {

        public static Delimiter of(byte[] value) {
            return new Delimiter(value, false);
        }

        public static Delimiter all(byte[] value) {
            return new Delimiter(value, true);
        }
    }

    /**
     * 受取項目の指定。
     *
     * @param length         受取項目のバイト長
     * @param justifiedRight {@code JUSTIFIED RIGHT} 指定
     */
    public record Field(int length, boolean justifiedRight) {

        public static Field of(int length) {
            return new Field(length, false);
        }
    }

    /**
     * 実行結果。
     *
     * @param fields     各受取項目の内容。転記が行われなかった項目は含まれない
     * @param delimiters 各受取項目に対応して検出された区切り文字。末尾で見つからなければ空の配列
     * @param counts     各受取項目へ転記された文字数 ({@code COUNT IN} が受け取る値)
     * @param pointer    次に走査する位置 (1 起点)。{@code WITH POINTER} が受け取る値
     * @param tallying   転記された受取項目の数 ({@code TALLYING IN} が受け取る値)
     * @param overflow   オーバーフロー条件が立ったかどうか
     */
    public record Result(List<byte[]> fields, List<byte[]> delimiters, List<Integer> counts,
                         int pointer, int tallying, boolean overflow) {
    }

    /**
     * {@code UNSTRING} を実行する。
     *
     * @param source       送出項目
     * @param startPointer 走査を始める位置 (1 起点)
     * @param delimiters   区切り文字の並び。空なら各受取項目がその長さぶんを順に取る
     * @param fields       受取項目の並び
     */
    public static Result unstring(byte[] source, int startPointer, List<Delimiter> delimiters,
                                  List<Field> fields, CodePage codePage) {
        List<byte[]> outFields = new ArrayList<>();
        List<byte[]> outDelimiters = new ArrayList<>();
        List<Integer> outCounts = new ArrayList<>();

        // ポインタが送出項目の範囲外なら、何も転記せずにオーバーフローとなる
        if (startPointer < 1 || startPointer > source.length) {
            return new Result(outFields, outDelimiters, outCounts, startPointer, 0, true);
        }

        int position = startPointer - 1;
        for (Field field : fields) {
            if (position >= source.length) {
                break;
            }
            int fieldStart = position;
            Optional<Match> match = delimiters.isEmpty()
                    ? Optional.empty()
                    : findEarliest(source, position, delimiters);

            int fieldEnd;
            byte[] foundDelimiter;
            if (match.isPresent()) {
                Match m = match.get();
                fieldEnd = m.at();
                foundDelimiter = m.delimiter().value().clone();
                position = m.at() + m.delimiter().value().length;
                if (m.delimiter().all()) {
                    // 連続する同じ区切り文字をまとめて読み飛ばす
                    while (Region.matchesAt(source, m.delimiter().value(), position)) {
                        position += m.delimiter().value().length;
                    }
                }
            } else if (delimiters.isEmpty()) {
                // 区切り指定がない場合は受取項目の長さぶんを取る
                fieldEnd = Math.min(source.length, position + field.length());
                foundDelimiter = new byte[0];
                position = fieldEnd;
            } else {
                fieldEnd = source.length;
                foundDelimiter = new byte[0];
                position = source.length;
            }

            byte[] content = java.util.Arrays.copyOfRange(source, fieldStart, fieldEnd);
            // <b>切り出したそのまま</b>を返す。受取項目へ入れるのは呼ぶ側の仕事である。
            // 数字項目なら小数点で位置を合わせ、JUSTIFIED なら右へ寄せる。
            // ここで英数字として詰めてしまうと、その区別が消える (NC218A)
            outFields.add(content);
            outDelimiters.add(foundDelimiter);
            outCounts.add(content.length);
        }

        boolean overflow = position < source.length;
        return new Result(outFields, outDelimiters, outCounts, position + 1, outFields.size(), overflow);
    }

    private record Match(int at, Delimiter delimiter) {
    }

    /**
     * 最も手前に現れる区切り文字を探す。同じ位置に複数が一致する場合は、
     * {@code DELIMITED BY} に書かれた順で先のものを採る。
     */
    private static Optional<Match> findEarliest(byte[] source, int from, List<Delimiter> delimiters) {
        Match best = null;
        for (Delimiter d : delimiters) {
            if (d.value().length == 0) {
                continue;
            }
            int at = Region.indexOf(source, d.value(), from);
            if (at < 0) {
                continue;
            }
            if (best == null || at < best.at()) {
                best = new Match(at, d);
            }
        }
        return Optional.ofNullable(best);
    }
}
