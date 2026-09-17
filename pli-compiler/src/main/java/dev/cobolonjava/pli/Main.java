package dev.cobolonjava.pli;

import dev.cobolonjava.runtime.interop.CatalogRevision;
import dev.cobolonjava.runtime.interop.DeployCatalogManifest;
import dev.cobolonjava.runtime.interop.GeneratedProgramArtifact;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** {@code plic [-d dir] [-I include-dir] source...}。 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: plic [-d dir] [-I include-dir] source...");
            System.exit(2);
            return;
        }
        PliCompiler compiler = new PliCompiler(new PliPreprocessor(options::include));
        int failures = 0;
        List<Compiled> compiled = new ArrayList<>();
        for (Path source : options.sources) {
            PliCompiler.Result result = compiler.compile(source.getFileName().toString(),
                    Files.readString(source, StandardCharsets.UTF_8));
            for (Diagnostic diagnostic : result.diagnostics()) System.err.println(diagnostic);
            if (!result.succeeded()) {
                failures++;
                continue;
            }
            Path target = options.output.resolve(result.className().replace('.', '/') + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, result.classFile());
            compiled.add(new Compiled(result));
        }
        writeCatalog(options.output, compiled);
        if (failures > 0) System.exit(1);
    }

    private static void writeCatalog(Path output, List<Compiled> compiled) throws IOException {
        MessageDigest revision = sha256();
        List<GeneratedProgramArtifact> artifacts = new ArrayList<>();
        compiled.stream().sorted(java.util.Comparator.comparing(
                value -> value.result.programSignature().programId().value())).forEach(value -> {
                    PliCompiler.Result result = value.result;
                    revision.update(result.classFile());
                    artifacts.add(new GeneratedProgramArtifact(
                            result.programSignature().programId(), result.className(),
                            result.programSignature(),
                            result.procedureManifest().procedureHash()));
                });
        DeployCatalogManifest manifest = new DeployCatalogManifest(
                DeployCatalogManifest.CURRENT_FORMAT_VERSION,
                new CatalogRevision("sha256:" + HexFormat.of().formatHex(revision.digest())),
                "pli-compiler", DeployCatalogManifest.CURRENT_RUNTIME_ABI_VERSION,
                "pli.generated", artifacts);
        Path target = output.resolve(DeployCatalogManifest.RESOURCE_NAME);
        Files.createDirectories(target.getParent());
        Files.writeString(target, manifest.toJson(), StandardCharsets.UTF_8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Compiled(PliCompiler.Result result) {
    }

    private record Options(List<Path> sources, Path output, Path includes) {
        static Options parse(String[] args) {
            List<Path> sources = new ArrayList<>();
            Path output = Path.of(".");
            Path includes = null;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "-d" -> output = Path.of(next(args, ++i, "-d"));
                    case "-I" -> includes = Path.of(next(args, ++i, "-I"));
                    default -> {
                        if (args[i].startsWith("-")) {
                            throw new IllegalArgumentException("unknown option: " + args[i]);
                        }
                        sources.add(Path.of(args[i]));
                    }
                }
            }
            if (sources.isEmpty()) throw new IllegalArgumentException("no source file given");
            return new Options(List.copyOf(sources), output, includes);
        }

        Optional<String> include(String member) {
            if (includes == null) return Optional.empty();
            for (String name : List.of(member, member + ".pli", member + ".inc")) {
                Path candidate = includes.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    try {
                        return Optional.of(Files.readString(candidate, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new IllegalStateException("cannot read include member " + candidate, e);
                    }
                }
            }
            return Optional.empty();
        }

        private static String next(String[] args, int index, String option) {
            if (index >= args.length) throw new IllegalArgumentException(option + " requires a value");
            return args[index];
        }
    }
}
