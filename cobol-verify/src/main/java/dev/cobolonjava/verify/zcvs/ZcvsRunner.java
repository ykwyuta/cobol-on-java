package dev.cobolonjava.verify.zcvs;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.compiler.source.DirectoryCopyBookResolver;
import dev.cobolonjava.compiler.source.Preprocessor;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * ZCVS (Zeus の z/OS Enterprise COBOL 検査スイート) をこの処理系へ流す。
 *
 * <p>CCVS85 は<b>規格</b>を測る。ZCVS が測るのはその外にある<b>z/OS の決めごと</b>で
 * ある。パック 10 進やゾーン 10 進の符号のニブル、{@code TRUNC}、{@code NUMPROC}、EBCDIC の
 * 照合順序、国字と DBCS、{@code ENTRY} と記憶域の寿命、QSAM / VSAM の入出力状態。どれも
 * CCVS85 を全部通しても出てこない差である。別の処理系 (Zeus) が自分の互換性を測るために
 * 書いたものなので、<b>自分で書いた規則を自分のテストで固定する</b>罠の外にある
 * (覚え書き 3)。
 *
 * <p>スイートは同梱しない (要件 NFR-042)。ZCVS は GPL-3.0-or-later であり、Apache-2.0 の
 * 成果物へは取り込めない。外にある置き場を指して動く。
 *
 * <h2>実機の裏付けではない</h2>
 * <p>ZCVS の期待値は Zeus の作者が公開仕様から書いたものであり、<b>z/OS で流した結果では
 * ない</b>。落ちた判定は「この処理系が違う」ことの強い手がかりだが、証明ではない。直す前に
 * 公開仕様を読むこと。
 *
 * <h2>数え方</h2>
 * <p>報告は CCVS85 と同じ形 ({@code PASS} / {@code FAIL*} と末尾の集計) で書かれる。
 * 判定の数は<b>プログラムが自分で書いた集計</b>から読む。最後まで行かなかった本は
 * 「0 件落ちた」ではなく<b>壊れた</b>と数える。
 */
public final class ZcvsRunner {

    /** 1 本にかけてよい時間 (秒)。翻訳にも実行にも置く (覚え書き 6)。 */
    public static final long LIMIT_SECONDS = 60;

    /** 結末の種類。 */
    public enum Kind {
        /** 判定がすべて通った。または、期待どおり翻訳できた・断った。 */
        PASSED,
        /** 最後まで動いたが、落ちた判定がある。 */
        FAILED,
        /** 翻訳できなかった (通るはずの本)。 */
        NOT_COMPILED,
        /** 断るはずの本を翻訳してしまった。 */
        NOT_REJECTED,
        /** 動かしたら落ちた。報告が最後まで書かれていない。 */
        CRASHED,
        /** 返ってこなかった。 */
        TIMED_OUT
    }

    /**
     * 1 本の結末。
     *
     * @param program  プログラム名
     * @param module   区分 ({@code ZD} など)
     * @param kind     結末
     * @param passed   通った判定の数
     * @param total    判定の数
     * @param reason   翻訳できなかった・落ちた理由。無ければ空
     * @param failures 落ちた判定。{@code 判定名  EXP=... ACT=...}
     */
    public record Outcome(String program, String module, Kind kind, int passed, int total,
                          String reason, List<String> failures) {
    }

    private final Path suite;
    private final ZcvsManifest manifest;
    private final CopyBookResolver resolver;

    public ZcvsRunner(Path suite) {
        this.suite = suite;
        this.manifest = ZcvsManifest.read(suite.resolve("manifest.toml"));
        CopyBookResolver copy = new DirectoryCopyBookResolver(suite.resolve("copy"));
        // COPY ... OF COPYALT は別のライブラリを指す。置き場の名前は小文字である
        this.resolver = (textName, libraryName) -> libraryName == null
                ? copy.resolve(textName, null)
                : new DirectoryCopyBookResolver(
                        suite.resolve(libraryName.toLowerCase(Locale.ROOT)))
                        .resolve(textName, null);
    }

    /** スイートの全部を流す。{@code only} が空でなければ、その名前の本だけを流す。 */
    public List<Outcome> run(Set<String> only) {
        List<Outcome> outcomes = new ArrayList<>();
        for (Path module : directories(suite)) {
            String name = module.getFileName().toString();
            if (!name.matches("Z[A-Z]")) {
                continue;
            }
            List<Path> helpers = Files.isDirectory(module.resolve("lib"))
                    ? sources(module.resolve("lib")) : List.of();
            for (Path source : sources(module)) {
                String program = stem(source);
                if (only.isEmpty() || only.contains(program)) {
                    outcomes.add(run(name, program, source, helpers));
                }
            }
        }
        return List.copyOf(outcomes);
    }

    private Outcome run(String module, String program, Path source, List<Path> helpers) {
        ZcvsManifest.Entry entry = manifest.of(program);
        CobolCompiler compiler = new CobolCompiler(Preprocessor.with(resolver));
        if (entry.ccsid() != 0) {
            compiler = compiler.withOptions(new CompilerOptions(
                    Map.of("CODEPAGE", String.valueOf(entry.ccsid()))));
        }
        List<CobolCompiler.Result> compiled = new ArrayList<>();
        List<Path> parts = new ArrayList<>();
        parts.add(source);
        if (entry.expect() == ZcvsManifest.Expect.RUN) {
            // 副プログラムは呼ぶ側と同じ読み込み器へ入れる。動的な CALL はそこから引く
            parts.addAll(helpers);
        }
        for (Path part : parts) {
            String refusal = compile(compiler, part, compiled);
            if (refusal == null) {
                continue;
            }
            if (refusal.equals(TIMEOUT)) {
                return outcome(program, module, Kind.TIMED_OUT, "compilation did not return");
            }
            if (entry.expect() == ZcvsManifest.Expect.COMPILE_ERROR && part.equals(source)
                    && !refusal.startsWith(THROWN)) {
                // 断ったことだけを見る。文面は Zeus のものであり、照合しない。
                // 例外で落ちたのは断ったうちに入らない (単なる破綻を成功にしない)
                return new Outcome(program, module, Kind.PASSED, 1, 1, refusal, List.of());
            }
            return outcome(program, module, Kind.NOT_COMPILED,
                    (part.equals(source) ? "" : stem(part) + ": ") + refusal);
        }
        if (entry.expect() == ZcvsManifest.Expect.COMPILE_ERROR) {
            return new Outcome(program, module, Kind.NOT_REJECTED, 0, 1,
                    "expected a diagnostic containing: " + entry.diagnostic(), List.of());
        }
        if (entry.expect() == ZcvsManifest.Expect.COMPILE_ONLY) {
            return new Outcome(program, module, Kind.PASSED, 1, 1, "", List.of());
        }
        Path directory;
        try {
            directory = Files.createTempDirectory("zcvs-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            return execute(module, program, entry, compiled, directory);
        } finally {
            delete(directory);
        }
    }

    private static final String TIMEOUT = "\u0000timeout";
    private static final String THROWN = "threw ";

    /** @return 翻訳できなかった理由。できたら {@code null} */
    private static String compile(CobolCompiler compiler, Path source,
                                  List<CobolCompiler.Result> into) {
        Object result = limited("compile-" + stem(source), () -> {
            try {
                return compiler.compile(source.getFileName().toString(),
                        Files.readString(source, StandardCharsets.UTF_8));
            } catch (RuntimeException | StackOverflowError | AssertionError thrown) {
                return thrown;
            }
        });
        if (result == null) {
            return TIMEOUT;
        }
        if (result instanceof Throwable thrown) {
            return THROWN + describe(thrown);
        }
        CobolCompiler.Result compiled = (CobolCompiler.Result) result;
        if (!compiled.succeeded()) {
            return compiled.diagnostics().stream().filter(d -> !d.isWarning()).findFirst()
                    .map(Object::toString).orElse("(no diagnostic)");
        }
        into.add(compiled);
        return null;
    }

    /** 翻訳したクラスを入れる読み込み器。1 本につき 1 つ作る。 */
    private static final class Generated extends ClassLoader {

        private Generated() {
            super(ZcvsRunner.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private Outcome execute(String module, String program, ZcvsManifest.Entry entry,
                            List<CobolCompiler.Result> compiled, Path directory) {
        CodePage codePage = entry.ccsid() == 0 ? CodePages.DEFAULT
                : CodePages.forName(String.valueOf(entry.ccsid()));
        ProgramContext context = ProgramContext.standard()
                .withOutput(OutputStream.nullOutputStream())
                // 標準入力は読ませない。道具の起動のしかたで結果が変わってはならない
                .withInput(() -> null)
                .withCodePage(codePage)
                .withCatalog(new DataSetCatalog(directory));
        Object thrown = limited("run-" + program, () -> {
            try {
                Generated loader = new Generated();
                Class<?> first = null;
                for (CobolCompiler.Result result : compiled) {
                    for (CobolCompiler.Compiled part : result.programs()) {
                        Class<?> type = loader.define(part.className(), part.classFile());
                        first = first == null ? type : first;
                    }
                }
                ((CobolProgram) first.getDeclaredConstructor().newInstance())
                        .runFresh(context, parm(entry.parm(), codePage));
                return "";
            } catch (Throwable caught) {
                return caught;
            }
        });
        if (thrown == null) {
            return outcome(program, module, Kind.TIMED_OUT, "the program did not return");
        }
        String crash = thrown instanceof Throwable caught ? describe(caught) : "";
        String report = reportIn(directory);
        if (report == null) {
            return outcome(program, module, Kind.CRASHED,
                    crash.isEmpty() ? "the program wrote no report" : crash);
        }
        List<String> failures = failures(report);
        Matcher counts = EXECUTED.matcher(report);
        if (!report.contains("END OF TEST-2") || !counts.find()) {
            // 途中で止まったものを「落ちた検査が 0 だから合格」と数えない
            return new Outcome(program, module, Kind.CRASHED, passes(report),
                    passes(report) + failures.size(),
                    crash.isEmpty() ? "the report is not complete" : crash, failures);
        }
        int passed = Integer.parseInt(counts.group(1));
        int total = Integer.parseInt(counts.group(2));
        if (!crash.isEmpty()) {
            return new Outcome(program, module, Kind.CRASHED, passed, total, crash, failures);
        }
        if (context.returnCode() != entry.exitCode()) {
            return new Outcome(program, module, Kind.FAILED, passed, total,
                    "return code " + context.returnCode() + ", expected " + entry.exitCode(),
                    failures);
        }
        return new Outcome(program, module, passed == total && total > 0
                ? Kind.PASSED : Kind.FAILED, passed, total, "", failures);
    }

    /** PARM はホストの形で渡す。頭に 2 バイトの長さ、続けて文字である。 */
    private static DataView[] parm(String parm, CodePage codePage) {
        if (parm == null) {
            return new DataView[0];
        }
        byte[] text = codePage.encode(parm);
        // 連絡節の項目が PARM より長く宣言されていても読めるよう、余りを空白で取る
        Storage storage = Storage.allocate(Math.max(text.length + 2, 102));
        storage.whole().fill(codePage.space());
        storage.view(0, 2).setBytes(new byte[] {(byte) (text.length >> 8), (byte) text.length});
        if (text.length > 0) {
            storage.view(2, text.length).setBytes(text);
        }
        return new DataView[] {storage.whole()};
    }

    private static final Pattern EXECUTED =
            Pattern.compile("(\\d+) OF (\\d+) TESTS WERE EXECUTED SUCCESSFULLY");

    /** 報告は 120 バイトの固定長レコードの並びであり、改行を持たない。 */
    private static final int RECORD = 120;

    private static String reportIn(Path directory) {
        Path file = directory.resolve("REPORT");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            // 見出しと集計は英数字であり、どの EBCDIC でも同じ位置にある
            return CodePages.DEFAULT.decode(Files.readAllBytes(file));
        } catch (IOException e) {
            return null;
        }
    }

    private static int passes(String report) {
        int count = 0;
        for (int at = 0; at + RECORD <= report.length(); at += RECORD) {
            if (report.startsWith("  PASS ", at)) {
                count++;
            }
        }
        return count;
    }

    /** 桁割りは 空白 2・合否 5・空白 5・判定名 20・空白 2・覚え書き 86 である。 */
    private static List<String> failures(String report) {
        List<String> found = new ArrayList<>();
        for (int at = 0; at + RECORD <= report.length(); at += RECORD) {
            if (report.startsWith("  FAIL*", at)) {
                found.add(report.substring(at + 12, at + 32).strip() + "  "
                        + report.substring(at + 34, at + RECORD).strip());
            }
        }
        return List.copyOf(found);
    }

    /**
     * 別の走脈で動かし、時間の限りまで待つ。
     *
     * @return 結果。時間切れなら {@code null}
     */
    private static Object limited(String name, Callable<Object> work) {
        FutureTask<Object> task = new FutureTask<>(work);
        Thread worker = new Thread(task, name);
        // 見捨てる走脈が道具全体を道連れにしないようにする
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            worker.interrupt();
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Outcome outcome(String program, String module, Kind kind, String reason) {
        return new Outcome(program, module, kind, 0, 0, reason, List.of());
    }

    /**
     * 落ちた理由。文面を持たない例外 (NullPointerException など) は、<b>どこで落ちたか</b>が
     * 無いと手がかりにならないので、いちばん上の枠を添える。
     */
    private static String describe(Throwable thrown) {
        if (thrown.getMessage() != null) {
            return thrown.getClass().getSimpleName() + ": " + thrown.getMessage();
        }
        // JDK の中の枠は読んでも分からない。処理系の枠が出るまで下りる
        StringBuilder where = new StringBuilder();
        int shown = 0;
        for (StackTraceElement frame : thrown.getStackTrace()) {
            if (frame.getClassName().startsWith("java.")) {
                continue;
            }
            where.append(shown == 0 ? " at " : " < ").append(frame);
            if (++shown == 3) {
                break;
            }
        }
        return thrown.getClass().getSimpleName() + where;
    }

    private static List<Path> directories(Path root) {
        try (Stream<Path> list = Files.list(root)) {
            return list.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> sources(Path directory) {
        try (Stream<Path> list = Files.list(directory)) {
            return list.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".CBL"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static void delete(Path directory) {
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // 作業場所の後始末に失敗しても、測定そのものは終わっている
        }
    }

    /** 診断の頭にある位置。{@code 名前:行:桁: } の形である。 */
    private static final Pattern POSITION = Pattern.compile(":\\d+:\\d+: ");

    /** 数と、落ちたところの一覧。 */
    public static String text(List<Outcome> outcomes) {
        StringBuilder out = new StringBuilder("ZCVS を流した結果\n");
        Map<String, int[]> modules = new TreeMap<>();
        int passedPrograms = 0;
        int passed = 0;
        int total = 0;
        for (Outcome outcome : outcomes) {
            int[] row = modules.computeIfAbsent(outcome.module(), k -> new int[4]);
            row[0]++;
            row[1] += outcome.kind() == Kind.PASSED ? 1 : 0;
            row[2] += outcome.passed();
            row[3] += outcome.total();
            passedPrograms += outcome.kind() == Kind.PASSED ? 1 : 0;
            passed += outcome.passed();
            total += outcome.total();
        }
        out.append(String.format("  プログラム %d 本のうち %d 本が通った。判定 %d / %d%n",
                outcomes.size(), passedPrograms, passed, total));
        modules.forEach((module, row) -> out.append(String.format(
                "  %-4s プログラム %3d / %3d   判定 %4d / %4d%n",
                module, row[1], row[0], row[2], row[3])));
        out.append("\n通らなかったもの\n");
        for (Outcome outcome : outcomes) {
            if (outcome.kind() == Kind.PASSED) {
                continue;
            }
            out.append(String.format("  %-8s %-12s %d/%d  %s%n", outcome.program(),
                    outcome.kind(), outcome.passed(), outcome.total(),
                    tail(outcome.reason())));
            for (String failure : outcome.failures()) {
                out.append("      ").append(failure).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * 診断は尻を残す (覚え書き 6)。頭の位置 ({@code 名前:行:桁}) も残す。
     * 理由が細かく散ったときに、止まった原文の行を引けるようにである (覚え書き 4)。
     */
    private static String tail(String reason) {
        if (reason.length() <= 200) {
            return reason;
        }
        Matcher position = POSITION.matcher(reason);
        String head = position.find() && position.end() < 60
                ? reason.substring(0, position.end()) : "";
        return head + "..." + reason.substring(reason.length() - 150);
    }

    /** 名前で引けるよう、探し方だけを外へ出す。 */
    public static Optional<Outcome> find(List<Outcome> outcomes, String program) {
        return outcomes.stream().filter(o -> o.program().equals(program)).findFirst();
    }
}
