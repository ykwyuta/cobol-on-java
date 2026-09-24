package dev.cobolonjava.maven;

import dev.cobolonjava.cics.bms.BmsDefinitionException;
import dev.cobolonjava.cics.bms.BmsParser;
import dev.cobolonjava.compiler.CobolBuild;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.hlasm.HlasmCompiler;
import dev.cobolonjava.hlasm.SourceLibrary;
import dev.cobolonjava.pli.PliBuild;
import dev.cobolonjava.runtime.interop.DeployCatalogManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * プログラムを作る手順 (設計 91 §3)。BMS を検め、COBOL・PL/I・HLASM を翻訳する。
 *
 * <p>翻訳そのものは {@link CobolBuild}・{@link PliBuild}・{@link HlasmCompiler} が行う。
 * コマンドラインの {@code cobolc} / {@code plic} / {@code hlasm} と同じものを呼ぶので、
 * どちらで作っても同じクラスが出る。
 *
 * <p>3 つの言語の生成クラスは同じ package (名前空間) に置く。ホストでは言語を問わずロード
 * モジュールが 1 つのロードライブラリに入るからである。COBOL と PL/I の配備カタログは
 * 1 つにまとめる (暫定判断 P-182 の解消)。
 */
final class ProgramBuild {

    private ProgramBuild() {
    }

    static void run(SourceLayout layout, Path output, boolean freeFormat,
                    CompilerOptions compilerOptions, Report report)
            throws IOException, BuildFailure {
        List<Path> cobol = layout.cobolSources();
        List<Path> pli = layout.pliSources();
        List<Path> hlasm = layout.hlasmSources();

        int failures = copyMapsets(layout.bmsSources(), output, report);

        List<DeployCatalogManifest> catalogs = new ArrayList<>();
        if (!cobol.isEmpty()) {
            CobolBuild.Result result = CobolBuild.run(new CobolBuild.Request(cobol, output,
                    layout.copybookSearchPath(), freeFormat, compilerOptions),
                    (source, diagnostic) -> report(diagnostic, report));
            report.info("COBOL: " + result.programIds().size() + " program(s) from "
                    + cobol.size() + " source(s)");
            failures += result.failedSources().size();
            catalogs.add(result.catalog());
        }
        if (!pli.isEmpty()) {
            PliBuild.Result result = PliBuild.run(new PliBuild.Request(pli, output,
                    layout.pliIncludeSearchPath()), diagnostic -> {
                        if (diagnostic.severity() == dev.cobolonjava.pli.Diagnostic.Severity.ERROR) {
                            report.error(diagnostic.toString());
                        } else {
                            report.warn(diagnostic.toString());
                        }
                    });
            report.info("PL/I: " + result.programIds().size() + " program(s) from "
                    + pli.size() + " source(s)");
            failures += result.failedSources().size();
            catalogs.add(result.catalog());
        }
        if (catalogs.size() > 1) {
            // どちらの Build も自分のカタログを同じ場所へ書くので、1 つにまとめて書き直す。
            // 同じ名前のプログラムは、ホストで同じロードライブラリに入れられないのと同じく断る
            try {
                DeployCatalogManifest merged = DeployCatalogManifest.merge("cobol-on-java",
                        catalogs);
                Files.writeString(output.resolve(DeployCatalogManifest.RESOURCE_NAME),
                        merged.toJson(), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException clash) {
                report.error("COBOL and PL/I programs share one name space: "
                        + clash.getMessage());
                failures++;
            }
        }
        failures += assemble(hlasm, layout.hlasmLibrary(), output, catalogs, report);
        if (cobol.isEmpty() && pli.isEmpty() && hlasm.isEmpty()) {
            report.info("no COBOL, PL/I or HLASM sources in " + layout.cobol() + " / "
                    + layout.pli() + " / " + layout.hlasm());
        }
        if (failures > 0) {
            throw new BuildFailure(failures + " source(s) failed to build");
        }
    }

    /**
     * HLASM を組み立てる。配備カタログには載せない。カタログは引数の個数と最小の長さを持つが、
     * HLASM の原文からは決まらない (R1 の表を何本読むかはプログラムが決める)。生成クラスは
     * COBOL と同じ名前空間にあるので、{@code CALL} と {@code EXEC PGM=} は名前で引ける。
     *
     * @return 失敗した原文の数
     */
    private static int assemble(List<Path> sources, Path library, Path output,
                                List<DeployCatalogManifest> catalogs, Report report)
            throws IOException {
        if (sources.isEmpty()) {
            return 0;
        }
        Set<String> taken = new HashSet<>();
        catalogs.forEach(catalog -> catalog.programs()
                .forEach(program -> taken.add(program.className())));
        HlasmCompiler compiler = HlasmCompiler.withLibrary(SourceLibrary.directory(library));
        int failures = 0;
        int assembled = 0;
        for (Path source : sources) {
            HlasmCompiler.Result result = compiler.compile(source.getFileName().toString(),
                    Files.readString(source, StandardCharsets.UTF_8));
            for (dev.cobolonjava.hlasm.Diagnostic diagnostic : result.diagnostics()) {
                if (diagnostic.severity() == dev.cobolonjava.hlasm.Diagnostic.Severity.ERROR) {
                    report.error(diagnostic.toString());
                } else if (diagnostic.severity()
                        == dev.cobolonjava.hlasm.Diagnostic.Severity.INFO) {
                    report.info(diagnostic.toString());
                } else {
                    report.warn(diagnostic.toString());
                }
            }
            if (!result.succeeded()) {
                failures++;
                continue;
            }
            if (!taken.add(result.className())) {
                // 同じ名前のクラスを書くと、先に作ったプログラムを黙って消してしまう
                report.error(source + ": program " + result.module().name()
                        + " is also built from another source");
                failures++;
                continue;
            }
            Path target = output.resolve(result.className().replace('.', '/') + ".class");
            Files.createDirectories(target.getParent());
            Files.write(target, result.classFile());
            assembled++;
        }
        report.info("HLASM: " + assembled + " module(s), not in the deploy catalog");
        return failures;
    }

    private static void report(Diagnostic diagnostic, Report report) {
        if (diagnostic.severity().blocking()) {
            report.error(diagnostic.toString());
        } else if (diagnostic.isWarning()) {
            report.warn(diagnostic.toString());
        } else {
            report.info(diagnostic.toString());
        }
    }

    /**
     * BMS の原文を 1 本ずつ検め、classpath の根へ写す。
     *
     * <p>{@code COPY} されない mapset は翻訳の途中で読まれないので、ここで読まないと
     * 誤りが実行時まで残る。写す先を根にしたのは、実行時に {@code /名前.bms} で読む
     * 既存の使い方 (デモ 009) に合わせたためである。根に置くので、名前が重なれば断る。
     * 翻訳の写し句展開 ({@code BmsCopyBookResolver}) と同じく ISO-8859-1 で読む。
     *
     * @return 誤りのあった原文の数
     */
    private static int copyMapsets(List<Path> mapsets, Path output, Report report)
            throws IOException {
        int failures = 0;
        Map<String, Path> seen = new HashMap<>();
        for (Path mapset : mapsets) {
            String name = mapset.getFileName().toString();
            Path previous = seen.putIfAbsent(name.toUpperCase(Locale.ROOT), mapset);
            if (previous != null) {
                report.error(mapset + ": BMS mapset name is also used by " + previous);
                failures++;
                continue;
            }
            try {
                BmsParser.parse(Files.readString(mapset, StandardCharsets.ISO_8859_1));
            } catch (BmsDefinitionException invalid) {
                report.error(mapset + ": " + invalid.getMessage());
                failures++;
                continue;
            }
            Files.createDirectories(output);
            Files.copy(mapset, output.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        }
        if (!mapsets.isEmpty()) {
            report.info("BMS: " + (mapsets.size() - failures) + " mapset(s)");
        }
        return failures;
    }
}
