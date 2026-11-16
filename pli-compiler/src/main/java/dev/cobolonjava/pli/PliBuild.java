package dev.cobolonjava.pli;

import dev.cobolonjava.runtime.interop.CatalogRevision;
import dev.cobolonjava.runtime.interop.DeployCatalogManifest;
import dev.cobolonjava.runtime.interop.GeneratedProgramArtifact;
import dev.cobolonjava.runtime.program.ProgramSupport;
import dev.cobolonjava.source.JavaSource;
import dev.cobolonjava.source.ProgramOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * PL/I ソースの並びを翻訳し、クラスファイルと配備カタログを 1 つの置き場へ書き出す。
 *
 * <p>コマンドラインの {@link Main} と Maven プラグインが同じ手順を踏むための入口である。
 * プロセスを終わらせる判断は呼ぶ側に任せる。
 */
public final class PliBuild {

    /**
     * 生成クラスの package。COBOL と同じであり、配備カタログはこの package のクラスしか載せない。
     */
    public static final String GENERATED_PACKAGE = ProgramSupport.GENERATED_PACKAGE;

    private PliBuild() {
    }

    /**
     * 翻訳の指定。
     *
     * @param includes {@code %INCLUDE} の置き場。<b>書いた順に探す</b>
     */
    public record Request(List<Path> sources, Path output, List<Path> includes) {

        public Request {
            sources = List.copyOf(sources);
            Objects.requireNonNull(output, "output");
            includes = List.copyOf(includes);
        }
    }

    /**
     * 翻訳の結末。{@code failedSources} は翻訳できなかったソースの並び。{@code catalog} は書いた
     * 配備カタログで、ほかの言語のカタログと 1 つにまとめるときに使う。
     */
    public record Result(List<Path> failedSources, List<String> programIds,
                         DeployCatalogManifest catalog) {

        public Result {
            failedSources = List.copyOf(failedSources);
            programIds = List.copyOf(programIds);
        }

        public boolean succeeded() {
            return failedSources.isEmpty();
        }
    }

    /** すべてのソースを翻訳する。1 本が失敗しても残りは翻訳し、診断をすべて出す。 */
    public static Result run(Request request, Consumer<Diagnostic> diagnostics)
            throws IOException {
        return run(request, ProgramOutput.CLASS, diagnostics);
    }

    public static Result run(Request request, ProgramOutput output,
                             Consumer<Diagnostic> diagnostics) throws IOException {
        PliCompiler compiler = new PliCompiler(
                new PliPreprocessor(member -> include(request.includes(), member)));
        List<Path> failed = new ArrayList<>();
        List<BuiltProgram> compiled = new ArrayList<>();
        for (Path source : request.sources()) {
            String fileName = source.getFileName().toString();
            PliCompiler.Translation result = compiler.translate(fileName,
                    Files.readString(source, StandardCharsets.UTF_8));
            result.diagnostics().forEach(diagnostics);
            if (!result.succeeded()) {
                failed.add(source);
                continue;
            }
            byte[] contents;
            if (output == ProgramOutput.JAVA) {
                String java = PliJavaSource.generate(result.className(), fileName, result.source());
                JavaSource.write(request.output(), result.className(), java);
                contents = java.getBytes(StandardCharsets.UTF_8);
            } else {
                contents = result.classFile(fileName);
                output.write(request.output(), result.className(), contents);
            }
            compiled.add(new BuiltProgram(new GeneratedProgramArtifact(
                    result.signature().programId(), result.className(), result.signature(),
                    result.procedures().procedureHash()), contents));
        }
        // 全件失敗でも空catalogを書き、以前の成功ビルドのcatalogを残さない。
        DeployCatalogManifest catalog = writeCatalog(request.output(), compiled);
        return new Result(failed, compiled.stream()
                .map(program -> program.artifact().programId().value())
                .toList(), catalog);
    }

    private record BuiltProgram(GeneratedProgramArtifact artifact, byte[] contents) {
    }

    private static DeployCatalogManifest writeCatalog(Path output, List<BuiltProgram> compiled)
            throws IOException {
        MessageDigest revision = sha256();
        List<GeneratedProgramArtifact> artifacts = new ArrayList<>();
        compiled.stream().sorted(Comparator.comparing(
                program -> program.artifact().programId().value())).forEach(program -> {
                    revision.update(program.contents());
                    artifacts.add(program.artifact());
                });
        DeployCatalogManifest manifest = new DeployCatalogManifest(
                DeployCatalogManifest.CURRENT_FORMAT_VERSION,
                new CatalogRevision("sha256:" + HexFormat.of().formatHex(revision.digest())),
                "pli-compiler", DeployCatalogManifest.CURRENT_RUNTIME_ABI_VERSION,
                GENERATED_PACKAGE, artifacts);
        Path target = output.resolve(DeployCatalogManifest.RESOURCE_NAME);
        Files.createDirectories(target.getParent());
        Files.writeString(target, manifest.toJson(), StandardCharsets.UTF_8);
        return manifest;
    }

    /** 置き場を書いた順に探す。名前そのまま、{@code .pli}、{@code .inc} の順に試す。 */
    private static Optional<String> include(List<Path> includes, String member) {
        for (Path directory : includes) {
            for (String name : List.of(member, member + ".pli", member + ".inc")) {
                Path candidate = directory.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    try {
                        return Optional.of(Files.readString(candidate, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "cannot read include member " + candidate, e);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
