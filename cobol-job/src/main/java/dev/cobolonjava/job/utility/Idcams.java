package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.PartitionedDataSet;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code IDCAMS} (要件 FR-137)。
 *
 * <p>VSAM のデータセットを扱うユーティリティである。{@code SYSIN} に書いた制御文を
 * 上から順に実行する。実装したのは {@code REPRO}、{@code DELETE}、
 * {@code DEFINE CLUSTER}、{@code LISTCAT} である。
 *
 * <h2>復帰コードはいちばん大きいものが残る</h2>
 * <p>制御文ごとに復帰コードが立ち、ジョブステップの復帰コードは<b>そのうちいちばん大きい
 * もの</b>になる。1 つ失敗しても後続の制御文は動く。これがホストの決まりであり、
 * 「消してから作る」という書き方が成り立つ理由である。
 */
public final class Idcams extends UtilityProgram {

    private int highest;

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        highest = 0;
        for (String statement : statements(control(context, SYSIN))) {
            execute(context, statement);
        }
        print(context, "IDC0002I IDCAMS PROCESSING COMPLETE. MAXIMUM CONDITION CODE WAS "
                + highest);
        context.setReturnCode(highest);
    }

    /**
     * 制御文をつなぐ。
     *
     * <p>ハイフンで終わる行は次へ続く。{@code DEFINE} のように括弧が閉じていない行も続く。
     */
    private static List<String> statements(List<String> lines) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (String line : lines) {
            String text = line.strip();
            if (text.startsWith("/*")) {
                continue;
            }
            boolean continued = text.endsWith("-");
            if (continued) {
                text = text.substring(0, text.length() - 1).stripTrailing();
            }
            current.append(current.isEmpty() ? "" : " ").append(text);
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
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '(') {
                depth++;
            } else if (text.charAt(i) == ')') {
                depth--;
            }
        }
        return depth;
    }

    private void execute(ProgramContext context, String statement) {
        List<String> words = words(statement);
        if (words.isEmpty()) {
            return;
        }
        String command = words.get(0).toUpperCase(Locale.ROOT);
        switch (command) {
            case "REPRO" -> repro(context, statement);
            case "DELETE" -> delete(context, words);
            case "DEFINE" -> define(context, statement);
            case "LISTCAT" -> listcat(context, statement);
            default -> {
                print(context, "IDC3003I COMMAND NOT SUPPORTED YET: " + command);
                fail(12);
            }
        }
    }

    /** {@code REPRO INFILE(dd) OUTFILE(dd)} と {@code INDATASET/OUTDATASET}。 */
    private void repro(ProgramContext context, String statement) {
        Path from = target(context, statement, "INFILE", "INDATASET");
        Path to = target(context, statement, "OUTFILE", "OUTDATASET");
        if (from == null || to == null) {
            print(context, "IDC3202I REPRO NEEDS AN INPUT AND AN OUTPUT");
            fail(12);
            return;
        }
        if (!Files.isReadable(from)) {
            print(context, "IDC3300I INPUT DATA SET NOT FOUND");
            fail(12);
            return;
        }
        byte[] bytes = readSound(context, from);
        writeSound(context, to, bytes);
        DataSetAttributes.read(from).write(to);
        print(context, "IDC0005I NUMBER OF RECORDS PROCESSED WAS " + records(from, bytes));
    }

    /** {@code DELETE 名前}。VSAM の目録から消すのにあたるのは、ファイルを消すことである。 */
    private void delete(ProgramContext context, List<String> words) {
        if (words.size() < 2) {
            print(context, "IDC3202I DELETE NEEDS A NAME");
            fail(12);
            return;
        }
        String name = unwrap(words.get(1));
        Path path = context.catalog().resolve(name);
        if (!Files.exists(path)) {
            print(context, "IDC3012I ENTRY " + name + " NOT FOUND");
            // 消せなかったのは異常だが、後続の制御文は動く
            fail(8);
            return;
        }
        remove(path);
        remove(DataSetAttributes.sidecarOf(path));
        print(context, "IDC0550I ENTRY (A) " + name + " DELETED");
    }

    /**
     * {@code DEFINE CLUSTER (NAME(名前) ...)}。
     *
     * <p>作るのは<b>空のデータセットとその属性</b>である。{@code RECORDSIZE} と
     * {@code KEYS} と {@code INDEXED} を読み、サイドカーへ残す。
     */
    private void define(ProgramContext context, String statement) {
        String name = parameter(statement, "NAME");
        if (name == null) {
            print(context, "IDC3202I DEFINE NEEDS NAME()");
            fail(12);
            return;
        }
        Path path = context.catalog().resolve(name);
        if (Files.exists(path)) {
            print(context, "IDC3013I DUPLICATE DATA SET NAME " + name);
            fail(12);
            return;
        }
        int length = 80;
        String size = parameter(statement, "RECORDSIZE");
        if (size != null) {
            // (平均 最大) の形である。切り出し方を決めるのは最大のほうである
            List<String> parts = words(size.replace(",", " "));
            length = number(parts.get(parts.size() - 1), length);
        }
        writeBytes(path, new byte[0]);
        new DataSetAttributes(RecordFormat.FIXED, length, context.codePage()).write(path);
        print(context, "IDC0508I DATA ALLOCATION STATUS FOR VOLUME LOCAL IS 0");
        print(context, "IDC0181I STORAGECLASS USED IS LOCAL");
    }

    /** {@code LISTCAT}。目録の一覧にあたるのは、置き場にあるファイルの一覧である。 */
    private void listcat(ProgramContext context, String statement) {
        String entries = parameter(statement, "ENTRIES");
        Path directory = context.catalog().directory();
        List<String> names = new ArrayList<>();
        if (entries != null) {
            for (String entry : words(entries.replace(",", " "))) {
                names.add(unwrap(entry));
            }
        } else {
            names.addAll(listing(directory, context.codePage()));
        }
        for (String name : names) {
            Path path = context.catalog().resolve(name);
            if (Files.exists(path)) {
                print(context, "NONVSAM ------- " + name);
            } else {
                print(context, "IDC3012I ENTRY " + name + " NOT FOUND");
                fail(4);
            }
        }
    }

    /**
     * 置き場にある名前を並べる。
     *
     * <p>並べ方は<b>コードページの順</b>である (要件 FR-053)。目録の並びは名前を
     * そのまま比べた順であり、EBCDIC では英字が数字より前に来る。Java の順で並べると
     * {@code A.PAY1} と {@code A.PAYA} が逆に出る。区分データセットのディレクトリと
     * 同じ決まりなので、同じ場所から引く。
     */
    private static List<String> listing(Path directory, CodePage codePage) {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return names;
        }
        try (var stream = Files.list(directory)) {
            stream.map(path -> path.getFileName().toString())
                    .filter(name -> !name.endsWith(".meta"))
                    .forEach(names::add);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + directory, e);
        }
        names.sort(PartitionedDataSet.order(codePage));
        return names;
    }

    // ---- 制御文の読み取り ----

    /** {@code INFILE(dd)} なら DD 名、{@code INDATASET(名前)} ならデータセット名である。 */
    private static Path target(ProgramContext context, String statement, String byDd,
                               String byName) {
        String dd = parameter(statement, byDd);
        if (dd != null) {
            return opened(context, unwrap(dd));
        }
        String name = parameter(statement, byName);
        return name == null ? null : opened(context, unwrap(name));
    }

    /** {@code 鍵(値)} の値。書かれていなければ {@code null}。 */
    private static String parameter(String statement, String key) {
        String upper = statement.toUpperCase(Locale.ROOT);
        int at = 0;
        while (true) {
            at = upper.indexOf(key, at);
            if (at < 0) {
                return null;
            }
            int after = at + key.length();
            boolean standalone = at == 0 || !Character.isLetterOrDigit(upper.charAt(at - 1));
            if (standalone && after < statement.length() && statement.charAt(after) == '(') {
                return statement.substring(after + 1, closing(statement, after));
            }
            at = after;
        }
    }

    /** 対応する閉じ括弧の位置。 */
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

    private static String unwrap(String text) {
        String value = text.trim();
        while (value.startsWith("(") && value.endsWith(")")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        // 括弧の中に修飾語が続く形 (名前 CLUSTER) は、先頭だけが名前である
        int blank = value.indexOf(' ');
        return blank < 0 ? value : value.substring(0, blank);
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

    private static int records(Path from, byte[] bytes) {
        DataSetAttributes attributes = DataSetAttributes.read(from);
        if (attributes.format() == RecordFormat.FIXED && attributes.recordLength() > 0) {
            return (bytes.length + attributes.recordLength() - 1) / attributes.recordLength();
        }
        return lines(bytes, attributes.codePage()).size();
    }

    private static void remove(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + path, e);
        }
    }

    private void fail(int code) {
        highest = Math.max(highest, code);
    }
}
