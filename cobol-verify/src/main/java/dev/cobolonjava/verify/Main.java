package dev.cobolonjava.verify;

import dev.cobolonjava.verify.ccvs85.Ccvs85Suite;
import dev.cobolonjava.verify.ccvs85.Population;
import dev.cobolonjava.verify.ccvs85.XCards;
import dev.cobolonjava.verify.corpus.CorpusReport;
import dev.cobolonjava.verify.corpus.CorpusRunner;
import dev.cobolonjava.verify.corpus.SourceDirectory;
import dev.cobolonjava.verify.execute.ExecutionReport;
import dev.cobolonjava.verify.execute.ProgramRunner;
import dev.cobolonjava.compiler.source.BmsCopyBookResolver;
import dev.cobolonjava.compiler.source.CicsSystemCopyBookResolver;
import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.compiler.source.DirectoryCopyBookResolver;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 検証基盤の起動口 (要件 NFR-040, NFR-042)。
 *
 * <pre>
 * verify ccvs85     &lt;newcob.val&gt; [-x 差し込み札] [-o 出力先]   翻訳が通るかを数える
 * verify ccvs85-run &lt;newcob.val&gt; [-x 差し込み札] [-o 出力先]   動かして合否を数える
 * verify corpus     &lt;置き場&gt;      [-I 写し句の置き場]... [-o 出力先]
 * </pre>
 *
 * <p>どちらも<b>数だけ</b>を出す。コーパスの中身は出さないし、同梱もしない
 * (要件 NFR-042)。取ってくるのは {@code tools/verify/} の取得スクリプトである。
 *
 * <p>復帰コードは、流せたかどうかで決める。<b>合格率が低いことは失敗ではない</b>。
 * 育っている途中の処理系に対して数を取るのがこの道具の仕事であり、数が低いから止まる
 * のでは継続して測れない。止まるのは配布物が読めなかったときだけである。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        // 覚え書きは日本語である。土地の既定に任せると文字化けする
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.setOut(out);
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        if (args.length < 2) {
            System.err.println("usage: verify ccvs85     <newcob.val> [-x x-cards] [-o out]");
            System.err.println("       verify ccvs85-run <newcob.val> [-x x-cards] [-o out]");
            System.err.println("       verify corpus     <directory> [-I copybooks]... [-o out]");
            System.err.println("       verify ims-gen    <directory> [-o out]");
            System.err.println("       verify ims-mpp    <base> -d classes -p program -s psb -m messages [-o out]");
            System.exit(2);
            return;
        }
        Path written = option(args, "-o");
        String text = switch (args[0]) {
            case "ccvs85" -> ccvs85(Path.of(args[1]), option(args, "-x"));
            case "ccvs85-run" -> ccvs85Run(Path.of(args[1]), option(args, "-x"));
            case "corpus" -> corpus(Path.of(args[1]), options(args, "-I"));
            case "ims-gen" -> imsGeneration(Path.of(args[1]));
            case "ims-mpp" -> dev.cobolonjava.verify.ims.ImsMessageRunner.run(Path.of(args[1]),
                    option(args, "-d"), word(args, "-p"), word(args, "-s"), option(args, "-m"));
            default -> null;
        };
        if (text == null) {
            System.err.println("unknown command: " + args[0]);
            System.exit(2);
            return;
        }
        out.print(text);
        if (written != null) {
            Files.writeString(written, text, StandardCharsets.UTF_8);
        }
    }

    /** CCVS85 を流す。 */
    private static String ccvs85(Path archive, Path cards) {
        if (!Files.isReadable(archive)) {
            System.err.println("CCVS85 の配布物が読めない: " + archive);
            System.err.println("tools/verify/fetch-ccvs85.sh で取ってくること");
            System.exit(1);
        }
        XCards xcards = cards == null ? XCards.defaults() : XCards.defaults().and(cards);
        Ccvs85Suite.Prepared prepared = Ccvs85Suite.prepare(archive, Population.plain(xcards));
        CorpusReport report = CorpusRunner.with(prepared.resolver()).run(prepared.sources());

        StringBuilder body = new StringBuilder();
        body.append(report.text("CCVS85 全体"));
        body.append('\n');
        body.append(report.only(Ccvs85Suite.NON_IO)
                .text("CCVS85 入出力以外 (要件 13 章の受け入れ基準)"));
        if (!prepared.skipped().isEmpty()) {
            body.append("\n差し込み札が足りず流さなかったもの\n");
            for (Map.Entry<String, List<Integer>> skipped : prepared.skipped().entrySet()) {
                body.append(String.format("  %-10s X-card %s%n", skipped.getKey(),
                        skipped.getValue()));
            }
            body.append("  → cobol-verify の x-cards.properties に足すこと。"
                    + "札が無いまま流すと、処理系の失敗を道具が作ることになる\n");
        }
        return body.toString();
    }

    /**
     * CCVS85 を<b>動かして</b>合否を数える (暫定判断 P-062)。
     *
     * <p>検査プログラムは自分で答え合わせをして印字する。その紙を読めば、
     * 翻訳が通るかではなく<b>規格どおりに動くか</b>まで測れる。
     */
    private static String ccvs85Run(Path archive, Path cards) {
        Ccvs85Suite.Prepared prepared = prepare(archive, cards);
        ExecutionReport report = ProgramRunner.with(prepared.resolver())
                .run(prepared.sources());

        StringBuilder body = new StringBuilder();
        body.append(report.text("CCVS85 を動かした結果"));
        body.append('\n');
        body.append(report.only(Ccvs85Suite.NON_IO)
                .text("CCVS85 入出力以外 (要件 13 章の受け入れ基準)"));
        return body.toString();
    }

    private static Ccvs85Suite.Prepared prepare(Path archive, Path cards) {
        if (!Files.isReadable(archive)) {
            System.err.println("CCVS85 の配布物が読めない: " + archive);
            System.err.println("tools/verify/fetch-ccvs85.sh で取ってくること");
            System.exit(1);
        }
        XCards xcards = cards == null ? XCards.defaults() : XCards.defaults().and(cards);
        return Ccvs85Suite.prepare(archive, Population.plain(xcards));
    }

    /**
     * 資産の置き場を流す。
     *
     * <p>{@code -I} は何度でも書ける。CICS の資産は業務の写し句と、BMS から作る記号マップの
     * 写し句を別の場所に持つ。写し句が引けないまま流すと、<b>処理系の失敗を道具が作る</b>
     * (覚え書き 6)。
     */
    private static String corpus(Path root, List<Path> includes) {
        if (!Files.isDirectory(root)) {
            System.err.println("置き場が無い: " + root);
            System.err.println("tools/verify/fetch-corpus.sh で取ってくること");
            System.exit(1);
        }
        List<CorpusRunner.Source> sources = SourceDirectory.read(root);
        CorpusRunner runner = includes.isEmpty()
                ? CorpusRunner.standard()
                : CorpusRunner.with(resolverOf(includes));
        CorpusReport report = runner.run(sources);
        return report.text("OSS コーパス (要件 NFR-042)") + '\n' + report.csv();
    }

    /** IMS の DBDGEN / PSBGEN の原文を読めるか数える (設計 78)。 */
    private static String imsGeneration(Path root) {
        if (!Files.isDirectory(root)) {
            System.err.println("置き場が無い: " + root);
            System.exit(1);
        }
        return dev.cobolonjava.verify.ims.ImsGenerationRunner.text(
                dev.cobolonjava.verify.ims.ImsGenerationRunner.run(root));
    }

    /**
     * 書かれた順に探す。先に見つかったものを使うのは、ホストの連結ライブラリと同じである。
     *
     * <p>同じ置き場に BMS の原文があれば、記号マップの写し句をその場で作る。
     */
    private static CopyBookResolver resolverOf(List<Path> includes) {
        List<CopyBookResolver> chain = java.util.stream.Stream.concat(
                includes.stream().<CopyBookResolver>mapMulti((include, sink) -> {
                    sink.accept(new DirectoryCopyBookResolver(include));
                    sink.accept(new BmsCopyBookResolver(include));
                }),
                // CICS 提供の写し句は最後に引く。資産が自前のものを置いていればそちらを使う
                java.util.stream.Stream.of(new CicsSystemCopyBookResolver(),
                        new dev.cobolonjava.compiler.source.Db2SystemCopyBookResolver(),
                        new dev.cobolonjava.compiler.source.LanguageEnvironmentCopyBookResolver()))
                .toList();
        return (textName, libraryName) -> chain.stream()
                .map(resolver -> resolver.resolve(textName, libraryName))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /** 語の指定を読む。書かれていなければ止める。 */
    private static String word(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        System.err.println(name + " is required");
        System.exit(2);
        return null;
    }

    /** 同じ指定を何度でも読む。 */
    private static List<Path> options(String[] args, String name) {
        List<Path> out = new ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                out.add(Path.of(args[i + 1]));
            }
        }
        return List.copyOf(out);
    }

    /**
     * 起動時の指定を読む。
     *
     * @return 書かれていなければ {@code null}
     */
    private static Path option(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return Path.of(args[i + 1]);
            }
        }
        return null;
    }
}
