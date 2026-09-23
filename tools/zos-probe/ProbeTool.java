import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 実機 (z/OS) で採った観測と、この処理系で採った観測を突き合わせる道具。
 *
 * <p>ビルドに入れず、単独のファイルとして {@code java ProbeTool.java ...} で動かす。
 * 観測の道具が処理系のモジュールに依ると、処理系の不具合が道具の不具合に化けるからである
 * (CLAUDE.md §6)。JDK の標準ライブラリだけを使う。
 *
 * <pre>
 * java tools/zos-probe/ProbeTool.java prb   実機の置き場 ローカルの置き場
 * java tools/zos-probe/ProbeTool.java print データセット --rdw|--fixed 長さ [--cp IBM1047]
 * java tools/zos-probe/ProbeTool.java cp    CPNAT のファイル
 * </pre>
 *
 * <p>どの形でも終了コードは 0 である。一致の数を門にしない (CLAUDE.md §7)。
 * 数えて並べ、読むのは人である。
 */
public final class ProbeTool {

    /** {@code PRB 事例 16進 |文字|}。行頭に ASA の制御文字や空白が付いていてもよい。 */
    private static final Pattern PRB =
            Pattern.compile("PRB\\s+(\\S+)\\s+([0-9A-F]*)\\s*\\|(.*)\\|\\s*$");

    private ProbeTool() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "prb" -> {
                if (args.length != 3) {
                    usage();
                    return;
                }
                comparePrb(Path.of(args[1]), Path.of(args[2]));
            }
            case "print" -> print(args);
            case "cp" -> {
                if (args.length != 2) {
                    usage();
                    return;
                }
                codePages(Path.of(args[1]));
            }
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println("usage: java ProbeTool.java prb <zos-dir|file> <local-dir|file>");
        System.err.println("       java ProbeTool.java print <dataset> --rdw|--fixed <lrecl>"
                + " [--cp IBM1047]");
        System.err.println("       java ProbeTool.java cp <cpnat-file>");
    }

    // ------------------------------------------------------------------ prb

    /** 1 つの観測。どのステップの出力に現れたかを持つ。 */
    private record Observation(String step, String id, String hex, String text) {

        String key() {
            return step + " " + id;
        }
    }

    /**
     * 事例を「ステップ名 + 事例名」で突き合わせる。同じ原文を翻訳の変種ごとに別の
     * ステップで流すので、事例名だけでは NUMPROC(NOPFD) の結果と PFD の結果が混ざる。
     */
    private static void comparePrb(Path zos, Path local) throws IOException {
        Map<String, List<Observation>> host = collect(zos);
        Map<String, List<Observation>> mine = collect(local);
        int match = 0;
        int diff = 0;
        int hostOnly = 0;
        int localOnly = 0;
        List<String> keys = new ArrayList<>(host.keySet());
        for (String key : mine.keySet()) {
            if (!host.containsKey(key)) {
                keys.add(key);
            }
        }
        StringBuilder report = new StringBuilder();
        for (String key : keys) {
            List<Observation> h = host.getOrDefault(key, List.of());
            List<Observation> l = mine.getOrDefault(key, List.of());
            int n = Math.max(h.size(), l.size());
            for (int i = 0; i < n; i++) {
                Observation a = i < h.size() ? h.get(i) : null;
                Observation b = i < l.size() ? l.get(i) : null;
                String verdict;
                if (a == null) {
                    verdict = "LOCAL-ONLY";
                    localOnly++;
                } else if (b == null) {
                    verdict = "ZOS-ONLY";
                    hostOnly++;
                } else if (same(a, b)) {
                    verdict = "MATCH";
                    match++;
                } else {
                    verdict = "DIFF";
                    diff++;
                }
                if (!verdict.equals("MATCH")) {
                    report.append(String.format("%-10s %s%n", verdict, key));
                    if (a != null) {
                        report.append("    z/OS : ").append(a.hex()).append(" |")
                                .append(a.text()).append("|\n");
                    }
                    if (b != null) {
                        report.append("    local: ").append(b.hex()).append(" |")
                                .append(b.text()).append("|\n");
                    }
                }
            }
        }
        System.out.print(report);
        System.out.printf("MATCH %d  DIFF %d  ZOS-ONLY %d  LOCAL-ONLY %d%n",
                match, diff, hostOnly, localOnly);
    }

    /**
     * 16 進が両方にあれば 16 進で比べる。どちらも 16 進を持たない行 (状態や件数を
     * 文字で出す行) だけ文字で比べる。文字は SYSOUT を取り出すときのコードページ変換を
     * 通っているので、16 進があるならそちらを信じる。
     */
    private static boolean same(Observation a, Observation b) {
        if (!a.hex().isEmpty() || !b.hex().isEmpty()) {
            return a.hex().equals(b.hex());
        }
        return a.text().strip().equals(b.text().strip());
    }

    /**
     * 置き場の下のファイルをすべて読み、PRB 行を集める。ステップ名は、ファイルを直に
     * 含むディレクトリの名前とする ({@code zowe zos-jobs download output} の形と、
     * run-local.sh が作る形がそうなっている)。ファイル 1 つを渡されたらステップ名は "-"。
     */
    private static Map<String, List<Observation>> collect(Path root) throws IOException {
        Map<String, List<Observation>> result = new LinkedHashMap<>();
        List<Path> files;
        if (Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
        } else {
            files = List.of(root);
        }
        for (Path file : files) {
            String step = Files.isDirectory(root) && file.getParent() != null
                    && !file.getParent().equals(root)
                    ? file.getParent().getFileName().toString().toUpperCase() : "-";
            for (String line : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
                Matcher m = PRB.matcher(line);
                if (m.find()) {
                    Observation o = new Observation(step, m.group(1), m.group(2), m.group(3));
                    result.computeIfAbsent(o.key(), k -> new ArrayList<>()).add(o);
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- print

    /**
     * 実機から「レコードのまま」取り出した印字データセットを、1 レコード 1 行の
     * 読める形にする。先頭の制御文字は角括弧で見せる ({@code [1]} は改頁、{@code [ ]} は
     * 1 行送り、{@code [0]} は 2 行送り、{@code [+]} は重ね印字)。
     *
     * <p>{@code --rdw} は可変長 (RECFM=V / VB / VBA) を RDW つきで取り出したもの、
     * {@code --fixed n} は固定長 (F / FB / FBA) を長さ n で切ったものである。
     * 制御文字を持つかどうか (RECFM の A) は、このデータを見ても分からない。LISTCAT か
     * ISPF 3.4 で RECFM を確かめて、書き留めておくこと。
     */
    private static void print(String[] args) throws IOException {
        if (args.length < 3) {
            usage();
            return;
        }
        Path file = Path.of(args[1]);
        boolean rdw = false;
        int fixed = 0;
        Charset charset = Charset.forName("IBM1047");
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--rdw" -> rdw = true;
                case "--fixed" -> fixed = Integer.parseInt(args[++i]);
                case "--cp" -> charset = Charset.forName(args[++i]);
                default -> {
                    usage();
                    return;
                }
            }
        }
        byte[] bytes = Files.readAllBytes(file);
        List<byte[]> records = new ArrayList<>();
        if (rdw) {
            int p = 0;
            while (p + 4 <= bytes.length) {
                int length = ((bytes[p] & 0xFF) << 8) | (bytes[p + 1] & 0xFF);
                if (length < 4 || p + length > bytes.length) {
                    System.err.printf("broken RDW at offset %d%n", p);
                    break;
                }
                byte[] record = new byte[length - 4];
                System.arraycopy(bytes, p + 4, record, 0, record.length);
                records.add(record);
                p += length;
            }
        } else if (fixed > 0) {
            for (int p = 0; p + fixed <= bytes.length; p += fixed) {
                byte[] record = new byte[fixed];
                System.arraycopy(bytes, p, record, 0, fixed);
                records.add(record);
            }
        } else {
            usage();
            return;
        }
        int n = 0;
        for (byte[] record : records) {
            n++;
            String text = new String(record, charset);
            String control = text.isEmpty() ? "" : text.substring(0, 1);
            String rest = text.isEmpty() ? "" : text.substring(1).stripTrailing();
            System.out.printf("%05d len=%-4d [%s]%s%n", n, record.length, control, rest);
        }
    }

    // ------------------------------------------------------------------- cp

    /**
     * CBLCP の出力 (実機の NATIONAL-OF の結果) を、この処理系が使う JDK のチャーセットの
     * 復号と比べる。IBM-1390 / IBM-1399 は JDK に無い (P-002) ので、上位互換に近い
     * x-IBM939 と比べ、差分を「作らねばならない変換表の差」として数える。
     */
    private static void codePages(Path file) throws IOException {
        Map<String, String> jdk = Map.of(
                "01047", "IBM1047", "00037", "IBM037",
                "00930", "x-IBM930", "00939", "x-IBM939",
                "01390", "x-IBM939", "01399", "x-IBM939");
        Pattern line = Pattern.compile("PRB CP\\.(\\d{5})\\.([SD])\\.([0-9A-F]+) ([0-9A-F]*)");
        Map<String, int[]> counts = new TreeMap<>();
        Map<String, List<String>> samples = new TreeMap<>();
        HexFormat hex = HexFormat.of().withUpperCase();
        for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Matcher m = line.matcher(text);
            if (!m.find()) {
                continue;
            }
            String ccsid = m.group(1);
            String mode = m.group(2);
            byte[] key = hex.parseHex(m.group(3));
            String host = m.group(4);
            String name = jdk.get(ccsid);
            String bucket = ccsid + "." + mode + (name == null ? "" : " vs " + name);
            int[] c = counts.computeIfAbsent(bucket, k -> new int[2]);
            if (name == null) {
                c[1]++;
                continue;
            }
            byte[] input;
            if (mode.equals("D")) {
                input = new byte[] {0x0E, key[0], key[1], 0x0F};
            } else {
                input = key;
            }
            String decoded = new String(input, Charset.forName(name));
            String mine = hex.formatHex(decoded.getBytes(StandardCharsets.UTF_16BE));
            if (mine.equals(host)) {
                c[0]++;
            } else {
                c[1]++;
                List<String> s = samples.computeIfAbsent(bucket, k -> new ArrayList<>());
                if (s.size() < 20) {
                    s.add(m.group(3) + " z/OS=" + host + " JDK=" + mine);
                }
            }
        }
        counts.forEach((bucket, c) -> {
            System.out.printf("%-24s MATCH %6d  DIFF %6d%n", bucket, c[0], c[1]);
            for (String s : samples.getOrDefault(bucket, List.of())) {
                System.out.println("    " + s);
            }
        });
    }
}
