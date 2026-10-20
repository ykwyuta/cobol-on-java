package dev.cobolonjava.verify.suite;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.compiler.source.DirectoryCopyBookResolver;
import dev.cobolonjava.compiler.source.Preprocessor;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zeus の COBOL 検査スイート ZCVS を、こちらの処理系で翻訳して動かす (設計 87)。
 *
 * <p>流し方は Zeus のハーネス ({@code crates/zeus-ccvs/src/runner.rs}) に揃える。モジュールの {@code lib/} にある
 * 補助プログラムを先に翻訳し、主プログラムと同じ読み込み器へ入れて CALL で引けるようにする。報告は
 * {@code ASSIGN TO REPORT} のファイルで、CCVS85 と同じ「END OF TEST-」で終わる。
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
public final class CobolSuiteRunner {

    /** 1 本の翻訳と実行のそれぞれに許す時間 (秒)。CCVS85 の道具と同じである。 */
    public static final long LIMIT_SECONDS = 60;

    /** 報告の 1 行。「PASS 」か「FAIL*」、5 桁の空白、検査の名前 (20 桁まで)。 */
    private static final Pattern ASSERTION =
            Pattern.compile("(PASS |FAIL\\*) {5}([A-Z0-9][A-Z0-9#@$-]{0,19})");

    private final Path suite;
    private final SuiteManifest manifest;
    private final SuiteCoverage coverage;
    private final long limitSeconds;

    public CobolSuiteRunner(Path suite, long limitSeconds) {
        this.suite = suite;
        this.manifest = SuiteManifest.load(suite);
        this.coverage = SuiteCoverage.load(suite);
        this.limitSeconds = limitSeconds;
    }

    public CobolSuiteRunner(Path suite) {
        this(suite, LIMIT_SECONDS);
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
        CobolCompiler compiler = compiler(config);
        Compiled main = compile(compiler, source);
        if (main.timedOut()) {
            return SuiteOutcome.simple(module, program, config.expect(), SuiteOutcome.Status.TIMED_OUT,
                    "compilation did not return within " + limitSeconds + "s", expected);
        }
        if (main.crash() != null) {
            return SuiteOutcome.simple(module, program, config.expect(), SuiteOutcome.Status.CRASHED,
                    main.crash(), expected);
        }
        CobolCompiler.Result result = main.result();
        switch (config.expect()) {
            case COMPILE_ERROR -> {
                if (result.succeeded()) {
                    return SuiteOutcome.simple(module, program, config.expect(),
                            SuiteOutcome.Status.ACCEPTED_WRONGLY, "the program was accepted", 1);
                }
                String diagnostics = result.diagnostics().toString();
                boolean wording = config.diagnosticContains() != null && diagnostics.toLowerCase(Locale.ROOT)
                        .contains(config.diagnosticContains().toLowerCase(Locale.ROOT));
                return new SuiteOutcome(module, program, config.expect(), SuiteOutcome.Status.PASSED,
                        firstDiagnostic(result), 1, 1, 0, 0, wording);
            }
            case COMPILE_ONLY -> {
                return result.succeeded()
                        ? new SuiteOutcome(module, program, config.expect(), SuiteOutcome.Status.PASSED, "",
                                1, 1, 0, 0, false)
                        : SuiteOutcome.simple(module, program, config.expect(),
                                SuiteOutcome.Status.NOT_COMPILED, firstDiagnostic(result), 1);
            }
            default -> {
                if (!result.succeeded()) {
                    return SuiteOutcome.simple(module, program, config.expect(),
                            SuiteOutcome.Status.NOT_COMPILED, firstDiagnostic(result), expected);
                }
            }
        }
        List<CobolCompiler.Compiled> programs = new ArrayList<>(result.programs());
        for (Path helper : helpers) {
            Compiled called = compile(compiler, helper);
            if (called.result() == null || !called.result().succeeded()) {
                String why = called.timedOut() ? "timed out"
                        : called.crash() != null ? called.crash() : firstDiagnostic(called.result());
                return SuiteOutcome.simple(module, program, config.expect(), SuiteOutcome.Status.NOT_COMPILED,
                        "helper " + stem(helper) + ": " + why, expected);
            }
            programs.addAll(called.result().programs());
        }
        return execute(module, program, source, config, tests, programs);
    }

    private SuiteOutcome execute(String module, String program, Path source, SuiteManifest.Program config,
                                 List<String> tests, List<CobolCompiler.Compiled> programs) {
        CodePage codePage = codePage(config);
        Path directory;
        try {
            directory = Files.createTempDirectory("zcvs-" + program + "-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            Generated loader = new Generated();
            Class<?> first = null;
            ProgramSignature signature = null;
            try {
                for (CobolCompiler.Compiled compiled : programs) {
                    Class<?> type = loader.define(compiled.className(), compiled.classFile());
                    if (first == null) {
                        first = type;
                        signature = compiled.programSignature();
                    }
                }
            } catch (RuntimeException | LinkageError thrown) {
                return SuiteOutcome.simple(module, program, config.expect(), SuiteOutcome.Status.CRASHED,
                        describe(thrown), tests.size());
            }
            Supplier<String> input = input(source);
            ProgramContext context = ProgramContext.standard()
                    .withOutput(OutputStream.nullOutputStream())
                    .withInput(input)
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
    private SuiteOutcome judged(String module, String program, SuiteManifest.Program config, List<String> tests,
                                String report, Throwable thrown, int returnCode) {
        Set<String> passed = new LinkedHashSet<>();
        Set<String> failed = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        Matcher line = ASSERTION.matcher(report);
        while (line.find()) {
            String name = line.group(2);
            if (line.group(1).startsWith("PASS")) {
                passed.add(name);
            } else {
                failed.add(name);
                failures.add(name);
            }
        }
        int pass = 0;
        int fail = 0;
        int missing = 0;
        for (String test : tests) {
            if (failed.contains(test)) {
                fail++;
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

    /** 翻訳と実行のコードページ。翻訳には CODEPAGE オプションとして渡す。 */
    private static CodePage codePage(SuiteManifest.Program config) {
        return config.ccsid() < 0 ? CodePages.DEFAULT : CodePages.forName(String.valueOf(config.ccsid()));
    }

    private CobolCompiler compiler(SuiteManifest.Program config) {
        CopyBookResolver copies = new DirectoryCopyBookResolver(suite.resolve("copy"));
        CopyBookResolver alternate = new DirectoryCopyBookResolver(suite.resolve("copyalt"));
        // COPY X OF COPYALT は copyalt の下から引く (ZX103A)。Zeus も同じ名前の置き場を作って流す
        CopyBookResolver resolver = (name, library) -> library != null && library.equalsIgnoreCase("COPYALT")
                ? alternate.resolve(name, null)
                : copies.resolve(name, null);
        CobolCompiler compiler = new CobolCompiler(Preprocessor.with(resolver));
        if (config.ccsid() >= 0) {
            // 翻訳時のコードページは定数の符号化を決める。こちらが受けない CCSID なら翻訳系が断る
            compiler = compiler.withOptions(new CompilerOptions(Map.of("CODEPAGE",
                    String.valueOf(config.ccsid()))));
        }
        return compiler;
    }

    /** 翻訳の結末。時間切れと例外を結果から分けて持つ。 */
    private record Compiled(CobolCompiler.Result result, String crash, boolean timedOut) {
    }

    private Compiled compile(CobolCompiler compiler, Path source) {
        String text = read(source);
        CobolCompiler.Result[] holder = new CobolCompiler.Result[1];
        Throwable thrown = awaited(stem(source), () -> {
            holder[0] = compiler.compile(source.getFileName().toString(), text);
            return null;
        });
        if (thrown == TIMED_OUT) {
            return new Compiled(null, null, true);
        }
        if (thrown != null) {
            return new Compiled(null, describe(thrown), false);
        }
        return new Compiled(holder[0], null, false);
    }

    /**
     * PARM を主プログラムへ渡す。ジョブの実行 ({@code JobRunner}、P-090) と同じく、連絡節の 1 つ目が宣言した長さまで
     * 領域を取り、頭に長さの半語と PARM を置く。USING を書かないプログラムには何も渡さない。
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
                if (text.contains("END OF TEST-")) {
                    return text;
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 最初の診断。止まった原文の行を持つ (CLAUDE.md §4)。 */
    private static String firstDiagnostic(CobolCompiler.Result result) {
        return result.diagnostics().stream()
                .filter(diagnostic -> !diagnostic.isWarning())
                .findFirst()
                .map(Object::toString)
                .orElse("(no diagnostic)");
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
        }, "zcvs-" + name, 256L * 1024 * 1024);
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
            super(CobolSuiteRunner.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static List<Path> sources(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().toUpperCase(Locale.ROOT).endsWith(".CBL"))
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
