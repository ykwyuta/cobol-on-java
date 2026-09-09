package dev.cobolonjava.compiler;

import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.compiler.source.DirectoryCopyBookResolver;
import dev.cobolonjava.compiler.source.FreeFormatReader;
import dev.cobolonjava.compiler.source.Preprocessor;
import dev.cobolonjava.compiler.source.ProcessStatement;
import dev.cobolonjava.runtime.interop.DeployCatalogManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 処理系の起動口 (要件 FR-180)。
 *
 * <pre>
 * cobolc [-d 出力ディレクトリ] [-I コピー句ディレクトリ] [--free] [-q オプション] ソース...
 * </pre>
 *
 * <p>翻訳したクラスファイルと {@code META-INF/cobol/programs.json} を書き出す。
 * 生成したクラスは {@code main} を持つので、そのまま {@code java} で起動できる。
 *
 * <p>{@code -q} には翻訳時オプションを {@code CBL} 文と同じ綴りで書く
 * ({@code -q SSRANGE,ARITH(EXTEND)})。ソースに書かれた {@code CBL} / {@code PROCESS} の
 * 指定のほうが<b>あとに重なる</b>。
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
            System.err.println("usage: cobolc [-d dir] [-I copybook-dir] [--free]"
                    + " [-q options] source...");
            System.exit(2);
            return;
        }

        int failed = 0;
        List<CobolCompiler.Compiled> deployed = new ArrayList<>();
        for (Path source : options.sources()) {
            if (!compile(source, options, deployed)) {
                failed++;
            }
        }
        // 全件失敗でも空catalogを書き、以前の成功ビルドのcatalogを残さない。
        writeDeployCatalog(options.output(), deployed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static boolean compile(Path source, Options options,
                                   List<CobolCompiler.Compiled> deployed) throws IOException {
        Preprocessor preprocessor = options.preprocessor();
        CobolCompiler.Result result = new CobolCompiler(preprocessor, options.compilerOptions())
                .compile(source.getFileName().toString(),
                        Files.readString(source, StandardCharsets.UTF_8));

        for (Diagnostic diagnostic : result.diagnostics()) {
            System.err.println(diagnostic);
        }
        if (!result.succeeded()) {
            return false;
        }

        // 1 本のソースにプログラムが何本あってもよい。その数だけクラスを出す
        for (CobolCompiler.Compiled program : result.programs()) {
            Path target = options.output()
                    .resolve(program.className().replace('.', '/') + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, program.classFile());
            deployed.add(program);
        }
        return true;
    }

    private static void writeDeployCatalog(Path output, List<CobolCompiler.Compiled> programs)
            throws IOException {
        Path target = output.resolve(DeployCatalogManifest.RESOURCE_NAME);
        Files.createDirectories(target.getParent());
        Files.writeString(target, DeployCatalogGenerator.generate(programs).toJson(),
                StandardCharsets.UTF_8);
    }

    /** 起動時の指定。 */
    private record Options(List<Path> sources, Path output, Path copybooks, boolean freeFormat,
                           CompilerOptions compilerOptions) {

        static Options parse(String[] args) {
            List<Path> sources = new ArrayList<>();
            Path output = Path.of(".");
            Path copybooks = null;
            boolean freeFormat = false;
            CompilerOptions compilerOptions = CompilerOptions.NONE;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-d" -> output = Path.of(next(args, ++i, "-d"));
                    case "-I" -> copybooks = Path.of(next(args, ++i, "-I"));
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
            return new Options(List.copyOf(sources), output, copybooks, freeFormat,
                    compilerOptions);
        }

        private static String next(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }

        Preprocessor preprocessor() {
            return new Preprocessor(
                    copybooks == null
                            ? (name, library) -> java.util.Optional.empty()
                            : new DirectoryCopyBookResolver(copybooks),
                    freeFormat ? FreeFormatReader.standard()
                            : dev.cobolonjava.compiler.source.FixedFormatReader.standard());
        }
    }
}
