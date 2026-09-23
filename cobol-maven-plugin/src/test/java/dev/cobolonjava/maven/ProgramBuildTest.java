package dev.cobolonjava.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.runtime.interop.DeployCatalogManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 標準の置き場からプログラムを作る (設計 91 §3)。 */
class ProgramBuildTest {

    private static final String GREET = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. GREET.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       COPY GREETW.",
            "       COPY TSTSET.",
            "       PROCEDURE DIVISION.",
            "           DISPLAY WS-GREETING.",
            "           GOBACK.",
            "");

    private static final String BROKEN = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. BROKEN.",
            "       PROCEDURE DIVISION.",
            "           MOVE UNDEFINED-ITEM TO OTHER-ITEM.",
            "");

    private static final String MAPSET = String.join("\n",
            "TSTSET   DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,TIOAPFX=YES",
            "TSTMAP   DFHMDI SIZE=(24,80)",
            "NAME     DFHMDF POS=(3,2),LENGTH=8,ATTRB=(UNPROT,NORM,IC)",
            "         DFHMSD TYPE=FINAL",
            "");

    private static final String PLI = """
            HELLO: PROCEDURE OPTIONS(MAIN);
              %INCLUDE BODY;
            END HELLO;
            """;

    @TempDir
    Path project;

    private final Recorder report = new Recorder();

    private Path output() {
        return project.resolve("target/classes");
    }

    private void write(String relative, String text) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private void build() throws IOException, BuildFailure {
        ProgramBuild.run(SourceLayout.standard(project), output(), false, CompilerOptions.NONE,
                report);
    }

    private DeployCatalogManifest catalog() throws IOException {
        return DeployCatalogManifest.fromJson(Files.readString(
                output().resolve(DeployCatalogManifest.RESOURCE_NAME), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("写し句と BMS の記号マップを標準の置き場から引いて COBOL を翻訳し、mapset を classpath の根へ写す")
    void cobolIsBuiltFromTheStandardLayout() throws Exception {
        write("src/main/cobol/GREET.cbl", GREET);
        write("src/main/copybook/GREETW.cpy",
                "       01 WS-GREETING PIC X(5) VALUE 'HELLO'.\n");
        write("src/main/bms/TSTSET.bms", MAPSET);

        build();

        assertTrue(Files.isRegularFile(output().resolve("cobol/generated/GREET.class")),
                () -> report.lines.toString());
        assertEquals(List.of("GREET"), catalog().programs().stream()
                .map(program -> program.programId().value()).toList());
        assertTrue(Files.isRegularFile(output().resolve("TSTSET.bms")));
    }

    @Test
    @DisplayName("下位ディレクトリも大文字の拡張子も拾い、写し句 (.cpy) は原文として拾わない")
    void subdirectoriesAndUpperCaseSuffixesArePickedUp() throws Exception {
        write("src/main/cobol/batch/GREET.CBL", GREET);
        write("src/main/cobol/GREETW.cpy",
                "       01 WS-GREETING PIC X(5) VALUE 'HELLO'.\n");
        write("src/main/copybook/GREETW.cpy",
                "       01 WS-GREETING PIC X(5) VALUE 'HELLO'.\n");
        write("src/main/bms/TSTSET.bms", MAPSET);

        build();

        assertEquals(List.of("GREET"), catalog().programs().stream()
                .map(program -> program.programId().value()).toList());
    }

    @Test
    @DisplayName("1 本が翻訳できなくても残りは翻訳し、診断を出してからビルドを止める")
    void aFailedSourceStopsTheBuildAfterTheRestIsBuilt() throws Exception {
        write("src/main/cobol/BROKEN.cbl", BROKEN);
        write("src/main/cobol/GREET.cbl", GREET);
        write("src/main/copybook/GREETW.cpy",
                "       01 WS-GREETING PIC X(5) VALUE 'HELLO'.\n");
        write("src/main/bms/TSTSET.bms", MAPSET);

        BuildFailure failure = assertThrows(BuildFailure.class, this::build);

        assertEquals("1 source(s) failed to build", failure.getMessage());
        assertTrue(report.lines.stream().anyMatch(line -> line.startsWith("ERROR ")
                && line.contains("BROKEN")), () -> report.lines.toString());
        assertEquals(List.of("GREET"), catalog().programs().stream()
                .map(program -> program.programId().value()).toList());
    }

    @Test
    @DisplayName("COPY されない mapset の誤りもビルドで出す")
    void anInvalidMapsetFailsTheBuild() throws Exception {
        write("src/main/bms/BADSET.bms", "BADSET   DFHMSD TYPE=&SYSPARM,NOSUCH=1\n");

        assertThrows(BuildFailure.class, this::build);

        assertTrue(report.lines.stream().anyMatch(line -> line.startsWith("ERROR ")
                && line.contains("BADSET.bms")), () -> report.lines.toString());
        assertFalse(Files.exists(output().resolve("BADSET.bms")));
    }

    @Test
    @DisplayName("PL/I を %INCLUDE の置き場を引いて翻訳し、pli.generated のカタログを書く")
    void pliIsBuiltFromTheStandardLayout() throws Exception {
        write("src/main/pli/HELLO.pli", PLI);
        write("src/main/pli-include/BODY.inc", "PUT SKIP LIST('HELLO');\n");

        build();

        assertTrue(Files.isRegularFile(output().resolve("pli/generated/HELLO.class")),
                () -> report.lines.toString());
        DeployCatalogManifest catalog = catalog();
        assertEquals("pli.generated", catalog.allowedPackage());
        assertEquals(List.of("HELLO"), catalog.programs().stream()
                .map(program -> program.programId().value()).toList());
    }

    @Test
    @DisplayName("COBOL と PL/I を 1 つのモジュールに置くと、カタログが上書きされる前に断る (P-182)")
    void cobolAndPliInOneModuleAreRefused() throws Exception {
        write("src/main/cobol/GREET.cbl", GREET);
        write("src/main/pli/HELLO.pli", PLI);

        assertThrows(BuildFailure.class, this::build);

        assertTrue(report.lines.stream().anyMatch(line -> line.contains("P-182")));
        assertFalse(Files.exists(output().resolve(DeployCatalogManifest.RESOURCE_NAME)));
    }

    @Test
    @DisplayName("原文が無ければ何も書かない")
    void nothingIsWrittenWithoutSources() throws Exception {
        build();

        assertFalse(Files.exists(output()));
    }
}
