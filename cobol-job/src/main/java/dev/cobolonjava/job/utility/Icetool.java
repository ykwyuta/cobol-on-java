package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.sort.SortKey;
import dev.cobolonjava.runtime.sort.SortWork;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code ICETOOL} (要件 FR-137)。
 *
 * <p>{@code TOOLIN} に書いた操作を上から順に実行する道具である。整列そのものは持たず、
 * <b>{@code SORT} を呼び出す</b>。だから鍵の扱いが 2 か所に分かれない。
 * {@code ICETOOL} が足しているのは「同じ入力を何度も通す」「重なりを見つける」
 * 「数える」という、整列の周りの仕事である。
 *
 * <h2>操作ごとに復帰コードが立ち、いちばん大きいものが残る</h2>
 * <p>{@code MODE STOP} (既定) なら、失敗した操作のところで打ち切る。
 * {@code MODE CONTINUE} なら最後まで通す。「消してから作る」のような並びが
 * 成り立つかどうかがこれで変わる。
 *
 * <h2>操作は「数を答えるもの」と「形を変えるもの」に分かれる (暫定判断 P-047 の解消)</h2>
 * <p>{@code COUNT} / {@code STATS} / {@code RANGE} / {@code UNIQUE} / {@code VERIFY} は
 * <b>データセットを作らない</b>。答えは覚え書きの行と復帰コードだけである。だから
 * {@code TO} を持たず、{@code MODE CONTINUE} と組み合わせて「入力が空なら後を飛ばす」
 * のような並びを作るために使われる。
 *
 * <p>{@code COPY} / {@code SORT} / {@code MERGE} / {@code SELECT} / {@code SPLICE} /
 * {@code SUBSET} / {@code RESIZE} は書き先を持つ。このうち {@code SPLICE} と
 * {@code RESIZE} は<b>レコードの中身と長さを変える</b>ので、書き先の様式が入力と違う。
 */
public final class Icetool extends UtilityProgram {

    /** 操作を書く入り口。 */
    private static final String TOOLIN = "TOOLIN";
    /** 覚え書きの出し先。 */
    private static final String TOOLMSG = "TOOLMSG";
    /** 呼び出した {@code SORT} の覚え書きの出し先。 */
    private static final String DFSMSG = "DFSMSG";

    private int highest;
    /** 失敗した操作のところで打ち切るか。 */
    private boolean stopping = true;

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        highest = 0;
        for (String statement : statements(control(context, TOOLIN))) {
            int before = highest;
            execute(context, statement);
            if (stopping && highest > before && highest >= 12) {
                print(context, TOOLMSG, "ICE602I OPERATION SEQUENCE STOPPED");
                break;
            }
        }
        print(context, TOOLMSG, "ICE052I 0 END OF ICETOOL. RETURN CODE IS " + highest);
        context.setReturnCode(highest);
    }

    /**
     * 操作をつなぐ。
     *
     * <p>行末のハイフンは次へ続く。1 桁目の {@code *} は注釈である。
     */
    private static List<String> statements(List<String> lines) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("*")) {
                continue;
            }
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            boolean continued = text.endsWith("-");
            if (continued) {
                text = text.substring(0, text.length() - 1).stripTrailing();
            }
            current.append(current.isEmpty() ? "" : " ").append(text);
            if (!continued) {
                out.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    private void execute(ProgramContext context, String statement) {
        List<String> words = words(statement);
        if (words.isEmpty()) {
            return;
        }
        String operator = words.get(0).toUpperCase(Locale.ROOT);
        print(context, TOOLMSG, "ICE627I " + operator);
        switch (operator) {
            case "MODE" -> mode(context, words);
            case "COPY" -> copy(context, statement);
            case "SORT" -> sort(context, statement);
            case "COUNT" -> count(context, statement);
            case "DISPLAY" -> display(context, statement);
            case "OCCUR" -> occur(context, statement);
            case "SELECT" -> select(context, statement);
            case "MERGE" -> merge(context, statement);
            case "STATS" -> stats(context, statement);
            case "RANGE" -> range(context, statement);
            case "UNIQUE" -> unique(context, statement);
            case "VERIFY" -> verify(context, statement);
            case "SPLICE" -> splice(context, statement);
            case "SUBSET" -> subset(context, statement);
            case "RESIZE" -> resize(context, statement);
            case "DEFAULTS" -> print(context, TOOLMSG, "ICE628I DEFAULTS ARE IN EFFECT");
            default -> {
                print(context, TOOLMSG, "ICE600I OPERATOR IS NOT SUPPORTED YET: " + operator);
                fail(12);
            }
        }
    }

    /** {@code MODE STOP} と {@code MODE CONTINUE}。 */
    private void mode(ProgramContext context, List<String> words) {
        String written = words.size() < 2 ? "" : words.get(1).toUpperCase(Locale.ROOT);
        switch (written) {
            case "STOP" -> stopping = true;
            case "CONTINUE" -> stopping = false;
            default -> {
                print(context, TOOLMSG, "ICE600I MODE IS NOT VALID: " + written);
                fail(12);
            }
        }
    }

    // ---- 写しと整列 ----

    /** {@code COPY FROM(dd) TO(dd,...) [USING(cccc)]}。 */
    private void copy(ProgramContext context, String statement) {
        transfer(context, statement, false);
    }

    /** {@code SORT FROM(dd) TO(dd,...) USING(cccc)}。 */
    private void sort(ProgramContext context, String statement) {
        transfer(context, statement, true);
    }

    /**
     * 入力を出力へ通す。
     *
     * <p>{@code USING} があれば {@code SORT} を呼び、その制御文で通す。無ければそのまま
     * 写す。<b>整列の規則を持たない</b>のがこの道具の要点である。
     */
    private void transfer(ProgramContext context, String statement, boolean sorting) {
        Path from = ddOf(context, statement, "FROM");
        List<Path> to = ddsOf(context, statement, "TO");
        String using = parameter(statement, "USING");
        if (from == null || to.isEmpty()) {
            print(context, TOOLMSG, "ICE601I THE OPERATION NEEDS FROM AND TO");
            fail(12);
            return;
        }
        if (sorting && using == null) {
            print(context, TOOLMSG, "ICE601I SORT NEEDS USING");
            fail(12);
            return;
        }
        if (!Files.isReadable(from)) {
            print(context, TOOLMSG, "ICE603I INPUT DATA SET NOT FOUND");
            fail(12);
            return;
        }
        if (using != null) {
            int code = delegate(context, List.of(from), to.get(0), using, false);
            fail(code);
            if (code >= 12) {
                return;
            }
        } else {
            copyBytes(context, from, to.get(0));
        }
        for (int i = 1; i < to.size(); i++) {
            copyBytes(context, to.get(0), to.get(i));
        }
        print(context, TOOLMSG, "ICE606I RECORDS PROCESSED: " + recordsIn(context, to.get(0)));
    }

    /**
     * {@code SORT} を呼ぶ (要件 FR-137)。
     *
     * <p>DD 名を付け替えた目録を渡すだけである。{@code SORT} から見れば普通に
     * {@code SORTIN} を読んで {@code SORTOUT} へ書いている。
     *
     * @param merging {@code MERGE} なら入力を {@code SORTINnn} へ結び付ける
     * @param using {@code USING(cccc)} の名前。制御文は {@code ccccCNTL} にある
     * @return 呼び先の復帰コード
     */
    private int delegate(ProgramContext context, List<Path> from, Path to, String using,
                         boolean merging) {
        DataSetCatalog catalog = new DataSetCatalog(context.catalog().directory());
        if (merging) {
            for (int i = 0; i < from.size(); i++) {
                catalog.assign(String.format("SORTIN%02d", i + 1), from.get(i));
            }
        } else {
            catalog.assign("SORTIN", from.get(0));
        }
        catalog.assign("SORTOUT", to);
        catalog.assign("SYSIN", context.catalog().resolve(using + "CNTL"));
        if (context.catalog().isAssigned(DFSMSG)) {
            catalog.assign("SYSPRINT", context.catalog().resolve(DFSMSG));
        }
        ProgramContext sub = context.withCatalog(catalog);
        new Dfsort().runFresh(sub, new DataView[0]);
        return sub.returnCode();
    }

    // ---- 数える ----

    /**
     * {@code COUNT FROM(dd)}。
     *
     * <p>{@code EMPTY} などを書けば、件数が期待どおりでないときに復帰コードが立つ。
     * 「入力が空なら後続を飛ばす」という並びがこれで書ける。
     */
    private void count(ProgramContext context, String statement) {
        Path from = ddOf(context, statement, "FROM");
        if (from == null || !Files.isReadable(from)) {
            print(context, TOOLMSG, "ICE603I INPUT DATA SET NOT FOUND");
            fail(12);
            return;
        }
        int records = recordsIn(context, from);
        print(context, TOOLMSG, "ICE628I RECORD COUNT: " + records);
        Boolean held = expectation(statement, records);
        if (held != null && !held) {
            print(context, TOOLMSG, "ICE607I THE RECORD COUNT IS NOT AS EXPECTED");
            fail(12);
        }
    }

    /**
     * 件数についての期待。
     *
     * @return 書かれていなければ {@code null}
     */
    private static Boolean expectation(String statement, int records) {
        if (hasWord(statement, "EMPTY")) {
            return records == 0;
        }
        if (hasWord(statement, "NOTEMPTY")) {
            return records > 0;
        }
        String higher = parameter(statement, "HIGHER");
        if (higher != null) {
            return records > number(higher, -1);
        }
        String lower = parameter(statement, "LOWER");
        if (lower != null) {
            return records < number(lower, -1);
        }
        String equal = parameter(statement, "EQUAL");
        if (equal != null) {
            return records == number(equal, -1);
        }
        return null;
    }

    // ---- 見せる ----

    /** {@code DISPLAY FROM(dd) LIST(dd) ON(p,l,fmt)...}。 */
    private void display(ProgramContext context, String statement) {
        Path from = ddOf(context, statement, "FROM");
        String list = name(statement, "LIST");
        List<SortField> fields = fieldsOf(context, statement);
        if (from == null || list == null || fields == null) {
            fail(12);
            return;
        }
        if (!Files.isReadable(from)) {
            print(context, TOOLMSG, "ICE603I INPUT DATA SET NOT FOUND");
            fail(12);
            return;
        }
        String title = quoted(statement, "TITLE");
        if (title != null) {
            print(context, list, title);
        }
        CodePage codePage = context.codePage();
        for (byte[] record : recordsOf(context, from)) {
            List<String> columns = new ArrayList<>();
            for (SortField field : fields) {
                columns.add(shown(record, field, codePage));
            }
            print(context, list, String.join("  ", columns));
        }
    }

    /**
     * {@code OCCUR FROM(dd) LIST(dd) ON(p,l,fmt)}。
     *
     * <p>値ごとの件数を並べる。どの値が何件あるかを知りたいだけのときに、整列と
     * 数え上げをジョブへ書かずに済む。
     */
    private void occur(ProgramContext context, String statement) {
        Path from = ddOf(context, statement, "FROM");
        String list = name(statement, "LIST");
        List<SortField> fields = fieldsOf(context, statement);
        if (from == null || list == null || fields == null || fields.isEmpty()) {
            fail(12);
            return;
        }
        if (!Files.isReadable(from)) {
            print(context, TOOLMSG, "ICE603I INPUT DATA SET NOT FOUND");
            fail(12);
            return;
        }
        CodePage codePage = context.codePage();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (byte[] record : ordered(recordsOf(context, from), fields, codePage)) {
            List<String> columns = new ArrayList<>();
            for (SortField field : fields) {
                columns.add(shown(record, field, codePage));
            }
            counts.merge(String.join("  ", columns), 1, Integer::sum);
        }
        String title = quoted(statement, "TITLE");
        if (title != null) {
            print(context, list, title);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            print(context, list, entry.getKey() + "  " + entry.getValue());
        }
    }

    // ---- 選ぶ ----

    /**
     * {@code SELECT FROM(dd) TO(dd) ON(p,l,fmt) 選び方}。
     *
     * <p>重なりを見つけるのがこの操作である。{@code ALLDUPS} は重なっているものすべて、
     * {@code NODUPS} は 1 件しかないもの、{@code FIRST} と {@code LAST} は各組の端である。
     */
    private void select(ProgramContext context, String statement) {
        Path from = ddOf(context, statement, "FROM");
        List<Path> to = ddsOf(context, statement, "TO");
        List<SortField> fields = fieldsOf(context, statement);
        if (from == null || to.isEmpty() || fields == null || fields.isEmpty()) {
            print(context, TOOLMSG, "ICE601I SELECT NEEDS FROM, TO AND ON");
            fail(12);
            return;
        }
        if (!Files.isReadable(from)) {
            print(context, TOOLMSG, "ICE603I INPUT DATA SET NOT FOUND");
            fail(12);
            return;
        }
        DataSetAttributes attributes = DataSetAttributes.read(from);
        CodePage codePage = context.codePage();
        List<byte[]> records = ordered(recordsOf(context, from), fields, codePage);
        List<byte[]> chosen = choose(context, records, fields, statement, codePage);
        if (chosen == null) {
            fail(12);
            return;
        }
        Records.Framed framed = Records.join(chosen, attributes, codePage, false);
        for (Path path : to) {
            writeSound(context, path, framed.bytes());
            framed.attributes().write(path);
        }
        print(context, TOOLMSG, "ICE606I RECORDS SELECTED: " + chosen.size());
    }

    /**
     * 選び方を当てる。
     *
     * @return 選び方が読めなければ {@code null}
     */
    private List<byte[]> choose(ProgramContext context, List<byte[]> records,
                                List<SortField> fields, String statement, CodePage codePage) {
        List<List<byte[]>> groups = groups(records, fields, codePage);
        List<byte[]> out = new ArrayList<>();
        if (hasWord(statement, "FIRST")) {
            groups.forEach(group -> out.add(group.get(0)));
            return out;
        }
        if (hasWord(statement, "LAST")) {
            groups.forEach(group -> out.add(group.get(group.size() - 1)));
            return out;
        }
        if (hasWord(statement, "ALLDUPS")) {
            groups.stream().filter(group -> group.size() > 1).forEach(out::addAll);
            return out;
        }
        if (hasWord(statement, "NODUPS")) {
            groups.stream().filter(group -> group.size() == 1).forEach(out::addAll);
            return out;
        }
        String higher = parameter(statement, "HIGHER");
        String lower = parameter(statement, "LOWER");
        String equal = parameter(statement, "EQUAL");
        if (higher != null || lower != null || equal != null) {
            int bound = number(higher != null ? higher : lower != null ? lower : equal, -1);
            for (List<byte[]> group : groups) {
                boolean keep = higher != null ? group.size() > bound
                        : lower != null ? group.size() < bound
                        : group.size() == bound;
                if (keep) {
                    out.addAll(group);
                }
            }
            return out;
        }
        print(context, TOOLMSG, "ICE601I SELECT NEEDS A WAY TO CHOOSE");
        return null;
    }

    /** 鍵が等しいものをまとめる。並べ替えたあとなので隣り合っている。 */
    private static List<List<byte[]>> groups(List<byte[]> records, List<SortField> fields,
                                             CodePage codePage) {
        List<List<byte[]>> out = new ArrayList<>();
        for (byte[] record : records) {
            if (!out.isEmpty() && sameKey(out.get(out.size() - 1).get(0), record, fields,
                    codePage)) {
                out.get(out.size() - 1).add(record);
                continue;
            }
            List<byte[]> group = new ArrayList<>();
            group.add(record);
            out.add(group);
        }
        return out;
    }

    private static boolean sameKey(byte[] left, byte[] right, List<SortField> fields,
                                   CodePage codePage) {
        for (SortField field : fields) {
            if (SortField.compareBytes(field.slice(left, codePage),
                    field.slice(right, codePage)) != 0) {
                return false;
            }
        }
        return true;
    }

    /** 並べ替えは {@link SortWork} に任せる。COBOL の {@code SORT} と同じ道である。 */
    private static List<byte[]> ordered(List<byte[]> records, List<SortField> fields,
                                        CodePage codePage) {
        List<SortKey> keys = new ArrayList<>();
        for (SortField field : fields) {
            keys.add(field.key());
        }
        SortWork work = new SortWork(keys, codePage);
        records.forEach(work::release);
        work.sort();
        return new ArrayList<>(work.all());
    }

    // ---- 併合 ----

    /**
     * {@code MERGE FROM(dd,dd,...) TO(dd) USING(cccc)} (要件 FR-137)。
     *
     * <p>{@code SORT} と同じく {@code SORT} ユーティリティへ渡す。違うのは入力が
     * 2 つ以上あることだけで、{@code SORTIN01} から順に結び付ける。<b>すでに整列済みの
     * ものを突き合わせる</b>ので、入力が並んでいなければ答えは狂う。それを見張るのは
     * ホストでも呼び先の仕事である。
     */
    private void merge(ProgramContext context, String statement) {
        List<Path> from = readsOf(context, statement, "FROM");
        List<Path> to = ddsOf(context, statement, "TO");
        String using = parameter(statement, "USING");
        if (from.isEmpty() || to.isEmpty() || using == null) {
            print(context, TOOLMSG, "ICE601I MERGE NEEDS FROM, TO AND USING");
            fail(12);
            return;
        }
        for (Path path : from) {
            if (!Files.isReadable(path)) {
                print(context, TOOLMSG, "ICE603I INPUT DATA SET NOT FOUND");
                fail(12);
                return;
            }
        }
        int code = delegate(context, from, to.get(0), using, true);
        fail(code);
        if (code >= 12) {
            return;
        }
        for (int i = 1; i < to.size(); i++) {
            copyBytes(context, to.get(0), to.get(i));
        }
        print(context, TOOLMSG, "ICE606I RECORDS PROCESSED: " + recordsIn(context, to.get(0)));
    }

    // ---- 数を答える ----

    /**
     * {@code STATS FROM(dd) ON(p,l,fmt)...} (要件 FR-137)。
     *
     * <p>場所ごとに最小・最大・平均・合計を出す。<b>データセットは作らない</b>。
     * 「金額欄の合計が締めの数と合うか」を、整列を書かずに 1 行で確かめるのに使う。
     *
     * <p>平均は小数を切り捨てる。ホストの {@code ICETOOL} も整数で出す。
     */
    private void stats(ProgramContext context, String statement) {
        Path from = ddOf(context, statement, "FROM");
        List<SortField> fields = fieldsOf(context, statement);
        List<byte[]> records = input(context, from, fields);
        if (records == null) {
            return;
        }
        CodePage codePage = context.codePage();
        for (SortField field : fields) {
            if (records.isEmpty()) {
                print(context, TOOLMSG, "ICE609I 0 THERE ARE NO RECORDS TO REPORT ON");
                continue;
            }
            BigDecimal least = null;
            BigDecimal most = null;
            BigDecimal total = BigDecimal.ZERO;
            for (byte[] record : records) {
                BigDecimal value = field.number(record, codePage).toBigDecimal();
                least = least == null || value.compareTo(least) < 0 ? value : least;
                most = most == null || value.compareTo(most) > 0 ? value : most;
                total = total.add(value);
            }
            BigDecimal average = total.divide(BigDecimal.valueOf(records.size()), 0,
                    RoundingMode.DOWN);
            print(context, TOOLMSG, "ICE607I 0 MINIMUM: " + least.toPlainString()
                    + ", MAXIMUM: " + most.toPlainString());
            print(context, TOOLMSG, "ICE608I 0 AVERAGE: " + average.toPlainString()
                    + ", TOTAL: " + total.toPlainString());
        }
    }

    /**
     * {@code RANGE FROM(dd) ON(p,l,fmt) HIGHER(n)/LOWER(n)/EQUAL(n)/NOTEQUAL(n)}。
     *
     * <p>範囲に入っている値の数を出す。{@code COUNT} との違いは<b>復帰コードを立てない</b>
     * ことである。{@code COUNT} は「件数がこうでなければ止める」ための道具、
     * {@code RANGE} は「いくつあるか報せる」ための道具である。
     */
    private void range(ProgramContext context, String statement) {
        Path from = ddOf(context, statement, "FROM");
        List<SortField> fields = fieldsOf(context, statement);
        List<byte[]> records = input(context, from, fields);
        if (records == null) {
            return;
        }
        BigDecimal higher = bound(statement, "HIGHER");
        BigDecimal lower = bound(statement, "LOWER");
        BigDecimal equal = bound(statement, "EQUAL");
        BigDecimal other = bound(statement, "NOTEQUAL");
        if (higher == null && lower == null && equal == null && other == null) {
            print(context, TOOLMSG, "ICE601I RANGE NEEDS HIGHER, LOWER, EQUAL OR NOTEQUAL");
            fail(12);
            return;
        }
        CodePage codePage = context.codePage();
        int inside = 0;
        for (byte[] record : records) {
            boolean keep = true;
            for (SortField field : fields) {
                BigDecimal value = field.number(record, codePage).toBigDecimal();
                keep = keep
                        && (higher == null || value.compareTo(higher) > 0)
                        && (lower == null || value.compareTo(lower) < 0)
                        && (equal == null || value.compareTo(equal) == 0)
                        && (other == null || value.compareTo(other) != 0);
            }
            if (keep) {
                inside++;
            }
        }
        print(context, TOOLMSG, "ICE610I 0 NUMBER OF VALUES IN RANGE: " + inside);
    }

    /**
     * {@code UNIQUE FROM(dd) ON(p,l,fmt)...}。
     *
     * <p>違う値がいくつあるかを出す。{@code SELECT ... NODUPS} が「重なっていないレコード」
     * を取り出すのに対し、こちらは<b>数だけ</b>を答える。組を作らないので並べ替えも要らない。
     */
    private void unique(ProgramContext context, String statement) {
        Path from = ddOf(context, statement, "FROM");
        List<SortField> fields = fieldsOf(context, statement);
        List<byte[]> records = input(context, from, fields);
        if (records == null) {
            return;
        }
        CodePage codePage = context.codePage();
        Set<String> seen = new LinkedHashSet<>();
        for (byte[] record : records) {
            StringBuilder value = new StringBuilder();
            for (SortField field : fields) {
                value.append(codePage.decode(field.slice(record, codePage)));
            }
            seen.add(value.toString());
        }
        print(context, TOOLMSG, "ICE609I 0 NUMBER OF UNIQUE VALUES: " + seen.size());
    }

    /**
     * {@code VERIFY FROM(dd) ON(p,l,fmt)...} (要件 FR-137, FR-141)。
     *
     * <p>10 進数の欄が<b>数として読めるか</b>を確かめる。読めない欄をそのまま計算へ渡すと、
     * ホストでは {@code S0C7} で落ちる。落ちてから探すより、流す前に見つけるほうが早い。
     * これが実資産で {@code VERIFY} が書かれる理由である。
     *
     * <p>だから、ふるい分けが読めないバイトを 0 とみなすのと違い、ここでは
     * {@link SortField#valid} を使って握り潰さない。
     */
    private void verify(ProgramContext context, String statement) {
        Path from = ddOf(context, statement, "FROM");
        List<SortField> fields = fieldsOf(context, statement);
        List<byte[]> records = input(context, from, fields);
        if (records == null) {
            return;
        }
        for (SortField field : fields) {
            if (field.format() == SortField.Format.CH || field.format() == SortField.Format.BI) {
                print(context, TOOLMSG,
                        "ICE601I VERIFY NEEDS A DECIMAL FORMAT: " + field.format());
                fail(12);
                return;
            }
        }
        CodePage codePage = context.codePage();
        int broken = 0;
        for (int i = 0; i < records.size(); i++) {
            for (SortField field : fields) {
                if (field.valid(records.get(i), codePage)) {
                    continue;
                }
                print(context, TOOLMSG, "ICE612I 0 RECORD " + (i + 1)
                        + " HAS AN INVALID DECIMAL VALUE AT " + (field.offset() + 1));
                broken++;
            }
        }
        print(context, TOOLMSG, "ICE611I 0 INVALID DECIMAL VALUES: " + broken);
        if (broken > 0) {
            fail(12);
        }
    }

    // ---- 形を変える ----

    /**
     * {@code SPLICE FROM(dd) TO(dd) ON(p,l,fmt)... WITH(p,l)... [KEEPNODUPS]} (要件 FR-137)。
     *
     * <p>鍵が同じレコードを<b>1 本につなぐ</b>。片方に無い欄をもう片方から持ってくる仕掛け
     * であり、実資産では 2 つのファイルを突き合わせるのに使われる。
     *
     * <p>組の 1 本目を土台にし、後から来たものの {@code WITH} の場所だけを重ね書きする。
     * 鍵が同じなら<b>後の値が勝つ</b>ということであり、更新レコードを当てる形になる。
     *
     * <p>既定では組になったものだけを出す。1 本しかないものも出したければ
     * {@code KEEPNODUPS} と書く。「両方にあるものだけ」と「片方にしかないものも」を
     * この 1 語で言い分ける。
     */
    private void splice(ProgramContext context, String statement) {
        Path from = ddOf(context, statement, "FROM");
        List<Path> to = ddsOf(context, statement, "TO");
        List<SortField> fields = fieldsOf(context, statement);
        List<List<String>> with = allOf(statement, "WITH");
        if (to.isEmpty() || with.isEmpty()) {
            print(context, TOOLMSG, "ICE601I SPLICE NEEDS FROM, TO, ON AND WITH");
            fail(12);
            return;
        }
        if (hasWord(statement, "KEEPBASE") || parameter(statement, "USING") != null) {
            print(context, TOOLMSG, "ICE600I SPLICE KEEPBASE AND USING ARE NOT SUPPORTED YET");
            fail(12);
            return;
        }
        List<int[]> places = places(context, with);
        List<byte[]> records = input(context, from, fields);
        if (places == null || records == null) {
            return;
        }
        CodePage codePage = context.codePage();
        boolean nodups = hasWord(statement, "KEEPNODUPS");
        List<byte[]> out = new ArrayList<>();
        for (List<byte[]> group : groups(ordered(records, fields, codePage), fields, codePage)) {
            if (group.size() == 1 && !nodups) {
                continue;
            }
            byte[] spliced = group.get(0).clone();
            for (int i = 1; i < group.size(); i++) {
                for (int[] place : places) {
                    overlay(spliced, group.get(i), place[0], place[1], codePage);
                }
            }
            out.add(spliced);
        }
        put(context, to, out, DataSetAttributes.read(from), false);
        print(context, TOOLMSG, "ICE606I RECORDS SPLICED: " + out.size());
    }

    /**
     * {@code WITH(p,l)} の並び。
     *
     * @return 読めなければ {@code null}
     */
    private List<int[]> places(ProgramContext context, List<List<String>> with) {
        List<int[]> out = new ArrayList<>();
        for (List<String> written : with) {
            int position = written.size() < 2 ? -1 : number(written.get(0), -1);
            int length = written.size() < 2 ? -1 : number(written.get(1), -1);
            if (position < 1 || length < 1) {
                print(context, TOOLMSG, "ICE601I WITH NEEDS A POSITION AND LENGTH: " + written);
                fail(12);
                return null;
            }
            out.add(new int[] {position - 1, length});
        }
        return out;
    }

    /** 場所 1 つを重ね書きする。土台からはみ出す分は捨てる。 */
    private static void overlay(byte[] into, byte[] from, int offset, int length,
                                CodePage codePage) {
        byte[] taken = SortField.slice(from, offset, length, codePage.space());
        int room = Math.min(taken.length, into.length - offset);
        if (offset >= 0 && room > 0) {
            System.arraycopy(taken, 0, into, offset, room);
        }
    }

    /**
     * {@code SUBSET FROM(dd) TO(dd) [DISCARD(dd)] [KEEP|REMOVE] FIRST(n)/LAST(n)/RRN(n,...)}。
     *
     * <p><b>中身ではなく順番で</b>選ぶ操作である。見出し行と末尾行を落とす、先頭の数件だけ
     * 見る、といった使い方をする。{@code INCLUDE} では書けない。
     *
     * <p>{@code DISCARD} を書けば、選ばれなかったほうもデータセットとして残る。
     * 落とした見出し行を別に取っておく形がこれである。
     */
    private void subset(ProgramContext context, String statement) {
        Path from = ddOf(context, statement, "FROM");
        List<Path> to = ddsOf(context, statement, "TO");
        List<Path> discard = ddsOf(context, statement, "DISCARD");
        if (to.isEmpty()) {
            print(context, TOOLMSG, "ICE601I SUBSET NEEDS FROM AND TO");
            fail(12);
            return;
        }
        if (parameter(statement, "USING") != null) {
            print(context, TOOLMSG, "ICE600I SUBSET USING IS NOT SUPPORTED YET");
            fail(12);
            return;
        }
        List<byte[]> records = input(context, from, List.of());
        if (records == null) {
            return;
        }
        boolean[] named = named(context, statement, records.size());
        if (named == null) {
            return;
        }
        boolean keeping = !hasWord(statement, "REMOVE");
        List<byte[]> kept = new ArrayList<>();
        List<byte[]> dropped = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            if (named[i] == keeping) {
                kept.add(records.get(i));
            } else {
                dropped.add(records.get(i));
            }
        }
        DataSetAttributes attributes = DataSetAttributes.read(from);
        put(context, to, kept, attributes, false);
        if (!discard.isEmpty()) {
            put(context, discard, dropped, attributes, false);
        }
        print(context, TOOLMSG, "ICE606I RECORDS SELECTED: " + kept.size());
    }

    /**
     * どのレコードが名指されているか。
     *
     * @return 選び方が読めなければ {@code null}
     */
    private boolean[] named(ProgramContext context, String statement, int count) {
        boolean[] out = new boolean[count];
        boolean said = false;
        if (mentions(statement, "FIRST")) {
            int many = Math.min(count, extent(statement, "FIRST"));
            for (int i = 0; i < many; i++) {
                out[i] = true;
            }
            said = true;
        }
        if (mentions(statement, "LAST")) {
            int many = Math.min(count, extent(statement, "LAST"));
            for (int i = count - many; i < count; i++) {
                out[i] = true;
            }
            said = true;
        }
        String numbers = parameter(statement, "RRN");
        if (numbers != null) {
            for (String written : words(numbers.replace(",", " "))) {
                int rrn = number(written, -1);
                if (rrn < 1) {
                    print(context, TOOLMSG, "ICE601I RRN IS NOT A NUMBER: " + written);
                    fail(12);
                    return null;
                }
                if (rrn <= count) {
                    out[rrn - 1] = true;
                }
            }
            said = true;
        }
        if (!said) {
            print(context, TOOLMSG, "ICE601I SUBSET NEEDS FIRST, LAST OR RRN");
            fail(12);
            return null;
        }
        return out;
    }

    /** {@code FIRST} とも {@code FIRST(n)} とも書ける。 */
    private static boolean mentions(String statement, String key) {
        return hasWord(statement, key) || indexOfKey(statement, key, 0) >= 0;
    }

    /** {@code FIRST} は 1 件、{@code FIRST(n)} は n 件である。 */
    private static int extent(String statement, String key) {
        String written = parameter(statement, key);
        return written == null ? 1 : Math.max(number(written, 1), 0);
    }

    /**
     * {@code RESIZE FROM(dd) TO(dd) TOLEN(n)} (要件 FR-137)。
     *
     * <p>レコードの長さを変える。中身は<b>一続きのバイト列として</b>数え直すので、
     * 長くすれば何本かが 1 本になり、短くすれば 1 本が何本かになる。機械の間で資産を
     * 運ぶときに、片方の様式へ合わせるために使う。
     *
     * <p>可変長では長さが 1 つに決まらないので断る。ホストの {@code RESIZE} も固定長を
     * 前提にしている。
     */
    private void resize(ProgramContext context, String statement) {
        Path from = ddOf(context, statement, "FROM");
        List<Path> to = ddsOf(context, statement, "TO");
        String written = parameter(statement, "TOLEN");
        int length = written == null ? -1 : number(written, -1);
        if (to.isEmpty() || length < 1) {
            print(context, TOOLMSG, "ICE601I RESIZE NEEDS FROM, TO AND TOLEN");
            fail(12);
            return;
        }
        List<byte[]> records = input(context, from, List.of());
        if (records == null) {
            return;
        }
        DataSetAttributes attributes = DataSetAttributes.read(from);
        if (attributes.format() != RecordFormat.FIXED) {
            print(context, TOOLMSG, "ICE600I RESIZE NEEDS A FIXED LENGTH DATA SET");
            fail(12);
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (byte[] record : records) {
            buffer.writeBytes(record);
        }
        byte[] bytes = buffer.toByteArray();
        List<byte[]> out = new ArrayList<>();
        for (int at = 0; at < bytes.length; at += length) {
            byte[] piece = Arrays.copyOfRange(bytes, at, Math.min(at + length, bytes.length));
            out.add(SortField.padded(piece, length, context.codePage().space()));
        }
        put(context, to, out,
                new DataSetAttributes(RecordFormat.FIXED, length, context.codePage()), false);
        print(context, TOOLMSG, "ICE606I RECORDS RESIZED: " + out.size());
    }

    // ---- 入出力の下ごしらえ ----

    /**
     * 入力を読む。読めなければ報せて {@code null} を返す。
     *
     * @param fields {@code null} なら {@code ON} が読めなかったということ。空なら要らない
     */
    private List<byte[]> input(ProgramContext context, Path from, List<SortField> fields) {
        if (fields == null) {
            fail(12);
            return null;
        }
        if (from == null) {
            print(context, TOOLMSG, "ICE601I THE OPERATION NEEDS FROM");
            fail(12);
            return null;
        }
        if (!Files.isReadable(from)) {
            print(context, TOOLMSG, "ICE603I INPUT DATA SET NOT FOUND");
            fail(12);
            return null;
        }
        return recordsOf(context, from);
    }

    /** 書き先へ同じものを配る。 */
    private static void put(ProgramContext context, List<Path> to, List<byte[]> records,
                            DataSetAttributes attributes, boolean reformatted) {
        Records.Framed framed = Records.join(records, attributes, context.codePage(), reformatted);
        for (Path path : to) {
            writeSound(context, path, framed.bytes());
            framed.attributes().write(path);
        }
    }

    // ---- 制御文の読み取り ----

    /**
     * {@code ON(p,l,fmt)} の並び。
     *
     * @return 読めなければ {@code null}
     */
    private List<SortField> fieldsOf(ProgramContext context, String statement) {
        List<SortField> out = new ArrayList<>();
        for (List<String> parts : allOf(statement, "ON")) {
            if (parts.size() < 3) {
                print(context, TOOLMSG, "ICE601I ON NEEDS A POSITION, LENGTH AND FORMAT");
                return null;
            }
            int position = number(parts.get(0), -1);
            int length = number(parts.get(1), -1);
            SortField.Format format = SortField.formatOf(parts.get(2));
            if (position < 1 || length < 1 || format == null) {
                print(context, TOOLMSG, "ICE601I ON IS NOT VALID: " + parts);
                return null;
            }
            SortField field = SortField.at(position - 1, length, format);
            if (!field.supported()) {
                print(context, TOOLMSG,
                        "ICE600I FI FIELD LENGTH IS NOT SUPPORTED YET: " + length);
                return null;
            }
            out.add(field);
        }
        if (out.isEmpty()) {
            print(context, TOOLMSG, "ICE601I THE OPERATION NEEDS ON");
            return null;
        }
        return out;
    }

    /**
     * 同じ鍵が何度も書かれるもの ({@code ON} と {@code WITH}) の中身を並べる。
     *
     * <p>コンマも空白も区切りとして扱う。{@code ON(1,5,CH)} と {@code ON(1 5 CH)} は
     * ホストでも同じである。
     */
    private static List<List<String>> allOf(String statement, String key) {
        List<List<String>> out = new ArrayList<>();
        int at = 0;
        while (true) {
            int found = indexOfKey(statement, key, at);
            if (found < 0) {
                return out;
            }
            int open = statement.indexOf('(', found);
            int close = closing(statement, open);
            at = close + 1;
            out.add(words(statement.substring(open + 1, close).replace(",", " ")));
        }
    }

    /**
     * {@code HIGHER(n)} のような境の値。
     *
     * @return 書かれていなければ {@code null}
     */
    private static BigDecimal bound(String statement, String key) {
        String written = parameter(statement, key);
        if (written == null) {
            return null;
        }
        try {
            return new BigDecimal(written.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 場所の値を、見せるための文字にする。 */
    private static String shown(byte[] record, SortField field, CodePage codePage) {
        if (field.format() == SortField.Format.CH) {
            return codePage.decode(field.slice(record, codePage));
        }
        return field.number(record, codePage).toBigDecimal().toPlainString();
    }

    private static Path ddOf(ProgramContext context, String statement, String key) {
        String written = name(statement, key);
        return written == null ? null : opened(context, written);
    }

    /** {@code TO(dd,dd)} は<b>書き先</b>である。無くてよいし、ディレクトリの空きを見る。 */
    private static List<Path> ddsOf(ProgramContext context, String statement, String key) {
        List<Path> out = new ArrayList<>();
        String written = parameter(statement, key);
        if (written == null) {
            return out;
        }
        for (String each : words(written.replace(",", " "))) {
            out.add(created(context, each.toUpperCase(Locale.ROOT)));
        }
        return out;
    }

    /**
     * {@code FROM(dd,dd,...)} の読み先。{@code MERGE} だけが 2 つ以上を取る。
     */
    private static List<Path> readsOf(ProgramContext context, String statement, String key) {
        List<Path> out = new ArrayList<>();
        String written = parameter(statement, key);
        if (written == null) {
            return out;
        }
        for (String each : words(written.replace(",", " "))) {
            out.add(opened(context, each.toUpperCase(Locale.ROOT)));
        }
        return out;
    }

    private static String name(String statement, String key) {
        String written = parameter(statement, key);
        return written == null ? null : written.trim().toUpperCase(Locale.ROOT);
    }

    /** {@code TITLE('...')} の中身。 */
    private static String quoted(String statement, String key) {
        String written = parameter(statement, key);
        if (written == null) {
            return null;
        }
        String text = written.trim();
        if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
            return text.substring(1, text.length() - 1).replace("''", "'");
        }
        return text;
    }

    /** {@code 鍵(値)} の値。書かれていなければ {@code null}。 */
    private static String parameter(String statement, String key) {
        int at = indexOfKey(statement, key, 0);
        if (at < 0) {
            return null;
        }
        int open = statement.indexOf('(', at);
        return statement.substring(open + 1, closing(statement, open));
    }

    /** 括弧が続く鍵の位置。前後が英数字でないものだけを当てる。 */
    private static int indexOfKey(String statement, String key, int from) {
        String upper = statement.toUpperCase(Locale.ROOT);
        int at = from;
        while (true) {
            at = upper.indexOf(key, at);
            if (at < 0) {
                return -1;
            }
            int after = at + key.length();
            boolean standalone = at == 0 || !Character.isLetterOrDigit(upper.charAt(at - 1));
            if (standalone && after < statement.length() && statement.charAt(after) == '(') {
                return at;
            }
            at = after;
        }
    }

    /** 値を取らない語が書かれているか。{@code ALLDUPS} などがこれである。 */
    private static boolean hasWord(String statement, String word) {
        for (String each : words(statement)) {
            if (each.equalsIgnoreCase(word)) {
                return true;
            }
        }
        return false;
    }

    private static int closing(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            if (text.charAt(i) == '(') {
                depth++;
            } else if (text.charAt(i) == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return text.length();
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        for (String word : text.split("[\\s]+")) {
            if (!word.isEmpty()) {
                out.add(word);
            }
        }
        return out;
    }

    private static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- データセット ----

    private static List<byte[]> recordsOf(ProgramContext context, Path path) {
        return Records.split(readSound(context, path), DataSetAttributes.read(path));
    }

    private static int recordsIn(ProgramContext context, Path path) {
        return Files.isReadable(path) ? recordsOf(context, path).size() : 0;
    }

    private static void copyBytes(ProgramContext context, Path from, Path to) {
        writeSound(context, to, readSound(context, from));
        DataSetAttributes.read(from).write(to);
    }

    private void fail(int code) {
        highest = Math.max(highest, code);
    }
}
