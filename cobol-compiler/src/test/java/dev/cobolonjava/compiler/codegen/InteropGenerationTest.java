package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.interop.ProgramParameter;
import dev.cobolonjava.runtime.interop.ProgramSignature;
import dev.cobolonjava.runtime.interop.ProgramSignatureMismatchException;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** コンパイラ生成クラスを通した Java / COBOL 相互呼び出しの結合試験。 */
@Tag("V1")
class InteropGenerationTest {

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(InteropGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    @Test
    @DisplayName("生成 COBOL の CALL は明示登録した Java を参照渡しで呼ぶ")
    void generatedCobolCallsRegisteredJava() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> caller = compile(loader, List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. CALLJAVA.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-TEXT PIC X(3) VALUE 'abc'.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    CALL 'JAVASUB' USING WS-TEXT",
                "    DISPLAY WS-TEXT",
                "    GOBACK."));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .revision("generated-interop-r1")
                .cobolProgram("CALLJAVA", caller)
                .javaProgram("JAVASUB", () -> (context, arguments) ->
                        arguments.get(0).setBytes(context.codePage().encode("XYZ")))
                .build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (CobolSession session = CobolRuntime.builder(catalog)
                .classLoader(loader).build().openSession(output)) {
            session.call("CALLJAVA");
        }

        assertEquals("XYZ", output.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    @DisplayName("Java は明示登録した生成 COBOL を参照渡しで呼ぶ")
    void javaCallsGeneratedCobol() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> subroutine = compile(loader, List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. COBOLSUB.",
                "DATA DIVISION.",
                "LINKAGE SECTION.",
                "01 LK-TEXT PIC X(3).",
                "PROCEDURE DIVISION USING LK-TEXT.",
                "MAIN-START.",
                "    MOVE 'COB' TO LK-TEXT",
                "    GOBACK."));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .revision("generated-interop-r1")
                .cobolProgram("COBOLSUB", subroutine)
                .build();
        Storage argument = Storage.copyOf(CodePages.DEFAULT.encode("---"));

        try (CobolSession session = CobolRuntime.builder(catalog)
                .classLoader(loader).build().openSession()) {
            assertThrows(ProgramSignatureMismatchException.class,
                    () -> session.call("COBOLSUB", Storage.allocate(2).whole()));
            session.call("COBOLSUB", argument.whole());
        }

        assertEquals("COB", CodePages.DEFAULT.decode(argument.array()));
    }

    @Test
    @DisplayName("外部カタログと生成クラスのABI署名が異なる場合は実行しない")
    void rejectsCatalogSignatureThatDisagreesWithGeneratedClass() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> subroutine = compile(loader, List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. COBOLSUB.",
                "DATA DIVISION.",
                "LINKAGE SECTION.",
                "01 LK-TEXT PIC X(3).",
                "PROCEDURE DIVISION USING LK-TEXT.",
                "MAIN-START.",
                "    GOBACK."));
        ProgramSignature staleSignature = ProgramSignature.of("COBOLSUB", List.of(
                ProgramParameter.fixedReference("LK-TEXT", 2, "stale-layout")));
        ProgramCatalog catalog = ProgramCatalog.builder()
                .revision("stale-catalog-r1")
                .cobolProgram("COBOLSUB", staleSignature, subroutine)
                .build();

        try (CobolSession session = CobolRuntime.builder(catalog)
                .classLoader(loader).build().openSession()) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> session.call("COBOLSUB", Storage.allocate(3).whole()));
            assertTrue(error.getMessage().contains(
                    "catalog and generated class signatures disagree"));
        }
    }

    @Test
    @DisplayName("生成クラス名へ変換しても外部PROGRAM-IDは失われない")
    void preservesExternalProgramId() {
        GeneratedLoader loader = new GeneratedLoader();
        Supplier<CobolProgram> program = compile(loader, List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. DASH-PGM.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    GOBACK."));

        assertEquals("DASH-PGM", program.get().name());

        ProgramCatalog catalog = ProgramCatalog.builder()
                .revision("generated-interop-r1")
                .cobolProgram("DASH-PGM", program)
                .build();
        try (CobolSession session = CobolRuntime.builder(catalog)
                .classLoader(loader).build().openSession()) {
            session.call("dash-pgm");
        }
    }

    private static Supplier<CobolProgram> compile(GeneratedLoader loader, List<String> lines) {
        String source = lines.stream()
                .map(line -> "       " + line + "\n")
                .reduce("", String::concat);
        CobolCompiler.Result result = CobolCompiler.standard().compile("INTEROP.cbl", source);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        Class<?> type = loader.define(result.className(), result.classFile());
        return () -> {
            try {
                return (CobolProgram) type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot create generated program", e);
            }
        };
    }
}
