package dev.cobolonjava.maven;

import dev.cobolonjava.cics.bms.BmsDefinitionException;
import dev.cobolonjava.cics.bms.BmsParser;
import dev.cobolonjava.compiler.CobolBuild;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.pli.PliBuild;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * プログラムを作る手順 (設計 91 §3)。BMS を検め、COBOL と PL/I を翻訳する。
 *
 * <p>翻訳そのものは {@link CobolBuild} と {@link PliBuild} が行う。コマンドラインの
 * {@code cobolc} / {@code plic} と同じものを呼ぶので、どちらで作っても同じクラスと
 * 配備カタログが出る。
 */
final class ProgramBuild {

    private ProgramBuild() {
    }

    static void run(SourceLayout layout, Path output, boolean freeFormat,
                    CompilerOptions compilerOptions, Report report)
            throws IOException, BuildFailure {
        List<Path> cobol = layout.cobolSources();
        List<Path> pli = layout.pliSources();
        if (!cobol.isEmpty() && !pli.isEmpty()) {
            // 配備カタログは 1 つの classpath に 1 つで、載せられる package も 1 つである。
            // COBOL (cobol.generated) と PL/I (pli.generated) を同じ置き場へ書くと、
            // 後から書いたほうのカタログが先のものを黙って消す (暫定判断 P-182)
            report.error("COBOL sources (" + layout.cobol() + ") and PL/I sources ("
                    + layout.pli() + ") cannot be built into one module yet;"
                    + " put them in separate modules (P-182)");
            throw new BuildFailure("COBOL and PL/I in one module");
        }

        int failures = copyMapsets(layout.bmsSources(), output, report);

        if (!cobol.isEmpty()) {
            CobolBuild.Result result = CobolBuild.run(new CobolBuild.Request(cobol, output,
                    layout.copybookSearchPath(), freeFormat, compilerOptions),
                    (source, diagnostic) -> report(diagnostic, report));
            report.info("COBOL: " + result.programIds().size() + " program(s) from "
                    + cobol.size() + " source(s)");
            failures += result.failedSources().size();
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
        }
        if (cobol.isEmpty() && pli.isEmpty()) {
            report.info("no COBOL or PL/I sources in " + layout.cobol() + " / " + layout.pli());
        }
        if (failures > 0) {
            throw new BuildFailure(failures + " source(s) failed to build");
        }
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
