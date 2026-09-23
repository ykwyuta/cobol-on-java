package dev.cobolonjava.compiler;

import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.compiler.source.ProcessStatement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 処理系の起動口 (要件 FR-180)。
 *
 * <pre>
 * cobolc [-d 出力ディレクトリ] [-I コピー句ディレクトリ]... [--free] [-q オプション] ソース...
 * </pre>
 *
 * <p>翻訳したクラスファイルと {@code META-INF/cobol/programs.json} を書き出す。
 * 生成したクラスは {@code main} を持つので、そのまま {@code java} で起動できる。
 *
 * <p>{@code -I} は何度でも書ける。書いた順に探し、先に見つかったものを使う。ホストの
 * 連結ライブラリと同じ規則である。同じ置き場に BMS の原文 ({@code 名前.bms}) があれば、
 * 記号マップの写し句をその場で作る (要件 FR-162)。
 *
 * <p>{@code -q} には翻訳時オプションを {@code CBL} 文と同じ綴りで書く
 * ({@code -q SSRANGE,ARITH(EXTEND)})。ソースに書かれた {@code CBL} / {@code PROCESS} の
 * 指定のほうが<b>あとに重なる</b>。
 *
 * <p>翻訳の手順そのものは {@link CobolBuild} にある。Maven プラグインも同じものを呼ぶ。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: cobolc [-d dir] [-I copybook-dir]... [--free]"
                    + " [-q options] source...");
            System.exit(2);
            return;
        }

        CobolBuild.Result result = CobolBuild.run(new CobolBuild.Request(options.sources(),
                        options.output(), options.copybooks(), options.freeFormat(),
                        options.compilerOptions()),
                (source, diagnostic) -> System.err.println(diagnostic));
        if (!result.succeeded()) {
            System.exit(1);
        }
    }

    /** 起動時の指定。 */
    private record Options(List<Path> sources, Path output, List<Path> copybooks,
                           boolean freeFormat, CompilerOptions compilerOptions) {

        static Options parse(String[] args) {
            List<Path> sources = new ArrayList<>();
            Path output = Path.of(".");
            List<Path> copybooks = new ArrayList<>();
            boolean freeFormat = false;
            CompilerOptions compilerOptions = CompilerOptions.NONE;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-d" -> output = Path.of(next(args, ++i, "-d"));
                    case "-I" -> copybooks.add(Path.of(next(args, ++i, "-I")));
                    case "--free" -> freeFormat = true;
                    case "-q" -> compilerOptions = compilerOptions.merge(
                            ProcessStatement.parse(next(args, ++i, "-q")));
                    default -> {
                        if (args[i].startsWith("-")) {
                            throw new IllegalArgumentException("unknown option: " + args[i]);
                        }
                        sources.add(Path.of(args[i]));
                    }
                }
            }
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("no source file given");
            }
            return new Options(List.copyOf(sources), output, List.copyOf(copybooks), freeFormat,
                    compilerOptions);
        }

        private static String next(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}
