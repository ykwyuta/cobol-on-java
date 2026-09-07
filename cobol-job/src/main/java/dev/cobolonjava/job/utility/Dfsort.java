package dev.cobolonjava.job.utility;

import dev.cobolonjava.job.jcl.JclOperands;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.item.Usage;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.sort.SortKey;
import dev.cobolonjava.runtime.sort.SortWork;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code SORT} (DFSORT) の互換実装 (要件 FR-137)。
 *
 * <p>バッチでいちばん重いのは整列である。COBOL の {@code SORT} 動詞が使われる場面より、
 * ジョブステップとして整列ユーティリティを呼ぶ場面のほうが多い。
 *
 * <h2>並べ替えの中身は COBOL の SORT と同じものを使う</h2>
 * <p>鍵の比べ方は {@link SortWork} が持っている。ここでやるのは<b>制御文を鍵へ翻訳する</b>
 * ことだけである。「並べ替えの規則が 2 か所にある」状態を作らないためであり、
 * {@code SORT} 動詞で正しいものは、ここでも正しい。
 *
 * <h2>通り道</h2>
 * <p>読む → {@code INCLUDE}/{@code OMIT} でふるう → 並べ替える → {@code SUM} でまとめる →
 * {@code OUTREC} で組み直す → 書く。この順はホストの決まりであり、たとえば {@code SUM} は
 * <b>並べ替えたあと</b>にしか効かない。順を入れ替えると答えが変わる。
 *
 * <h2>位置は 1 から数える</h2>
 * <p>制御文の位置はレコードの先頭を 1 とする。可変長では<b>先頭の 4 バイトは長さ</b>で
 * あり、データは 5 から始まる。ここでも長さをレコードの一部として持つので、
 * ホストと同じ数え方になる。
 */
public final class Dfsort extends UtilityProgram {

    /** 整列の入力。 */
    private static final String SORTIN = "SORTIN";
    /** 整列の出力。 */
    private static final String SORTOUT = "SORTOUT";

    /** ふるい分けの 1 つ。 */
    private sealed interface Test {
        boolean holds(byte[] record, CodePage codePage);
    }

    private record Always(boolean value) implements Test {
        @Override
        public boolean holds(byte[] record, CodePage codePage) {
            return value;
        }
    }

    private record All(List<Test> parts) implements Test {
        @Override
        public boolean holds(byte[] record, CodePage codePage) {
            for (Test part : parts) {
                if (!part.holds(record, codePage)) {
                    return false;
                }
            }
            return true;
        }
    }

    private record Any(List<Test> parts) implements Test {
        @Override
        public boolean holds(byte[] record, CodePage codePage) {
            for (Test part : parts) {
                if (part.holds(record, codePage)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 場所と定数を比べる。 */
    private record Constant(SortField field, String relation, byte[] bytes, Decimal number)
            implements Test {
        @Override
        public boolean holds(byte[] record, CodePage codePage) {
            int order = bytes != null
                    ? SortField.compareBytes(field.slice(record, codePage), bytes)
                    : field.number(record, codePage).compareTo(number);
            return relates(order, relation);
        }
    }

    /** 場所と場所を比べる。 */
    private record Between(SortField left, String relation, SortField right) implements Test {
        @Override
        public boolean holds(byte[] record, CodePage codePage) {
            int order = left.format() == SortField.Format.CH || left.format() == SortField.Format.BI
                    ? SortField.compareBytes(left.slice(record, codePage), right.slice(record, codePage))
                    : left.number(record, codePage)
                            .compareTo(right.number(record, codePage));
            return relates(order, relation);
        }
    }

    /**
     * {@code OUTFIL} に付ける見出しと末尾 (要件 FR-137)。
     *
     * <p>{@code 1} が付くものはデータセット全体を囲み、{@code 2} が付くものは
     * {@code LINES=n} で切った頁ごとに付く。
     *
     * @param lines 1 頁に入るレコードの数。{@code 0} なら頁で切らない
     */
    private record Report(List<String> header1, List<String> trailer1,
                          List<String> header2, List<String> trailer2, int lines) {

        /** 何も書かれていなければ、行を足す仕事そのものが無い。 */
        boolean empty() {
            return header1 == null && trailer1 == null && header2 == null && trailer2 == null;
        }
    }

    /**
     * {@code OUTFIL} 1 個。
     *
     * <p>1 回読んだものを<b>いくつもの出力へ振り分ける</b>ための指定である。条件ごとに
     * ジョブステップを分けると、そのたびに入力を読み直すことになる。
     *
     * @param dds      書き先の DD 名
     * @param save     どの {@code OUTFIL} にも選ばれなかったものを受け取るか
     * @param startRec 何本目から取るか。{@code 1} なら先頭から
     * @param endRec   何本目まで取るか。{@code 0} なら終わりまで
     * @param splitBy  書き先へ順ぐりに配る本数。{@code 0} なら配らない
     */
    private record OutFile(List<String> dds, Test include, Test omit, List<String> outrec,
                           boolean save, int startRec, int endRec, int splitBy, Report report) {
    }

    private int highest;
    private final List<String> notes = new ArrayList<>();

    /** 並べ替えの鍵。空なら並べ替えない。 */
    private List<SortField> sortFields = List.of();
    /** {@code FIELDS=COPY} または {@code OPTION COPY}。 */
    private boolean copying;
    /** {@code MERGE}。入力は {@code SORTINnn} である。 */
    private boolean merging;
    private Test include;
    private Test omit;
    /** {@code OUTREC} の項目。書かれていなければ {@code null}。 */
    private List<String> outrec;
    /** {@code INREC} の項目。書かれていなければ {@code null}。 */
    private List<String> inrec;
    /** {@code OUTFIL} の並び。書かれていなければ空。 */
    private final List<OutFile> outFiles = new ArrayList<>();
    /** {@code SUM} で足す場所。書かれていなければ {@code null}。 */
    private List<SortField> sumFields;
    /** {@code SUM FIELDS=NONE}。 */
    private boolean sumNone;
    /** 制御文に {@code SORT} も {@code MERGE} も書かれていたか。 */
    private boolean stated;

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        CodePage codePage = context.codePage();
        for (String statement : statements(control(context, SYSIN))) {
            parse(statement, codePage);
        }
        print(context, "ICE143I 0 BLOCKSET " + (merging ? "MERGE" : "SORT")
                + " TECHNIQUE SELECTED");
        if (!stated) {
            fail("ICE000I NO SORT OR MERGE STATEMENT WAS GIVEN");
        }
        if (highest == 0) {
            process(context, codePage);
        }
        for (String note : notes) {
            print(context, note);
        }
        print(context, highest == 0
                ? "ICE052I 0 END OF DFSORT"
                : "ICE751I 0 END OF DFSORT - UNSUCCESSFUL");
        context.setReturnCode(highest);
    }

    // ---- 通り道 ----

    private void process(ProgramContext context, CodePage codePage) {
        List<Path> inputs = inputs(context);
        if (inputs.isEmpty()) {
            fail("ICE046A 0 SORT CAPACITY EXCEEDED - NO INPUT DATA SET");
            return;
        }
        DataSetAttributes attributes = DataSetAttributes.read(inputs.get(0));
        List<byte[]> records = new ArrayList<>();
        for (Path input : inputs) {
            records.addAll(Records.split(readSound(context, input), attributes));
        }
        int read = records.size();

        records = filter(records, include, omit, codePage);
        // INREC は並べ替えの前に組み直す。だから SORT FIELDS の位置は組み直したあとを指す
        records = reformat(records, inrec, attributes, "INREC", codePage);
        if (!copying && !sortFields.isEmpty()) {
            records = ordered(records, codePage);
        }
        if (sumFields != null || sumNone) {
            records = summed(records, codePage);
        }
        records = reformat(records, outrec, attributes, "OUTREC", codePage);
        if (highest != 0) {
            return;
        }
        if (outFiles.isEmpty()) {
            write(context, SORTOUT, records, attributes, inrec != null || outrec != null);
            note("ICE054I 0 RECORDS - IN: " + read + ", OUT: " + records.size());
            return;
        }
        note("ICE054I 0 RECORDS - IN: " + read + ", OUT: " + records.size());
        writeOutFiles(context, records, attributes, codePage);
    }

    /**
     * {@code INREC} と {@code OUTREC} の組み直し。
     *
     * @param items 書かれていなければ {@code null}。そのときは何もしない
     */
    private List<byte[]> reformat(List<byte[]> records, List<String> items,
                                  DataSetAttributes attributes, String verb, CodePage codePage) {
        if (items == null) {
            return records;
        }
        if (attributes.format() == RecordFormat.VARIABLE) {
            fail("ICE000I " + verb + " ON A VARIABLE LENGTH DATA SET IS NOT SUPPORTED YET");
            return records;
        }
        return rebuilt(records, items, codePage);
    }

    /**
     * {@code OUTFIL} の書き出し (要件 FR-137)。
     *
     * <p>1 回読んだものをいくつもの出力へ振り分ける。条件ごとにステップを分けると、
     * そのたびに<b>入力を読み直す</b>ことになる。実資産で重い整列を 1 回で済ませる仕掛けが
     * これである。
     *
     * <p>選ぶ順はホストの決まりに従う。まず {@code STARTREC} / {@code ENDREC} が
     * <b>並びの何本目か</b>で切り、そのあとで {@code INCLUDE} / {@code OMIT} が中身で
     * ふるう。逆にすると {@code STARTREC=2} の数え方が変わって答えが違う。
     *
     * <p>{@code SAVE} は「どこにも選ばれなかったもの」を受け取る。だから先に他のものを
     * 決めてから残りを配る。
     */
    private void writeOutFiles(ProgramContext context, List<byte[]> records,
                               DataSetAttributes attributes, CodePage codePage) {
        boolean[] taken = new boolean[records.size()];
        Map<OutFile, List<byte[]>> selected = new LinkedHashMap<>();
        for (OutFile file : outFiles) {
            if (file.save()) {
                continue;
            }
            List<byte[]> chosen = new ArrayList<>();
            for (int i = 0; i < records.size(); i++) {
                if (!within(file, i + 1) || !matches(records.get(i), file, codePage)) {
                    continue;
                }
                chosen.add(records.get(i));
                taken[i] = true;
            }
            selected.put(file, chosen);
        }
        for (OutFile file : outFiles) {
            if (!file.save()) {
                continue;
            }
            List<byte[]> chosen = new ArrayList<>();
            for (int i = 0; i < records.size(); i++) {
                if (!taken[i]) {
                    chosen.add(records.get(i));
                }
            }
            selected.put(file, chosen);
        }
        for (OutFile file : outFiles) {
            List<byte[]> chosen = reformat(selected.get(file), file.outrec(), attributes,
                    "OUTFIL OUTREC", codePage);
            if (highest != 0) {
                return;
            }
            boolean rebuilt = inrec != null || outrec != null || file.outrec() != null;
            if (file.splitBy() > 0) {
                split(context, file, chosen, attributes, rebuilt);
                continue;
            }
            List<byte[]> lines = decorated(chosen, file.report(), codePage);
            if (highest != 0) {
                return;
            }
            for (String dd : file.dds()) {
                write(context, dd, lines, attributes, rebuilt || !file.report().empty());
                note("ICE224I 0 RECORDS WRITTEN TO " + dd + ": " + lines.size());
            }
        }
    }

    /** {@code STARTREC} と {@code ENDREC} の範囲に入っているか。 */
    private static boolean within(OutFile file, int number) {
        return number >= file.startRec() && (file.endRec() <= 0 || number <= file.endRec());
    }

    /**
     * {@code SPLIT} と {@code SPLITBY=n} (要件 FR-137)。
     *
     * <p>同じ {@code OUTFIL} に書いた書き先へ<b>順ぐりに配る</b>。中身では分けられない
     * ものを均す仕掛けであり、後の処理を並べて走らせるために使われる。
     */
    private void split(ProgramContext context, OutFile file, List<byte[]> records,
                       DataSetAttributes attributes, boolean rebuilt) {
        int shares = file.dds().size();
        List<List<byte[]>> shared = new ArrayList<>();
        for (int i = 0; i < shares; i++) {
            shared.add(new ArrayList<>());
        }
        for (int i = 0; i < records.size(); i++) {
            shared.get((i / file.splitBy()) % shares).add(records.get(i));
        }
        for (int i = 0; i < shares; i++) {
            write(context, file.dds().get(i), shared.get(i), attributes, rebuilt);
            note("ICE224I 0 RECORDS WRITTEN TO " + file.dds().get(i) + ": "
                    + shared.get(i).size());
        }
    }

    /**
     * 見出しと末尾を付ける (要件 FR-137、暫定判断 P-047 の解消)。
     *
     * <p>整列の出力はそのまま報告書として印刷されることが多い。末尾の合計は<b>その上に
     * 並んだレコードから数えたもの</b>でなければならず、それが正しいのは同じ通り道が
     * 両方を作っているからである。合計を別のステップで数え直すと、間にふるいが 1 つ
     * 入っただけで合わなくなる。
     *
     * <p>{@code HEADER1} / {@code TRAILER1} はデータセット全体を囲み、
     * {@code HEADER2} / {@code TRAILER2} は {@code LINES=n} で切った頁ごとに付く。
     * だから {@code TRAILER1} の合計は全体、{@code TRAILER2} の合計は頁の分になる。
     */
    private List<byte[]> decorated(List<byte[]> records, Report report, CodePage codePage) {
        if (report.empty()) {
            return records;
        }
        List<byte[]> out = new ArrayList<>();
        int page = 1;
        if (report.header1() != null) {
            out.add(reportLine(report.header1(), records, page, codePage));
        }
        int depth = report.lines() > 0 ? report.lines() : Math.max(records.size(), 1);
        for (int at = 0; at < records.size(); at += depth) {
            List<byte[]> onPage = records.subList(at, Math.min(at + depth, records.size()));
            if (report.header2() != null) {
                out.add(reportLine(report.header2(), onPage, page, codePage));
            }
            out.addAll(onPage);
            if (report.trailer2() != null) {
                out.add(reportLine(report.trailer2(), onPage, page, codePage));
            }
            page++;
        }
        if (report.trailer1() != null) {
            out.add(reportLine(report.trailer1(), records, page, codePage));
        }
        return out;
    }

    /**
     * 見出しか末尾の 1 行を組む。
     *
     * @param scope 数えるレコード。{@code TRAILER1} なら全体、{@code TRAILER2} なら頁の分
     */
    private byte[] reportLine(List<String> items, List<byte[]> scope, int page,
                              CodePage codePage) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (String written : items) {
            String item = written.trim();
            int colon = item.indexOf(':');
            if (colon > 0 && digitsOnly(item.substring(0, colon))) {
                // 桁を言ってから中身を書く。p: だけで終わってもよい
                while (buffer.size() < number(item.substring(0, colon), 1) - 1) {
                    buffer.write(codePage.space());
                }
                item = item.substring(colon + 1).trim();
            }
            if (item.isEmpty()) {
                continue;
            }
            byte[] bytes = reportItem(item, scope, page, codePage);
            if (bytes == null) {
                return new byte[0];
            }
            buffer.writeBytes(bytes);
        }
        return buffer.toByteArray();
    }

    /**
     * 見出しと末尾に書ける 1 項目。
     *
     * @return 読めなければ {@code null}
     */
    private byte[] reportItem(String item, List<byte[]> scope, int page, CodePage codePage) {
        char last = item.charAt(item.length() - 1);
        if ((last == 'X' || last == 'Z') && digitsOnly(item.substring(0, item.length() - 1))) {
            int count = number(item.substring(0, item.length() - 1), 1);
            byte[] out = new byte[count];
            Arrays.fill(out, last == 'X' ? codePage.space() : (byte) 0);
            return out;
        }
        if (item.length() > 2 && (item.charAt(0) == 'C' || item.charAt(0) == 'X')
                && item.charAt(1) == '\'') {
            byte[] literal = literal(item, codePage);
            if (literal == null) {
                fail("ICE000I OUTFIL LITERAL IS NOT VALID: " + item);
            }
            return literal;
        }
        String key = JclOperands.key(item).toUpperCase(Locale.ROOT);
        String value = JclOperands.value(item);
        if (key.equals("TOTAL") || key.equals("MIN") || key.equals("MAX") || key.equals("AVG")) {
            return summary(key, value, scope, codePage);
        }
        String text = switch (key) {
            case "&PAGE" -> String.valueOf(page);
            case "&DATE" -> LocalDate.now().format(DateTimeFormatter.ofPattern("MM/dd/yy"));
            case "&TIME" -> LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            case "COUNT" -> String.valueOf(scope.size());
            default -> null;
        };
        if (text == null) {
            fail("ICE000I OUTFIL HEADER AND TRAILER ITEM IS NOT SUPPORTED YET: " + item);
            return null;
        }
        return codePage.encode(text);
    }

    /**
     * {@code TOTAL=(p,l,fmt)} のような数え上げ。
     *
     * <p>{@code TOTAL=(4,3,ZD,EDIT=(TTT))} のように、続けて書き方を言える。末尾の合計だけ
     * コンマが入らない、といったことにならないよう、書き方は {@code OUTREC} と同じ
     * {@link SortEdit} が読む。
     *
     * @return 場所が読めなければ {@code null}
     */
    private byte[] summary(String key, String value, List<byte[]> scope, CodePage codePage) {
        List<String> parts = JclOperands.split(JclOperands.unwrap(value));
        SortField field = placeOf(parts);
        if (field == null) {
            fail("ICE000I OUTFIL HEADER AND TRAILER ITEM IS NOT SUPPORTED YET: "
                    + key + "=" + value);
            return null;
        }
        List<String> decorations = parts.subList(Math.min(3, parts.size()), parts.size());
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal least = null;
        BigDecimal most = null;
        for (byte[] record : scope) {
            BigDecimal each = field.number(record, codePage).toBigDecimal();
            total = total.add(each);
            least = least == null || each.compareTo(least) < 0 ? each : least;
            most = most == null || each.compareTo(most) > 0 ? each : most;
        }
        BigDecimal answer = scope.isEmpty() ? BigDecimal.ZERO : switch (key) {
            case "TOTAL" -> total;
            case "MIN" -> least;
            case "MAX" -> most;
            default -> total.divide(BigDecimal.valueOf(scope.size()), 0, RoundingMode.DOWN);
        };
        if (decorations.isEmpty()) {
            return codePage.encode(answer.toPlainString());
        }
        return changed(Decimal.parse(answer.toPlainString()), field, decorations, codePage);
    }

    /**
     * {@code (p,l,fmt)} 1 つ。
     *
     * @return 読めなければ {@code null}
     */
    private static SortField placeOf(List<String> parts) {
        if (parts.size() < 3) {
            return null;
        }
        int position = number(parts.get(0).trim(), -1);
        int length = number(parts.get(1).trim(), -1);
        SortField.Format format = SortField.formatOf(parts.get(2).trim());
        if (position < 1 || length < 1 || format == null) {
            return null;
        }
        SortField field = SortField.at(position - 1, length, format);
        return field.supported() ? field : null;
    }

    private static boolean matches(byte[] record, OutFile file, CodePage codePage) {
        if (file.include() != null && !file.include().holds(record, codePage)) {
            return false;
        }
        return file.omit() == null || !file.omit().holds(record, codePage);
    }

    /** 入力のファイル。{@code MERGE} なら {@code SORTINnn} を番号の順に読む。 */
    private List<Path> inputs(ProgramContext context) {
        List<Path> out = new ArrayList<>();
        if (merging) {
            for (int i = 1; i <= 99; i++) {
                String name = SORTIN + (i < 10 ? "0" + i : String.valueOf(i));
                if (!context.catalog().isAssigned(name)) {
                    continue;
                }
                Path path = opened(context, name);
                if (Files.isReadable(path)) {
                    out.add(path);
                }
            }
            return out;
        }
        Path path = opened(context, SORTIN);
        if (Files.isReadable(path)) {
            out.add(path);
        }
        return out;
    }

    private static List<byte[]> filter(List<byte[]> records, Test keep, Test drop,
                                       CodePage codePage) {
        if (keep == null && drop == null) {
            return records;
        }
        List<byte[]> out = new ArrayList<>();
        for (byte[] record : records) {
            if (keep != null && !keep.holds(record, codePage)) {
                continue;
            }
            if (drop != null && drop.holds(record, codePage)) {
                continue;
            }
            out.add(record);
        }
        return out;
    }

    /** 並べ替えは {@link SortWork} に任せる。COBOL の {@code SORT} と同じ道である。 */
    private List<byte[]> ordered(List<byte[]> records, CodePage codePage) {
        List<SortKey> keys = new ArrayList<>();
        for (SortField field : sortFields) {
            keys.add(field.key(codePage));
        }
        SortWork work = new SortWork(keys, codePage);
        for (byte[] record : records) {
            work.release(record);
        }
        work.sort();
        return new ArrayList<>(work.all());
    }

    /**
     * {@code SUM}。鍵が等しいレコードをまとめ、合計欄を足す。
     *
     * <p>並べ替えたあとなので、鍵が等しいものは<b>隣り合っている</b>。前のレコードと
     * 比べるだけでまとめられる。
     */
    private List<byte[]> summed(List<byte[]> records, CodePage codePage) {
        if (copying || sortFields.isEmpty()) {
            fail("ICE000I SUM NEEDS SORT FIELDS");
            return records;
        }
        List<byte[]> out = new ArrayList<>();
        for (byte[] record : records) {
            byte[] previous = out.isEmpty() ? null : out.get(out.size() - 1);
            if (previous != null && sameKey(previous, record, codePage)) {
                if (!sumNone) {
                    add(previous, record, codePage);
                }
                continue;
            }
            out.add(record.clone());
        }
        return out;
    }

    private boolean sameKey(byte[] left, byte[] right, CodePage codePage) {
        for (SortField field : sortFields) {
            if (SortField.compareBytes(field.slice(left, codePage), field.slice(right, codePage)) != 0) {
                return false;
            }
        }
        return true;
    }

    private void add(byte[] into, byte[] record, CodePage codePage) {
        for (SortField field : sumFields) {
            Decimal total = field.number(into, codePage).add(field.number(record, codePage));
            byte[] bytes = field.encode(total);
            if (bytes == null) {
                fail("ICE000I SUM CANNOT ADD FORMAT " + field.format());
                return;
            }
            System.arraycopy(bytes, 0, into, field.offset(),
                    Math.min(bytes.length, into.length - field.offset()));
        }
    }

    /** {@code OUTREC}。レコードを組み直す。 */
    private List<byte[]> rebuilt(List<byte[]> records, List<String> items, CodePage codePage) {
        List<byte[]> out = new ArrayList<>();
        int width = 0;
        for (byte[] record : records) {
            byte[] built = build(record, items, codePage);
            if (built == null) {
                return records;
            }
            width = Math.max(width, built.length);
            out.add(built);
        }
        for (int i = 0; i < out.size(); i++) {
            out.set(i, SortField.padded(out.get(i), width, codePage.space()));
        }
        return out;
    }

    private byte[] build(byte[] record, List<String> items, CodePage codePage) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (int i = 0; i < items.size(); i++) {
            String item = items.get(i).trim();
            if (item.isEmpty()) {
                continue;
            }
            int colon = item.indexOf(':');
            if (colon > 0 && digitsOnly(item.substring(0, colon))) {
                // p: はここから先が何桁目かを言う。中身が続けて書かれていてもよい
                int column = number(item.substring(0, colon), -1);
                while (buffer.size() < column - 1) {
                    buffer.write(codePage.space());
                }
                item = item.substring(colon + 1).trim();
                if (item.isEmpty()) {
                    continue;
                }
            } else if (item.endsWith(":")) {
                fail("ICE000I OUTREC COLUMN IS NOT A NUMBER: " + item);
                return null;
            }
            char last = item.charAt(item.length() - 1);
            if ((last == 'X' || last == 'Z') && digitsOnly(item.substring(0, item.length() - 1))) {
                int count = item.length() == 1 ? 1 : number(item.substring(0, item.length() - 1), 1);
                byte filler = last == 'X' ? codePage.space() : 0;
                for (int n = 0; n < count; n++) {
                    buffer.write(filler);
                }
                continue;
            }
            if (item.length() > 2 && (item.charAt(0) == 'C' || item.charAt(0) == 'X')
                    && item.charAt(1) == '\'') {
                byte[] literal = literal(item, codePage);
                if (literal == null) {
                    fail("ICE000I OUTREC LITERAL IS NOT VALID: " + item);
                    return null;
                }
                buffer.writeBytes(literal);
                continue;
            }
            if (digitsOnly(item) && i + 1 < items.size() && digitsOnly(items.get(i + 1).trim())) {
                int offset = number(item, 1) - 1;
                int length = number(items.get(i + 1).trim(), 0);
                i++;
                SortField.Format format = null;
                if (i + 1 < items.size() && SortField.formatOf(items.get(i + 1).trim()) != null) {
                    format = SortField.formatOf(items.get(i + 1).trim());
                    i++;
                }
                List<String> decorations = new ArrayList<>();
                while (i + 1 < items.size() && SortEdit.names(items.get(i + 1))) {
                    decorations.add(items.get(i + 1).trim());
                    i++;
                }
                if (decorations.isEmpty()) {
                    // 形は書かれていてもよい。写すだけなので中身は変わらない
                    buffer.writeBytes(SortField.slice(record, offset, length, codePage.space()));
                    continue;
                }
                if (format == null) {
                    fail("ICE000I OUTREC NEEDS A FORMAT TO CHANGE A NUMBER: " + item);
                    return null;
                }
                SortField source = SortField.at(offset, length, format);
                byte[] written = changed(source.number(record, codePage), source, decorations,
                        codePage);
                if (written == null) {
                    return null;
                }
                buffer.writeBytes(written);
                continue;
            }
            fail("ICE000I OUTREC ITEM IS NOT SUPPORTED YET: " + item);
            return null;
        }
        return buffer.toByteArray();
    }

    /**
     * 読んだ数を書き直す (要件 FR-137、暫定判断 P-047 の解消)。
     *
     * <p>{@code TO=} は次のプログラムのために形を変え、{@code EDIT=} は人のために型へ
     * はめる。どちらの読み方も {@link SortEdit} が持っている。
     *
     * @return 書き直し方が読めなければ {@code null}
     */
    private byte[] changed(Decimal value, SortField source, List<String> decorations,
                           CodePage codePage) {
        SortEdit edit = SortEdit.read(decorations);
        if (edit == null || !edit.sound()) {
            fail("ICE000I NUMBER FORMAT IS NOT SUPPORTED YET: "
                    + String.join(",", decorations));
            return null;
        }
        byte[] written = edit.write(value, source, codePage);
        if (written == null) {
            fail("ICE000I NUMBER FORMAT IS NOT SUPPORTED YET: "
                    + String.join(",", decorations));
        }
        return written;
    }

    private void write(ProgramContext context, String ddName, List<byte[]> records,
                       DataSetAttributes attributes, boolean reformatted) {
        Path path = created(context, ddName);
        Records.Framed framed = Records.join(records, attributes, context.codePage(), reformatted);
        writeSound(context, path, framed.bytes());
        framed.attributes().write(path);
    }

    // ---- 制御文の読み取り ----

    /**
     * 制御文をつなぐ。
     *
     * <p>行末のコンマ、行末のハイフン、閉じていない括弧のどれかがあれば次の行へ続く。
     * 1 桁目の {@code *} は注釈である。
     */
    private static List<String> statements(List<String> lines) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (String line : lines) {
            if (line.startsWith("*")) {
                continue;
            }
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            boolean continued = text.endsWith("-") || text.endsWith(",");
            if (text.endsWith("-")) {
                text = text.substring(0, text.length() - 1).stripTrailing();
            }
            current.append(text);
            depth += depthOf(text);
            if (!continued && depth <= 0) {
                out.add(current.toString());
                current.setLength(0);
                depth = 0;
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    private static int depthOf(String text) {
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                quoted = c != '\'';
            } else if (c == '\'') {
                quoted = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return depth;
    }

    private void parse(String statement, CodePage codePage) {
        int blank = statement.indexOf(' ');
        String verb = (blank < 0 ? statement : statement.substring(0, blank))
                .toUpperCase(Locale.ROOT);
        String operands = blank < 0 ? "" : statement.substring(blank + 1).trim();
        switch (verb) {
            case "SORT", "MERGE" -> {
                stated = true;
                merging = verb.equals("MERGE");
                sortOf(operands);
            }
            case "INCLUDE" -> include = testOf(operands, codePage);
            case "OMIT" -> omit = testOf(operands, codePage);
            case "OUTREC" -> outrec = itemsOf("OUTREC", operands);
            case "INREC" -> inrec = itemsOf("INREC", operands);
            case "OUTFIL" -> outFileOf(operands, codePage);
            case "SUM" -> sumOf(operands);
            case "OPTION" -> optionOf(operands);
            case "END" -> {
                // 制御文の終わり。読み飛ばす
            }
            default -> fail("ICE000I CONTROL STATEMENT IS NOT SUPPORTED YET: " + verb);
        }
    }

    private void sortOf(String operands) {
        String fields = operand(operands, "FIELDS");
        String format = operand(operands, "FORMAT");
        if (fields == null) {
            fail("ICE000I SORT NEEDS FIELDS");
            return;
        }
        if (fields.equalsIgnoreCase("COPY")) {
            copying = true;
            return;
        }
        List<String> parts = JclOperands.split(JclOperands.unwrap(fields));
        List<SortField> out = new ArrayList<>();
        int at = 0;
        while (at < parts.size()) {
            if (at + 1 >= parts.size()) {
                fail("ICE000I SORT FIELDS IS NOT VALID: " + fields);
                return;
            }
            int position = number(parts.get(at).trim(), -1);
            int length = number(parts.get(at + 1).trim(), -1);
            at += 2;
            SortField.Format kind = format == null ? null : SortField.formatOf(format);
            if (at < parts.size() && SortField.formatOf(parts.get(at).trim()) != null) {
                kind = SortField.formatOf(parts.get(at).trim());
                at++;
            }
            boolean ascending = true;
            if (at < parts.size() && order(parts.get(at).trim()) != null) {
                ascending = order(parts.get(at).trim());
                at++;
            }
            if (position < 1 || length < 1 || kind == null) {
                fail("ICE000I SORT FIELDS IS NOT VALID: " + fields);
                return;
            }
            SortField field = new SortField(position - 1, length, kind, ascending);
            if (!supported(field)) {
                return;
            }
            out.add(field);
        }
        sortFields = List.copyOf(out);
    }

    private void sumOf(String operands) {
        String fields = operand(operands, "FIELDS");
        if (fields == null) {
            fail("ICE000I SUM NEEDS FIELDS");
            return;
        }
        if (fields.equalsIgnoreCase("NONE")) {
            sumNone = true;
            sumFields = List.of();
            return;
        }
        List<String> parts = JclOperands.split(JclOperands.unwrap(fields));
        List<SortField> out = new ArrayList<>();
        int at = 0;
        while (at < parts.size()) {
            if (at + 1 >= parts.size()) {
                fail("ICE000I SUM FIELDS IS NOT VALID: " + fields);
                return;
            }
            int position = number(parts.get(at).trim(), -1);
            int length = number(parts.get(at + 1).trim(), -1);
            at += 2;
            SortField.Format kind = at < parts.size() ? SortField.formatOf(parts.get(at).trim()) : null;
            if (kind != null) {
                at++;
            }
            if (position < 1 || length < 1 || kind == null) {
                fail("ICE000I SUM FIELDS IS NOT VALID: " + fields);
                return;
            }
            if (kind == SortField.Format.CH) {
                fail("ICE000I SUM CANNOT ADD FORMAT CH");
                return;
            }
            SortField field = SortField.at(position - 1, length, kind);
            if (!supported(field)) {
                return;
            }
            out.add(field);
        }
        sumFields = List.copyOf(out);
    }

    /** {@code FIELDS=(...)} または {@code BUILD=(...)} の項目。 */
    private List<String> itemsOf(String verb, String operands) {
        String fields = operand(operands, "FIELDS");
        if (fields == null) {
            fields = operand(operands, "BUILD");
        }
        if (fields == null) {
            fail("ICE000I " + verb + " NEEDS FIELDS OR BUILD");
            return null;
        }
        return JclOperands.split(JclOperands.unwrap(fields));
    }

    /**
     * {@code OUTFIL} 1 個 (要件 FR-137)。
     *
     * <p>書き先は {@code FNAMES=(dd,...)} か {@code FILES=(01,...)} で言う。番号で言った
     * ときの DD 名は {@code SORTOFnn} である。
     *
     * <p>知らない副オペランドは<b>誤りとして報告する</b>。読み飛ばすと、切ったつもりの
     * 出力が切られないまま出る (暫定判断 P-047)。
     */
    private void outFileOf(String operands, CodePage codePage) {
        for (String operand : JclOperands.split(operands)) {
            String key = JclOperands.key(operand).trim().toUpperCase(Locale.ROOT);
            if (!key.isEmpty() && !KNOWN_OUTFIL.contains(key)) {
                fail("ICE000I OUTFIL OPERAND IS NOT SUPPORTED YET: " + key);
                return;
            }
        }
        List<String> dds = new ArrayList<>();
        String names = operand(operands, "FNAMES");
        if (names != null) {
            for (String written : JclOperands.split(JclOperands.unwrap(names))) {
                dds.add(written.trim().toUpperCase(Locale.ROOT));
            }
        }
        String numbers = operand(operands, "FILES");
        if (numbers != null) {
            for (String written : JclOperands.split(JclOperands.unwrap(numbers))) {
                String digits = written.trim();
                dds.add("SORTOF" + (digits.length() == 1 ? "0" + digits : digits));
            }
        }
        if (dds.isEmpty()) {
            fail("ICE000I OUTFIL NEEDS FNAMES OR FILES");
            return;
        }
        String format = operand(operands, "FORMAT");
        String written = operand(operands, "INCLUDE");
        Test keep = written == null ? null : conditionOf(written, format, codePage);
        String dropped = operand(operands, "OMIT");
        Test drop = dropped == null ? null : conditionOf(dropped, format, codePage);
        String fields = operand(operands, "OUTREC");
        if (fields == null) {
            fields = operand(operands, "BUILD");
        }
        List<String> built = fields == null
                ? null
                : JclOperands.split(JclOperands.unwrap(fields));
        boolean save = hasWord(operands, "SAVE");
        if (save && (keep != null || drop != null)) {
            fail("ICE000I OUTFIL SAVE CANNOT BE COMBINED WITH INCLUDE OR OMIT");
            return;
        }
        int startRec = count(operands, "STARTREC", 1);
        int endRec = count(operands, "ENDREC", 0);
        int splitBy = hasWord(operands, "SPLIT") ? 1 : count(operands, "SPLITBY", 0);
        Report report = new Report(itemsIn(operands, "HEADER1"), itemsIn(operands, "TRAILER1"),
                itemsIn(operands, "HEADER2"), itemsIn(operands, "TRAILER2"),
                count(operands, "LINES", 0));
        if (splitBy > 0 && !report.empty()) {
            fail("ICE000I OUTFIL SPLIT CANNOT BE COMBINED WITH A HEADER OR TRAILER");
            return;
        }
        outFiles.add(new OutFile(List.copyOf(dds), keep, drop, built, save, startRec, endRec,
                splitBy, report));
    }

    /** 書ける副オペランド。ここに無いものは誤りである (暫定判断 P-047)。 */
    private static final Set<String> KNOWN_OUTFIL = Set.of(
            "FNAMES", "FILES", "INCLUDE", "OMIT", "OUTREC", "BUILD", "SAVE", "FORMAT",
            "STARTREC", "ENDREC", "SPLIT", "SPLITBY", "LINES",
            "HEADER1", "HEADER2", "TRAILER1", "TRAILER2");

    /** {@code 鍵=n} の数。書かれていなければ既定を返す。 */
    private static int count(String operands, String key, int fallback) {
        String written = operand(operands, key);
        return written == null ? fallback : number(JclOperands.unwrap(written), fallback);
    }

    /**
     * {@code HEADER1=(...)} の項目。
     *
     * @return 書かれていなければ {@code null}
     */
    private static List<String> itemsIn(String operands, String key) {
        String written = operand(operands, key);
        return written == null ? null : JclOperands.split(JclOperands.unwrap(written));
    }

    /** 値を取らないオペランドが書かれているか。{@code SAVE} と {@code SPLIT} である。 */
    private static boolean hasWord(String operands, String word) {
        for (String operand : JclOperands.split(operands)) {
            if (operand.trim().equalsIgnoreCase(word)) {
                return true;
            }
        }
        return false;
    }

    private void optionOf(String operands) {
        for (String operand : JclOperands.split(operands)) {
            String key = JclOperands.key(operand).toUpperCase(Locale.ROOT);
            if (key.equals("COPY")) {
                copying = true;
                stated = true;
            }
            // ほかの OPTION は動きを変えないものとして読み飛ばす (暫定判断 P-047)
        }
    }

    // ---- ふるい分け ----

    private Test testOf(String operands, CodePage codePage) {
        String cond = operand(operands, "COND");
        if (cond == null) {
            fail("ICE000I INCLUDE AND OMIT NEED COND");
            return null;
        }
        return conditionOf(cond, operand(operands, "FORMAT"), codePage);
    }

    /**
     * 条件そのもの。
     *
     * <p>{@code INCLUDE COND=(...)} では {@code COND} の中身、{@code OUTFIL INCLUDE=(...)}
     * では {@code INCLUDE} の中身がここへ来る。<b>書き方が違うだけで同じ条件</b>なので、
     * 読むところは 1 つにしてある。
     *
     * @param format 形を言っていなければ {@code null}
     */
    private Test conditionOf(String cond, String format, CodePage codePage) {
        if (cond.equalsIgnoreCase("ALL")) {
            return new Always(true);
        }
        if (cond.equalsIgnoreCase("NONE")) {
            return new Always(false);
        }
        List<String> parts = JclOperands.split(JclOperands.unwrap(cond));
        List<Test> conjunction = new ArrayList<>();
        List<Test> disjunction = new ArrayList<>();
        int at = 0;
        while (at < parts.size()) {
            int[] cursor = {at};
            Test test = oneTest(parts, cursor, format, codePage);
            if (test == null) {
                return null;
            }
            conjunction.add(test);
            at = cursor[0];
            if (at >= parts.size()) {
                break;
            }
            String joiner = parts.get(at).trim().toUpperCase(Locale.ROOT);
            at++;
            if (joiner.equals("AND") || joiner.equals("&")) {
                continue;
            }
            if (joiner.equals("OR") || joiner.equals("|")) {
                // OR は AND より弱い。ここまでの積を 1 つにまとめてから次へ進む
                disjunction.add(conjunction.size() == 1 ? conjunction.get(0)
                        : new All(List.copyOf(conjunction)));
                conjunction = new ArrayList<>();
                continue;
            }
            fail("ICE000I COND IS NOT VALID: " + joiner);
            return null;
        }
        Test last = conjunction.size() == 1 ? conjunction.get(0) : new All(List.copyOf(conjunction));
        if (disjunction.isEmpty()) {
            return last;
        }
        disjunction.add(last);
        return new Any(List.copyOf(disjunction));
    }

    private Test oneTest(List<String> parts, int[] cursor, String format, CodePage codePage) {
        int at = cursor[0];
        if (at + 3 >= parts.size()) {
            fail("ICE000I COND IS NOT VALID");
            return null;
        }
        int position = number(parts.get(at).trim(), -1);
        int length = number(parts.get(at + 1).trim(), -1);
        at += 2;
        SortField.Format kind = SortField.formatOf(parts.get(at).trim());
        if (kind != null) {
            at++;
        } else if (format != null) {
            kind = SortField.formatOf(format);
        }
        if (position < 1 || length < 1 || kind == null || at >= parts.size()) {
            fail("ICE000I COND IS NOT VALID");
            return null;
        }
        String relation = parts.get(at).trim().toUpperCase(Locale.ROOT);
        at++;
        if (!isRelation(relation) || at >= parts.size()) {
            fail("ICE000I COND RELATION IS NOT VALID: " + relation);
            return null;
        }
        SortField left = SortField.at(position - 1, length, kind);
        if (!supported(left)) {
            return null;
        }
        String value = parts.get(at).trim();
        at++;
        // 数が 2 つ続けば、比べる相手も場所である
        if (digitsOnly(value) && at < parts.size() && digitsOnly(parts.get(at).trim())) {
            int rightLength = number(parts.get(at).trim(), -1);
            at++;
            SortField.Format rightKind = kind;
            if (at < parts.size() && SortField.formatOf(parts.get(at).trim()) != null) {
                rightKind = SortField.formatOf(parts.get(at).trim());
                at++;
            }
            cursor[0] = at;
            SortField right = SortField.at(number(value, 1) - 1, rightLength, rightKind);
            return supported(right) ? new Between(left, relation, right) : null;
        }
        cursor[0] = at;
        return constant(left, relation, value, codePage);
    }

    private Test constant(SortField field, String relation, String value, CodePage codePage) {
        if (value.length() > 2 && (value.charAt(0) == 'C' || value.charAt(0) == 'X')
                && value.charAt(1) == '\'') {
            byte[] bytes = literal(value, codePage);
            if (bytes == null) {
                fail("ICE000I COND LITERAL IS NOT VALID: " + value);
                return null;
            }
            byte filler = value.charAt(0) == 'C' ? codePage.space() : 0;
            return new Constant(field, relation, SortField.padded(bytes, field.length(), filler), null);
        }
        Decimal number = decimal(value);
        if (number == null) {
            fail("ICE000I COND VALUE IS NOT VALID: " + value);
            return null;
        }
        if (field.format() == SortField.Format.CH) {
            byte[] bytes = codePage.encode(value);
            return new Constant(field, relation, SortField.padded(bytes, field.length(), codePage.space()),
                    null);
        }
        return new Constant(field, relation, null, number);
    }

    // ---- 形と値 ----

    /** その場所を扱えるか。扱えなければ理由を残す。 */
    private boolean supported(SortField field) {
        if (field.supported()) {
            return true;
        }
        fail("ICE000I FI FIELD LENGTH IS NOT SUPPORTED YET: " + field.length());
        return false;
    }

    private static boolean relates(int order, String relation) {
        return switch (relation) {
            case "EQ" -> order == 0;
            case "NE" -> order != 0;
            case "GT" -> order > 0;
            case "GE" -> order >= 0;
            case "LT" -> order < 0;
            case "LE" -> order <= 0;
            default -> false;
        };
    }

    private static boolean isRelation(String text) {
        return switch (text) {
            case "EQ", "NE", "GT", "GE", "LT", "LE" -> true;
            default -> false;
        };
    }

    /** 昇順なら {@code TRUE}、降順なら {@code FALSE}、順を言っていなければ {@code null}。 */
    private static Boolean order(String text) {
        return switch (text.toUpperCase(Locale.ROOT)) {
            case "A" -> Boolean.TRUE;
            case "D" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** {@code C'文字'} と {@code X'16 進'}。 */
    private static byte[] literal(String text, CodePage codePage) {
        if (!text.endsWith("'")) {
            return null;
        }
        String body = text.substring(2, text.length() - 1);
        if (text.charAt(0) == 'C') {
            return codePage.encode(body.replace("''", "'"));
        }
        if (body.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[body.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int value = Character.digit(body.charAt(2 * i), 16) * 16
                    + Character.digit(body.charAt(2 * i + 1), 16);
            if (value < 0) {
                return null;
            }
            out[i] = (byte) value;
        }
        return out;
    }

    private static Decimal decimal(String text) {
        try {
            return Decimal.parse(text.startsWith("+") ? text.substring(1) : text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean digitsOnly(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** {@code 鍵=値} の値を取り出す。書かれていなければ {@code null}。 */
    private static String operand(String operands, String key) {
        for (String operand : JclOperands.split(operands)) {
            if (JclOperands.key(operand).equalsIgnoreCase(key)) {
                return JclOperands.value(operand);
            }
        }
        return null;
    }

    private void note(String text) {
        notes.add(text);
    }

    private void fail(String text) {
        notes.add(text);
        highest = 16;
    }
}
