package dev.cobolonjava.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.DeployCatalogManifest;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 起動口からソースを翻訳し、書き出したクラスファイルをそのまま動かす。 */
@Tag("V1")
class MainTest {

    private static final String SOURCE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. SUMMER.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01 WS-I     PIC 9(3) COMP VALUE 1.",
            "       01 WS-TOTAL PIC 9(5) COMP-3 VALUE 0.",
            "       PROCEDURE DIVISION.",
            "       MAIN-START.",
            "           PERFORM UNTIL WS-I > 5",
            "               ADD WS-I TO WS-TOTAL",
            "               ADD 1 TO WS-I",
            "           END-PERFORM",
            "           DISPLAY 'SUM=' WS-TOTAL.",
            "");

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(MainTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    @Test
    @DisplayName("ソースからクラスファイルを書き出し、そのまま動かせる (FR-180)")
    void aSourceFileBecomesARunnableClass(@TempDir Path work) throws IOException {
        Path source = work.resolve("SUMMER.cbl");
        Files.writeString(source, SOURCE, StandardCharsets.UTF_8);
        Path output = work.resolve("out");

        Main.main(new String[] {"-d", output.toString(), source.toString()});

        Path classFile = output.resolve("cobol/generated/SUMMER.class");
        assertTrue(Files.isRegularFile(classFile), "クラスファイルが書き出される");

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            Class<?> type = new GeneratedLoader()
                    .define("cobol.generated.SUMMER", Files.readAllBytes(classFile));
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            program.runFresh(ProgramContext.capturing(sink));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
        assertEquals("SUM=00015" + System.lineSeparator(), sink.toString(StandardCharsets.UTF_8));

        Path manifestFile = output.resolve(DeployCatalogManifest.RESOURCE_NAME);
        assertTrue(Files.isRegularFile(manifestFile), "配備カタログが書き出される");
        ByteArrayOutputStream catalogSink = new ByteArrayOutputStream();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {
                output.toUri().toURL()}, getClass().getClassLoader())) {
            DeployCatalogManifest manifest = DeployCatalogManifest.fromResource(loader);
            assertEquals("SUMMER", manifest.programs().get(0).programId().value());
            assertNotNull(manifest.programs().get(0).signature());
            try (CobolSession session = CobolRuntime.builder(manifest.toProgramCatalog())
                    .classLoader(loader).build().openSession(catalogSink)) {
                session.runMain("SUMMER");
            }
        }
        assertEquals("SUM=00015" + System.lineSeparator(),
                catalogSink.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("コピー句のディレクトリを指定できる (FR-090, FR-180)")
    void aCopybookDirectoryCanBeGiven(@TempDir Path work) throws IOException {
        Files.writeString(work.resolve("REC.cpy"), String.join("\n",
                "       01 WS-A PIC X(3) VALUE 'ABC'.",
                ""), StandardCharsets.UTF_8);
        Path source = work.resolve("USER.cbl");
        Files.writeString(source, String.join("\n", List.of(
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. USER.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       COPY REC.",
                "       PROCEDURE DIVISION.",
                "           DISPLAY WS-A.",
                "")), StandardCharsets.UTF_8);
        Path output = work.resolve("out");

        Main.main(new String[] {"-d", output.toString(), "-I", work.toString(),
                source.toString()});

        assertTrue(Files.isRegularFile(output.resolve("cobol/generated/USER.class")));
    }

    @Test
    @DisplayName("自由形式のソースも翻訳できる (FR-002, FR-180)")
    void aFreeFormatSourceCompilesToo(@TempDir Path work) throws IOException {
        Path source = work.resolve("FREE.cbl");
        Files.writeString(source, String.join("\n", List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. FREEONE.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-A PIC X(2) VALUE 'OK'.",
                "PROCEDURE DIVISION.",
                "    DISPLAY WS-A.  *> 行内注釈",
                "")), StandardCharsets.UTF_8);
        Path output = work.resolve("out");

        Main.main(new String[] {"-d", output.toString(), "--free", source.toString()});

        assertTrue(Files.isRegularFile(output.resolve("cobol/generated/FREEONE.class")));
    }
}
