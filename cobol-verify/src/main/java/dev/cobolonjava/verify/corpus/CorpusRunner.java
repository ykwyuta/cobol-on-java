package dev.cobolonjava.verify.corpus;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.compiler.source.Preprocessor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * ソースの束を翻訳にかけて、通ったかどうかを数える (要件 NFR-042)。
 *
 * <p>実行はしない。<b>受理できるか</b>だけを見る道具である。要件 NFR-042 が言う
 * 「構文網羅率」はこれであり、狙いは<b>実装漏れによる翻訳失敗を早く見つける</b>ことである。
 *
 * <h2>壊れても止まらない</h2>
 * <p>1 本が例外で落ちても、そこで止めずに次へ進む。500 本流して 1 本目で止まったら、
 * <b>残りの 499 本について何も分からない</b>。検査の道具は、被験体の壊れ方に付き合って
 * 落ちてはならない。
 *
 * <p>その代わり、落ちたことは {@link CompileOutcome.Status#CRASHED} として数える。
 * 握り潰すのとは違う。
 */
public final class CorpusRunner {

    /**
     * 翻訳にかける道。
     *
     * <p>{@link CobolCompiler} を直に持たずに挟んであるのは、<b>この道具自身を試せる</b>
     * ようにするためである。壊れたときの数え方を確かめるのに、処理系の不具合を当てにする
     * わけにはいかない。不具合は直るからである。
     */
    @FunctionalInterface
    public interface Compilation {

        CobolCompiler.Result compile(String fileName, String source);
    }

    /**
     * 1 本にかけてよい時間 (秒)。
     *
     * <p>翻訳は必ず終わらなければならない。終わらないものがあれば、それは<b>数え上げの
     * 対象ではなく不具合</b>である。ここで待ち切って先へ進まないと、1 本の無限ループが
     * 残り全部の測定を奪う。実際に、手続き部の文法が曖昧だったころ CCVS85 の大きな
     * プログラム 1 本が返らず、測定が丸ごと止まった。
     */
    public static final long LIMIT_SECONDS = 60;

    private final Compilation compilation;
    private final long limitSeconds;

    public CorpusRunner(Compilation compilation) {
        this(compilation, LIMIT_SECONDS);
    }

    public CorpusRunner(Compilation compilation, long limitSeconds) {
        this.compilation = compilation;
        this.limitSeconds = limitSeconds;
    }

    /** 時間の限りを変えた同じ道具。 */
    public CorpusRunner withLimit(long seconds) {
        return new CorpusRunner(compilation, seconds);
    }

    /** 写し句を持たない、素の処理系で流す。 */
    public static CorpusRunner standard() {
        return new CorpusRunner(CobolCompiler.standard()::compile);
    }

    /** 写し句を引ける処理系で流す。CCVS85 は写し句を使うモジュールがある。 */
    public static CorpusRunner with(CopyBookResolver resolver) {
        return new CorpusRunner(new CobolCompiler(Preprocessor.with(resolver))::compile);
    }

    /**
     * 翻訳にかける 1 本。
     *
     * @param group 数え上げの区分。CCVS85 なら検査モジュール、資産なら持ち主
     */
    public record Source(String name, String group, String text) {
    }

    /** 1 本を翻訳にかける。時間の限りを超えたら待つのをやめる。 */
    public CompileOutcome run(Source source) {
        Object outcome = awaited(source);
        if (outcome == null) {
            return CompileOutcome.timedOut(source.name(), source.group(), limitSeconds);
        }
        if (outcome instanceof Throwable thrown) {
            // 診断を出す道を通らずに外へ出た。これは処理系の欠陥である
            return CompileOutcome.crashed(source.name(), source.group(), thrown);
        }
        CobolCompiler.Result result = (CobolCompiler.Result) outcome;
        if (result.succeeded()) {
            return CompileOutcome.compiled(source.name(), source.group());
        }
        List<String> diagnostics = new ArrayList<>();
        for (Diagnostic diagnostic : result.diagnostics()) {
            diagnostics.add(diagnostic.toString());
        }
        return CompileOutcome.rejected(source.name(), source.group(), diagnostics);
    }

    /**
     * 別の走脈で翻訳し、時間の限りまで待つ。
     *
     * @return 翻訳の結果、外へ出た例外、または時間切れなら {@code null}
     */
    private Object awaited(Source source) {
        BlockingQueue<Object> done = new ArrayBlockingQueue<>(1);
        Thread worker = new Thread(() -> {
            Object outcome;
            try {
                outcome = compilation.compile(source.name(), source.text());
            } catch (RuntimeException | StackOverflowError | AssertionError thrown) {
                outcome = thrown;
            }
            done.offer(outcome);
        }, "compile-" + source.name());
        // 見捨てる走脈が処理系全体を道連れにしないよう、番人にはしない
        worker.setDaemon(true);
        worker.start();
        try {
            Object outcome = done.poll(limitSeconds, TimeUnit.SECONDS);
            if (outcome == null) {
                // 止まらない翻訳は割り込みでは止まらない。見捨てて次へ進む
                worker.interrupt();
            }
            return outcome;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            worker.interrupt();
            return null;
        }
    }

    /** 束を流して数える。 */
    public CorpusReport run(List<Source> sources) {
        List<CompileOutcome> outcomes = new ArrayList<>();
        for (Source source : sources) {
            outcomes.add(run(source));
        }
        return new CorpusReport(List.copyOf(outcomes));
    }
}
