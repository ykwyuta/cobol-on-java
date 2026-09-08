package dev.cobolonjava.verify.execute;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.compiler.source.Preprocessor;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.verify.corpus.CorpusRunner;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 検査プログラムを<b>翻訳して動かし</b>、自分で書いた報告を読む (暫定判断 P-062)。
 *
 * <p>これまでの {@link CorpusRunner} は翻訳が通るかだけを見ていた。通る本数が増えたので、
 * 動かす段を足した。CCVS85 の検査プログラムは<b>自分で答え合わせをして印字する</b>ので、
 * その紙を読めば「規格どおりに動くか」まで測れる。
 *
 * <h2>壊れても止まらない</h2>
 * <p>1 本が例外で落ちても、返ってこなくても、そこで止めずに次へ進む。
 * <b>検査の道具は、被験体の壊れ方に付き合って落ちてはならない</b>。
 * その代わり、落ちたことは結末として数える。
 *
 * <h2>紙は名前で探さない</h2>
 * <p>報告をどのファイルへ書くかは差し込み札 (X-card) が決めており、プログラムごとに
 * 違いうる。だから作業場所に書かれたファイルを全部見て、<b>報告の形をしているもの</b>
 * を探す。名前を決め打ちにすると、札を書き換えたときに黙って測れなくなる。
 */
public final class ProgramRunner {

    /**
     * 1 本を動かしてよい時間 (秒)。
     *
     * <p>翻訳と同じく、動かすほうも必ず終わらなければならない。終わらないものは
     * <b>数え上げの対象ではなく不具合</b>である。待ち切って先へ進まないと、
     * 1 本の無限ループが残り全部の測定を奪う。
     */
    public static final long LIMIT_SECONDS = 60;

    private final CobolCompiler compiler;
    private final long limitSeconds;

    public ProgramRunner(CobolCompiler compiler, long limitSeconds) {
        this.compiler = compiler;
        this.limitSeconds = limitSeconds;
    }

    /** 写し句を引ける処理系で流す。CCVS85 は写し句を使うモジュールがある。 */
    public static ProgramRunner with(CopyBookResolver resolver) {
        return new ProgramRunner(new CobolCompiler(Preprocessor.with(resolver)), LIMIT_SECONDS);
    }

    /** 時間の限りを変えた同じ道具。 */
    public ProgramRunner withLimit(long seconds) {
        return new ProgramRunner(compiler, seconds);
    }

    /** 翻訳したクラスを入れる読み込み器。1 本につき 1 つ作る。 */
    private static final class Generated extends ClassLoader {

        private Generated() {
            super(ProgramRunner.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    /** 1 本を翻訳して動かす。 */
    public RunOutcome run(CorpusRunner.Source source) {
        return run(source, List.of());
    }

    /**
     * 1 本を、それが呼ぶ副プログラムと一緒に翻訳して動かす。
     *
     * <p>CCVS85 は<b>呼ぶ側と呼ばれる側を別々の部品に置いている</b>
     * ({@code *HEADER,COBOL,IC101A,SUBRTN,IC102A})。別々に翻訳して別々に動かすと、
     * 呼ぶ側は「呼び先が無い」で落ち、呼ばれる側は「引数が渡されていない」で落ちる。
     * どちらも<b>道具が作った失敗</b>であって、処理系の失敗ではない。
     *
     * @param called 呼ばれる側の部品。同じ読み込み器へ入れる
     */
    public RunOutcome run(CorpusRunner.Source source, List<CorpusRunner.Source> called) {
        List<CobolCompiler.Result> compiled = new ArrayList<>();
        for (CorpusRunner.Source part : join(source, called)) {
            CobolCompiler.Result result;
            try {
                result = compiler.compile(part.name(), part.text());
            } catch (RuntimeException | StackOverflowError | AssertionError thrown) {
                return RunOutcome.notCompiled(source.name(), source.group(),
                        thrown.getClass().getSimpleName());
            }
            if (!result.succeeded()) {
                return RunOutcome.notCompiled(source.name(), source.group(),
                        result.diagnostics().isEmpty()
                                ? "(no diagnostic)"
                                : result.diagnostics().get(0).toString());
            }
            compiled.add(result);
        }
        if (!TestReport.isSelfChecking(source.text())) {
            // 翻訳の診断を見るための検査である。報告を書く仕掛けを持っていない
            return RunOutcome.compileOnly(source.name(), source.group());
        }
        Path directory;
        try {
            directory = Files.createTempDirectory("ccvs85-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            return execute(source, compiled, directory);
        } finally {
            delete(directory);
        }
    }

    private static List<CorpusRunner.Source> join(CorpusRunner.Source first,
                                                  List<CorpusRunner.Source> rest) {
        List<CorpusRunner.Source> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private RunOutcome execute(CorpusRunner.Source source, List<CobolCompiler.Result> compiled,
                               Path directory) {
        Class<?> first;
        try {
            // 呼ぶ側も呼ばれる側も同じ読み込み器へ入れる。CALL は読み込み器から引く
            Generated loader = new Generated();
            Class<?> found = null;
            for (CobolCompiler.Result result : compiled) {
                for (CobolCompiler.Compiled program : result.programs()) {
                    Class<?> type = loader.define(program.className(), program.classFile());
                    if (found == null) {
                        // 最初の部品の 1 本目が主プログラムである
                        found = type;
                    }
                }
            }
            first = found;
        } catch (RuntimeException | LinkageError thrown) {
            return RunOutcome.crashed(source.name(), source.group(), describe(thrown));
        }
        if (first == null) {
            return RunOutcome.crashed(source.name(), source.group(), "no program was generated");
        }
        Throwable thrown = awaited(source, first, directory);
        if (thrown == TIMED_OUT) {
            return RunOutcome.timedOut(source.name(), source.group(), limitSeconds);
        }
        String report = reportIn(directory);
        if (report == null) {
            return RunOutcome.crashed(source.name(), source.group(),
                    thrown == null ? "the program wrote no report" : describe(thrown));
        }
        // 報告が書けていれば、途中で落ちていても読み取れたところまでを数える
        return RunOutcome.reported(source.name(), source.group(),
                TestReport.executed(report).orElse(0), TestReport.total(report).orElse(0),
                TestReport.failed(report), TestReport.deleted(report),
                TestReport.inspected(report));
    }

    /** 時間切れを表す番人。例外そのものではないので、取り違えようがない。 */
    private static final Throwable TIMED_OUT = new Throwable("timed out");

    /**
     * 別の走脈で動かし、時間の限りまで待つ。
     *
     * @return 外へ出た例外、無ければ {@code null}、時間切れなら {@link #TIMED_OUT}
     */
    private Throwable awaited(CorpusRunner.Source source, Class<?> program, Path directory) {
        BlockingQueue<Object> done = new ArrayBlockingQueue<>(1);
        Object empty = new Object();
        Thread worker = new Thread(() -> {
            Object outcome = empty;
            try {
                // DISPLAY の行は捨てる。合否は紙のほうに書かれており、
                // 道具の出力に混ぜると数のほうが読めなくなる
                ProgramContext context = ProgramContext.standard()
                        .withOutput(OutputStream.nullOutputStream())
                        .withCatalog(new DataSetCatalog(directory));
                ((CobolProgram) program.getDeclaredConstructor().newInstance())
                        .runFresh(context);
            } catch (Throwable caught) {
                outcome = caught;
            }
            done.offer(outcome);
        }, "run-" + source.name());
        // 見捨てる走脈が道具全体を道連れにしないよう、番人にはしない
        worker.setDaemon(true);
        worker.start();
        try {
            Object outcome = done.poll(limitSeconds, TimeUnit.SECONDS);
            if (outcome == null) {
                // 止まらないプログラムは割り込みでは止まらない。見捨てて次へ進む
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

    /** 作業場所に書かれたファイルのうち、報告の形をしているもの。 */
    private static String reportIn(Path directory) {
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String text = CodePages.DEFAULT.decode(Files.readAllBytes(file));
                if (TestReport.isComplete(text)) {
                    return text;
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String describe(Throwable thrown) {
        return thrown.getClass().getSimpleName()
                + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage());
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

    /**
     * 束を流して数える。
     *
     * <p>副プログラムの部品は<b>それ自身を検査として数えない</b>。呼ばれる側だけを
     * 動かしても、引数が渡されていないので落ちるに決まっている。呼ぶ側と一緒に
     * 翻訳して、呼ぶ側 1 本として数える。
     */
    public ExecutionReport run(List<CorpusRunner.Source> sources) {
        Map<String, List<CorpusRunner.Source>> parts = new LinkedHashMap<>();
        for (CorpusRunner.Source source : sources) {
            parts.computeIfAbsent(testOf(source.name()), k -> new ArrayList<>()).add(source);
        }
        List<RunOutcome> outcomes = new ArrayList<>();
        parts.forEach((test, group) -> {
            CorpusRunner.Source main = mainOf(group, test);
            List<CorpusRunner.Source> called = new ArrayList<>(group);
            called.remove(main);
            outcomes.add(run(main, called));
        });
        return new ExecutionReport(List.copyOf(outcomes));
    }

    /**
     * その部品が属する検査の名前。
     *
     * <p>配布物は {@code *HEADER,COBOL,IC101A,SUBRTN,IC102A} のように、
     * <b>呼ぶ側の名前を頭に置いて</b>副プログラムを別の部品にしている。
     * 最初のコンマまでが検査の名前である。
     */
    private static String testOf(String name) {
        String bare = name.endsWith(".cbl") ? name.substring(0, name.length() - 4) : name;
        int comma = bare.indexOf(',');
        return comma < 0 ? bare : bare.substring(0, comma);
    }

    /** 呼ぶ側の部品。名前にコンマが無いものである。 */
    private static CorpusRunner.Source mainOf(List<CorpusRunner.Source> group, String test) {
        for (CorpusRunner.Source source : group) {
            if (source.name().equals(test + ".cbl")) {
                return source;
            }
        }
        // 呼ぶ側が流せなかった (差し込み札が足りない等)。先頭を主とする
        return group.get(0);
    }
}
