package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.source.LanguageEnvironmentCopyBookResolver;
import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.ProgramCatalog;
import dev.cobolonjava.runtime.le.LanguageEnvironmentServices;
import dev.cobolonjava.runtime.program.CobolProgram;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CEEIGZCT と LE の日付の callable service (暫定判断 P-139)。 */
@Tag("V1")
class LanguageEnvironmentGenerationTest {

    private static final class GeneratedLoader extends ClassLoader {
        private GeneratedLoader() {
            super(LanguageEnvironmentGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static CobolCompiler.Result compile(List<String> procedure) {
        List<String> lines = new java.util.ArrayList<>(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. LEDATES.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-DATE.",
                "   03 WS-DATE-LEN PIC S9(4) BINARY VALUE 8.",
                "   03 WS-DATE-TEXT PIC X(8) VALUE '20260230'.",
                "01 WS-PIC.",
                "   03 WS-PIC-LEN PIC S9(4) BINARY VALUE 8.",
                "   03 WS-PIC-TEXT PIC X(8) VALUE 'YYYYMMDD'.",
                "01 WS-LILIAN PIC S9(9) BINARY.",
                "01 WS-MSG PIC 9(4).",
                "01 WS-OUT PIC 9(9).",
                "01 FC.",
                "   02 CONDITION-TOKEN-VALUE.",
                "   COPY CEEIGZCT.",
                "      03 CASE-1-CONDITION-ID.",
                "         04 SEVERITY PIC S9(4) BINARY.",
                "         04 MSG-NO PIC S9(4) BINARY.",
                "      03 CASE-SEV-CTL PIC X.",
                "      03 FACILITY-ID PIC XXX.",
                "   02 I-S-INFO PIC S9(9) BINARY.",
                "PROCEDURE DIVISION.",
                "MAIN-START."));
        lines.addAll(procedure);
        return CobolCompiler.with(new LanguageEnvironmentCopyBookResolver())
                .compile("LEDATES.cbl", lines.stream().map(line -> "       " + line + "\n").reduce("", String::concat));
    }

    @Test
    @DisplayName("COPY CEEIGZCTのCEE000で成否を見て、CEEDAYSのLilianの日とmessage番号を読む")
    void callsDateServices() {
        CobolCompiler.Result result = compile(List.of(
                "    CALL 'CEEDAYS' USING WS-DATE WS-PIC WS-LILIAN FC",
                "    MOVE MSG-NO OF FC TO WS-MSG",
                "    IF NOT CEE000 OF FC DISPLAY 'BAD ' WS-MSG END-IF",
                "    MOVE '20260915' TO WS-DATE-TEXT",
                "    CALL 'CEEDAYS' USING WS-DATE WS-PIC WS-LILIAN FC",
                "    MOVE WS-LILIAN TO WS-OUT",
                "    IF CEE000 OF FC DISPLAY 'LILIAN ' WS-OUT END-IF",
                "    GOBACK."));
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        GeneratedLoader loader = new GeneratedLoader();
        Class<?> type = loader.define(result.className(), result.classFile());
        ProgramCatalog catalog = LanguageEnvironmentServices.register(ProgramCatalog.builder()
                .cobolProgram("LEDATES", () -> {
                    try {
                        return (CobolProgram) type.getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                })).build();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CobolSession session = CobolRuntime.builder(catalog).classLoader(loader).build().openSession(output)) {
            session.call("LEDATES");
        }

        String printed = output.toString(StandardCharsets.UTF_8);
        long lilian = LocalDate.of(2026, 9, 15).toEpochDay() - LocalDate.of(1582, 10, 14).toEpochDay();
        assertTrue(printed.contains("BAD") && printed.contains("2508"), printed);
        assertTrue(printed.contains("LILIAN") && printed.contains(Long.toString(lilian)), printed);
    }

    @Test
    @DisplayName("CEEIGZCTはCEE000だけを定義し、ほかの記号名は定義されていないとして止まる")
    void definesOnlyCee000() {
        CobolCompiler.Result result = compile(List.of(
                "    IF CEE2EC OF FC DISPLAY 'X' END-IF",
                "    GOBACK."));
        assertFalse(result.succeeded());
    }
}
