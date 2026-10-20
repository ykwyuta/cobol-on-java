package dev.cobolonjava.verify.suite;

import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.interop.ProgramParameter;
import dev.cobolonjava.runtime.interop.ProgramSignature;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zeus の検査スイート (ZCVS / ZPVS / ZHVS) を、こちらの処理系で翻訳して動かす (設計 87)。
 *
 * <p>流し方は Zeus のハーネス ({@code crates/zeus-ccvs/src/runner.rs}) に揃える。スイートとモジュールの
 * {@code lib/} にある補助プログラムを翻訳し、主プログラムと同じ読み込み器へ入れて CALL で引けるようにする。
 * 報告は {@code REPORT} の DD へ書かれる。言語ごとの違いは翻訳だけで ({@link SuiteLanguage})、翻訳したものは
 * どれも {@code CobolProgram} のクラスなので、動かす段と報告を読む段は共通である。
 *
 * <h2>道具が失敗を作らない (CLAUDE.md §6)</h2>
 * <ul>
 *   <li>断るべきものの判定は「翻訳が断ったか」で行う。Zeus の診断の文面 ({@code diagnostic_contains}) は Zeus の
 *       処理系の文面であり、こちらの文面とは違う。合否に使えば、正しく断っても文面の違いで落ちる。同じ語を
 *       含んだかは参考として数えるだけである</li>
 *   <li>1 本が落ちても返らなくても止めない。翻訳と実行のそれぞれに時間の限りを置く</li>
 *   <li>報告を読むときは {@code coverage.json} の一覧と突き合わせ、書かれなかった検査を「出なかった」と数える</li>
 * </ul>
 */
public final class SuiteRunner {

    /** 1 本の翻訳と実行のそれぞれに許す時間 (秒)。CCVS85 の道具と同じである。 */
    public static final long LIMIT_SECONDS = 60;

    /**
     * 報告の 1 行。「PASS」か「FAIL*」、空白、検査の名前 (20 桁まで)。空白の数は言語で違う
     * (COBOL は 5 桁、PL/I は 1 桁、HLASM は名前を 6 桁目に置く)。
     */
    private static final Pattern ASSERTION =
            Pattern.compile("(?<![A-Z0-9*])(PASS|FAIL\\*) +([A-Z0-9][A-Z0-9#@$-]{0,19})");
    /** 最後まで書いた印。COBOL は「END OF TEST-」、PL/I と HLASM は集計の行で終わる。 */
    private static final Pattern COMPLETE =
            Pattern.compile("END OF TEST-|TESTS WERE EXECUTED SUCCESSFULLY");

    private final Path suite;
    private final SuiteLanguage language;
    private final SuiteManifest manifest;
    private final SuiteCoverage coverage;
    private final long limitSeconds;

    public SuiteRunner(Path suite, SuiteLanguage language, long limitSeconds) {
        this.suite = suite;
        this.language = language;
        this.manifest = SuiteManifest.load(suite);
        this.coverage = SuiteCoverage.load(suite);
        this.limitSeconds = limitSeconds;
    }

    public SuiteRunner(Path suite, SuiteLanguage language) {
        this(suite, language, LIMIT_SECONDS);
    }

    public SuiteManifest manifest() {
        return manifest;
    }

    /** 全モジュールを流す。{@code only} が空でなければ、その名前の主プログラムだけを流す。 */
    public List<SuiteOutcome> run(Set<String> only) {
        List<SuiteOutcome> outcomes = new ArrayList<>();
        for (String module : manifest.modules()) {
            Path directory = suite.resolve(module);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            List<Path> helpers = new ArrayList<>(sources(suite.resolve("lib")));
            helpers.addAll(sources(directory.resolve("lib")));
            for (Path source : sources(directory)) {
                String program = stem(source);
                if (!only.isEmpty() && !only.contains(program)) {
                    continue;
                }
                outcomes.add(runOne(module, program, source, helpers));
            }
        }
        return outcomes;
    }

    private SuiteOutcome runOne(String module, String program, Path source, List<Path> helpers) {
        SuiteManifest.Program config = manifest.program(program);
        List<String> tests = coverage.testsOf(program);
        int expected = config.expect() == SuiteManifest.Expect.RUN ? tests.size() : 1;
        Translated main = translate(source, config);
        if (main.failure() != null) {
            return SuiteOutcome.simple(module, program, config.expect(), main.status(), main.failure(), expected);
        }
        SuiteLanguage.Translation result = main.translation();
        switch (config.expect()) {
            case COMPILE_ERROR -> {
                if (result.succeeded()) {
                    return SuiteOutcome.simple(module, program, config.expect(),
                            SuiteOutcome.Status.ACCEPTED_WRONGLY, "the program was accepted", 1);
                }
                boolean wording = config.diagnosticContains() != null && String.join("\n", result.errors())
                        .toLowerCase(Locale.ROOT).contains(config.diagnosticContains().toLowerCase(Locale.ROOT));
                return new SuiteOutcome(module, program, config.expect(), SuiteOutcome.Status.PASSED,
                        result.errors().get(0), 1, 1, 0, 0, wording);
            }
            case COMPILE_ONLY -> {
                return result.succeeded()
                        ? new SuiteOutcome(module, program, config.expect(), SuiteOutcome.Status.PASSED, "",
                                1, 1, 0, 0, false)
                        : SuiteOutcome.simple(module, program, config.expect(),
                                SuiteOutcome.Status.NOT_COMPILED, result.errors().get(0), 1);
            }
            default -> {
                if (!result.succeeded()) {
                    return SuiteOutcome.simple(module, program, config.expect(),
                            SuiteOutcome.Status.NOT_COMPILED, result.errors().get(0), expected);
                }
            }
        }
        List<SuiteLanguage.Unit> units = new ArrayList<>(result.units());
        for (Path helper : helpers) {
            Translated called = translate(helper, SuiteManifest.Program.DEFAULT);
            String why = called.failure() != null ? called.failure()
                    : called.translation().succeeded() ? null : called.translation().errors().get(0);
            if (why != null) {
                return SuiteOutcome.simple(module, program, config.expect(), SuiteOutcome.Status.NOT_COMPILED,
                        "helper " + stem(helper) + ": " + why, expected);
            }
            units.addAll(called.translation().units());
        }
        return execute(module, program, source, config, tests, units, result.signature());
    }

    /** 翻訳の結末。時間切れと例外は失敗の理由として分けて持つ。 */
    private record Translated(SuiteLanguage.Translation translation, SuiteOutcome.Status status, String failure) {
    }

    private Translated translate(Path source, SuiteManifest.Program config) {
        SuiteLanguage.Translation[] holder = new SuiteLanguage.Translation[1];
        Throwable thrown = awaited(stem(source), () -> {
            holder[0] = language.translate(suite, source, config);
            return null;
        });
        if (thrown == TIMED_OUT) {
            return new Translated(null, SuiteOutcome.Status.TIMED_OUT,
                    "translation did not return within " + limitSeconds + "s");
        }
        if (thrown != null) {
            return new Translated(null, SuiteOutcome.Status.CRASHED, "translation failed: " + describe(thrown));
        }
        return new Translated(holder[0], null, null);
    }

    private SuiteOutcome execute(String module, String program, Path source, SuiteManifest.Program config,
                                 List<String> tests, List<SuiteLanguage.Unit> units, ProgramSignature signature) {
        CodePage codePage = config.ccsid() < 0 ? CodePages.DEFAULT
                : CodePages.forName(String.valueOf(config.ccsid()));
        Path directory;
        try {
            directory = Files.createTempDirectory("zeus-suite-" + program + "-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Generated loader = new Generated();
            Class<?> first = null;
            try {
                for (SuiteLanguage.Unit unit : units) {
                    Class<?> type = loader.define(unit.className(), unit.classFile());
                    if (first == null) {
                        first = type;
                    }
                }
            } catch (RuntimeException | LinkageError thrown) {
                return SuiteOutcome.simple(module, program, config.expect(), SuiteOutcome.Status.CRASHED,
                        describe(thrown), tests.size());
            }
            ProgramContext context = ProgramContext.standard()
                    .withOutput(OutputStream.nullOutputStream())
                    .withInput(input(source))
                    .withCodePage(codePage)
                    .withCatalog(new DataSetCatalog(directory));
            DataView[] arguments = parm(config.parm(), signature, codePage);
            Class<?> main = first;
            Throwable thrown = awaited(program, () -> {
                ((CobolProgram) main.getDeclaredConstructor().newInstance()).runFresh(context, arguments);
                return null;
            });
            if (thrown == TIMED_OUT) {
                return SuiteOutcome.simple(module, program, config.expect(), SuiteOutcome.Status.TIMED_OUT,
                        "the program did not return within " + limitSeconds + "s", tests.size());
            }
            String report = report(directory, codePage);
            if (report == null) {
                return SuiteOutcome.simple(module, program, config.expect(), SuiteOutcome.Status.CRASHED,
                        thrown == null ? "the program wrote no complete report" : describe(thrown), tests.size());
            }
            return judged(module, program, config, tests, report, thrown, context.returnCode());
        } finally {
            delete(directory);
        }
    }

    /** 報告を一覧と突き合わせる。 */
    private static SuiteOutcome judged(String module, String program, SuiteManifest.Program config,
                                       List<String> tests, String report, Throwable thrown, int returnCode) {
        Set<String> passed = new LinkedHashSet<>();
        Set<String> failed = new LinkedHashSet<>();
        Matcher line = ASSERTION.matcher(report);
        while (line.find()) {
            (line.group(1).equals("PASS") ? passed : failed).add(line.group(2));
        }
        int pass = 0;
        int fail = 0;
        int missing = 0;
        List<String> failures = new ArrayList<>();
        for (String test : tests) {
            if (failed.contains(test)) {
                fail++;
                failures.add(test);
            } else if (passed.contains(test)) {
                pass++;
            } else {
                missing++;
            }
        }
        List<String> problems = new ArrayList<>();
        if (!failures.isEmpty()) {
            problems.add("FAIL " + String.join(" ", failures.subList(0, Math.min(5, failures.size())))
                    + (failures.size() > 5 ? " ..." : ""));
        }
        if (missing > 0) {
            problems.add(missing + " test(s) not reported");
        }
        int expectedCode = config.expectExitCode() == null ? 0 : config.expectExitCode();
        if (returnCode != expectedCode) {
            problems.add("RETURN-CODE " + returnCode + " (expected " + expectedCode + ")");
        }
        if (thrown != null) {
            problems.add(describe(thrown));
        }
        SuiteOutcome.Status status = problems.isEmpty() ? SuiteOutcome.Status.PASSED : SuiteOutcome.Status.FAILED;
        return new SuiteOutcome(module, program, config.expect(), status, String.join("; ", problems),
                tests.size(), pass, fail, missing, false);
    }

    /**
     * PARM を主プログラムへ渡す。ジョブの実行 ({@code JobRunner}、P-090) と同じく、連絡節の 1 つ目が宣言した長さまで
     * 領域を取り、頭に長さの半語と PARM を置く。引数を受けないプログラムには何も渡さない。
     */
    private static DataView[] parm(String parm, ProgramSignature signature, CodePage codePage) {
        if (signature == null || signature.parameters().isEmpty()) {
            return new DataView[0];
        }
        List<ProgramParameter> parameters = signature.parameters();
        byte[] text = parm == null ? new byte[0] : codePage.encode(parm);
        Storage storage = Storage.allocate(Math.max(text.length + 2, parameters.get(0).minimumBytes()));
        storage.whole().fill(codePage.space());
        storage.view(0, 2).setBytes(new byte[] {(byte) (text.length >> 8), (byte) text.length});
        if (text.length > 0) {
            storage.view(2, text.length).setBytes(text);
        }
        return new DataView[] {storage.whole()};
    }

    /** {@code 名前.DAT} があれば、その行を ACCEPT へ渡す (Zeus は標準入力へ渡す)。無ければ最初から尽きている。 */
    private static Supplier<String> input(Path source) {
        Path data = source.resolveSibling(stem(source) + ".DAT");
        Deque<String> lines = new ArrayDeque<>();
        if (Files.isRegularFile(data)) {
            lines.addAll(List.of(read(data).split("\\R", -1)));
        }
        return lines::poll;
    }

    /** 作業場所のファイルのうち、最後まで書かれた報告。 */
    private static String report(Path directory, CodePage codePage) {
        try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String text = codePage.decode(Files.readAllBytes(file));
                if (COMPLETE.matcher(text).find()) {
                    return text;
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 時間切れを表す番人。 */
    private static final Throwable TIMED_OUT = new Throwable("timed out");

    @FunctionalInterface
    private interface Work {
        Object run() throws Exception;
    }

    /** 別の走脈で動かし、時間の限りまで待つ。返らなければ見捨てて次へ進む。 */
    private Throwable awaited(String name, Work work) {
        BlockingQueue<Object> done = new ArrayBlockingQueue<>(1);
        Object empty = new Object();
        Thread worker = new Thread(null, () -> {
            Object outcome = empty;
            try {
                work.run();
            } catch (Throwable caught) {
                outcome = caught;
            }
            done.offer(outcome);
        }, "zeus-suite-" + name, 256L * 1024 * 1024);
        worker.setDaemon(true);
        worker.start();
        try {
            Object outcome = done.poll(limitSeconds, TimeUnit.SECONDS);
            if (outcome == null) {
                worker.interrupt();
                return TIMED_OUT;
            }
            return outcome == empty ? null : (Throwable) outcome;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            worker.interrupt();
            return TIMED_OUT;
        }
    }

    private static final class Generated extends ClassLoader {

        private Generated() {
            super(SuiteRunner.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private List<Path> sources(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        String suffix = "." + language.extension();
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().toUpperCase(Locale.ROOT).endsWith(suffix))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stem(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)).toUpperCase(Locale.ROOT);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String describe(Throwable thrown) {
        Throwable cause = thrown instanceof java.lang.reflect.InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : thrown;
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    private static void delete(Path directory) {
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            // 作業場所の後始末に失敗しても、測定そのものは終わっている
        }
    }
}
