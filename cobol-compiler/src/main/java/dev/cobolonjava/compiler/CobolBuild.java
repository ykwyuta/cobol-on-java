package dev.cobolonjava.compiler;

import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.BmsCopyBookResolver;
import dev.cobolonjava.compiler.source.CicsSystemCopyBookResolver;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.compiler.source.CopyBookResolver;
import dev.cobolonjava.compiler.source.Db2SystemCopyBookResolver;
import dev.cobolonjava.compiler.source.DirectoryCopyBookResolver;
import dev.cobolonjava.compiler.source.FixedFormatReader;
import dev.cobolonjava.compiler.source.FreeFormatReader;
import dev.cobolonjava.compiler.source.LanguageEnvironmentCopyBookResolver;
import dev.cobolonjava.compiler.source.Preprocessor;
import dev.cobolonjava.runtime.interop.DeployCatalogManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * ソースの並びを翻訳し、クラスファイルと配備カタログを 1 つの置き場へ書き出す。
 *
 * <p>コマンドラインの {@link Main} と Maven プラグインが同じ手順を踏むための入口である。
 * どちらから翻訳しても<b>同じ置き場に同じものが出る</b>ことを、手順を 1 か所に置いて保つ。
 * プロセスを終わらせる判断 ({@code System.exit}) は呼ぶ側に任せる。
 */
public final class CobolBuild {

    private CobolBuild() {
    }

    /**
     * 翻訳の指定。
     *
     * @param copybooks 写し句の置き場。<b>書いた順に探す</b> (ホストの連結ライブラリと同じ)。
     *                  同じ置き場の BMS の原文からは記号マップの写し句を作る (要件 FR-162)
     */
    public record Request(List<Path> sources, Path output, List<Path> copybooks,
                          boolean freeFormat, CompilerOptions compilerOptions) {

        public Request {
            sources = List.copyOf(sources);
            Objects.requireNonNull(output, "output");
            copybooks = List.copyOf(copybooks);
            Objects.requireNonNull(compilerOptions, "compilerOptions");
        }
    }

    /** 翻訳の結末。{@code failedSources} は翻訳できなかったソースの並び。 */
    public record Result(List<Path> failedSources, List<String> programIds) {

        public Result {
            failedSources = List.copyOf(failedSources);
            programIds = List.copyOf(programIds);
        }

        public boolean succeeded() {
            return failedSources.isEmpty();
        }
    }

    /**
     * すべてのソースを翻訳する。1 本が失敗しても残りは翻訳し、診断をすべて出す。
     *
     * @param diagnostics ソースと診断を受け取る。警告も渡す
     */
    public static Result run(Request request, BiConsumer<Path, Diagnostic> diagnostics)
            throws IOException {
        Preprocessor preprocessor = new Preprocessor(resolver(request.copybooks()),
                request.freeFormat() ? FreeFormatReader.standard() : FixedFormatReader.standard());
        List<Path> failed = new ArrayList<>();
        List<CobolCompiler.Compiled> deployed = new ArrayList<>();
        for (Path source : request.sources()) {
            CobolCompiler.Result result = new CobolCompiler(preprocessor,
                    request.compilerOptions())
                    .compile(source.getFileName().toString(),
                            Files.readString(source, StandardCharsets.UTF_8));
            for (Diagnostic diagnostic : result.diagnostics()) {
                diagnostics.accept(source, diagnostic);
            }
            if (!result.succeeded()) {
                failed.add(source);
                continue;
            }
            // 1 本のソースにプログラムが何本あってもよい。その数だけクラスを出す
            for (CobolCompiler.Compiled program : result.programs()) {
                Path target = request.output()
                        .resolve(program.className().replace('.', '/') + ".class");
                Files.createDirectories(target.getParent());
                Files.write(target, program.classFile());
                deployed.add(program);
            }
        }
        // 全件失敗でも空catalogを書き、以前の成功ビルドのcatalogを残さない。
        Path catalog = request.output().resolve(DeployCatalogManifest.RESOURCE_NAME);
        Files.createDirectories(catalog.getParent());
        Files.writeString(catalog, DeployCatalogGenerator.generate(deployed).toJson(),
                StandardCharsets.UTF_8);
        return new Result(failed, deployed.stream()
                .map(program -> program.programSignature().programId().value())
                .toList());
    }

    /**
     * 書かれた順に探し、先に見つかったものを使う。ホストの連結ライブラリと同じである。
     *
     * <p>CICS・Db2・Language Environment が配る写し句は<b>最後に</b>引く。資産が自前の
     * {@code DFHAID} や {@code SQLCA} を置いていれば、そちらを使う。
     */
    private static CopyBookResolver resolver(List<Path> copybooks) {
        List<CopyBookResolver> chain = new ArrayList<>();
        for (Path directory : copybooks) {
            chain.add(new DirectoryCopyBookResolver(directory));
            chain.add(new BmsCopyBookResolver(directory));
        }
        chain.add(new CicsSystemCopyBookResolver());
        chain.add(new Db2SystemCopyBookResolver());
        chain.add(new LanguageEnvironmentCopyBookResolver());
        List<CopyBookResolver> fixed = List.copyOf(chain);
        return (textName, libraryName) -> fixed.stream()
                .map(resolver -> resolver.resolve(textName, libraryName))
                .flatMap(Optional::stream)
                .findFirst();
    }
}
