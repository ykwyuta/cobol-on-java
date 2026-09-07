package dev.cobolonjava.verify;

import dev.cobolonjava.verify.ccvs85.Ccvs85Suite;
import dev.cobolonjava.verify.ccvs85.Population;
import dev.cobolonjava.verify.ccvs85.XCards;
import dev.cobolonjava.verify.corpus.CorpusReport;
import dev.cobolonjava.verify.corpus.CorpusRunner;
import dev.cobolonjava.verify.corpus.SourceDirectory;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 検証基盤の起動口 (要件 NFR-040, NFR-042)。
 *
 * <pre>
 * verify ccvs85 &lt;newcob.val&gt; [-x 差し込み札] [-o 出力先]
 * verify corpus &lt;置き場&gt;      [-o 出力先]
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
            System.err.println("usage: verify ccvs85 <newcob.val> [-x x-cards] [-o out]");
            System.err.println("       verify corpus <directory> [-o out]");
            System.exit(2);
            return;
        }
        Path written = option(args, "-o");
        String text = switch (args[0]) {
            case "ccvs85" -> ccvs85(Path.of(args[1]), option(args, "-x"));
            case "corpus" -> corpus(Path.of(args[1]));
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

    /** 資産の置き場を流す。 */
    private static String corpus(Path root) {
        if (!Files.isDirectory(root)) {
            System.err.println("置き場が無い: " + root);
            System.err.println("tools/verify/fetch-corpus.sh で取ってくること");
            System.exit(1);
        }
        List<CorpusRunner.Source> sources = SourceDirectory.read(root);
        CorpusReport report = CorpusRunner.standard().run(sources);
        return report.text("OSS コーパス (要件 NFR-042)") + '\n' + report.csv();
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
