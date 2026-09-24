package dev.cobolonjava.hlasm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CopyExpanderTest {

    @Test
    void copiedMacroWorksAfterLibraryDirectoryIsGone(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("DEF.asm"), String.join("\n",
                "         MACRO",
                "         FIELD &N",
                "         DC    F'&N'",
                "         MEND"), StandardCharsets.UTF_8);
        HlasmCompiler.Result result = HlasmCompiler.withLibrary(SourceLibrary.directory(directory))
                .compile("test.asm", String.join("\n",
                        "TEST     CSECT",
                        "         SR    15,15",
                        "         BR    14",
                        "         COPY  DEF",
                        "         FIELD 7",
                        "         END"));
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("00000007", result.module().hex(4, 4));

        Files.delete(directory.resolve("DEF.asm"));
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> loadGenerated(String name, byte[] bytes) {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }.loadGenerated(result.className(), result.classFile());
        CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
        program.run(Storage.allocate(0), null, new DataView[0]);
        assertEquals("TEST", program.programSignature().programId().value());
    }

    @Test
    void nestedCopyUsesMembersInOrder() {
        Map<String, String> members = Map.of(
                "OUTER", "         COPY  INNER",
                "INNER", "         DC    C'A'");
        HlasmCompiler.Result result = HlasmCompiler.withLibrary(members::get)
                .compile("test.asm", String.join("\n",
                        "TEST     CSECT", "         COPY  OUTER", "         END"));
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("C1", result.module().hex(0, 1));
    }

    @Test
    void endInCopiedMemberStopsBeforeLaterMissingCopy() {
        HlasmCompiler.Result result = HlasmCompiler.withLibrary(member ->
                member.equals("FINISH") ? "         DC    C'A'\n         END" : null)
                .compile("test.asm", String.join("\n",
                        "TEST     CSECT", "         COPY  FINISH",
                        "         COPY  ABSENT"));
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("C1", result.module().hex(0, 1));
    }

    @Test
    void findsLibraryMacroByInstructionNameIncludingNestedDependency() {
        Map<String, String> members = Map.of(
                "OUTER", String.join("\n", "         MACRO", "         OUTER &N",
                        "         INNER &N", "         MEND"),
                "INNER", String.join("\n", "         MACRO", "         INNER &N",
                        "         DC    F'&N'", "         MEND"));
        HlasmCompiler.Result result = HlasmCompiler.withLibrary(members::get)
                .compile("test.asm", String.join("\n",
                        "TEST     CSECT", "         OUTER 7", "         END"));
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("00000007", result.module().hex(0, 4));
    }

    @Test
    void findsLibraryMacrosWhoseNamesAreGeneratedBySetSymbols() throws Exception {
        Map<String, String> members = Map.of(
                "OUTER", String.join("\n", "         MACRO", "         OUTER &N",
                        "&NEXT    SETC  'INNER'", "         &NEXT &N", "         MEND"),
                "INNER", String.join("\n", "         MACRO", "         INNER &N",
                        "         DC    F'&N'", "         MEND"));
        HlasmCompiler.Result result = HlasmCompiler.withLibrary(members::get)
                .compile("test.asm", String.join("\n",
                        "TEST     CSECT", "         SR    15,15", "         BR    14",
                        "&OP      SETC  'OUTER'",
                        "         &OP   7", "         END"));
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("00000007", result.module().hex(4, 4));
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> loadGenerated(String name, byte[] bytes) {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }.loadGenerated(result.className(), result.classFile());
        CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
        program.run(Storage.allocate(0), null, new DataView[0]);
        assertEquals("TEST", program.programSignature().programId().value());
    }

    @Test
    void expandsCopyInsideDynamicallySelectedLibraryMacro() {
        Map<String, String> members = Map.of(
                "EMIT", String.join("\n", "         MACRO", "         EMIT  &N",
                        "         COPY  VALUE", "         MEND"),
                "VALUE", "         DC    F'&N'");
        HlasmCompiler.Result result = HlasmCompiler.withLibrary(members::get)
                .compile("test.asm", String.join("\n",
                        "TEST     CSECT", "&OP      SETC  'EMIT'",
                        "         &OP   9", "         END"));
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("00000009", result.module().hex(0, 4));
    }

    @Test
    void macroParameterCanSelectLibraryMacro() {
        HlasmCompiler.Result result = HlasmCompiler.withLibrary(member ->
                member.equals("VALUE") ? String.join("\n",
                        "         MACRO", "         VALUE &N",
                        "         DC    F'&N'", "         MEND") : null)
                .compile("test.asm", String.join("\n",
                        "         MACRO", "         CHOOSE &OP,&N",
                        "         &OP   &N", "         MEND",
                        "TEST     CSECT", "         CHOOSE VALUE,5", "         END"));
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals("00000005", result.module().hex(0, 4));
    }

    @Test
    void rejectsMalformedDynamicallySelectedLibraryMacro() {
        HlasmCompiler.Result result = HlasmCompiler.withLibrary(
                member -> "         MACRO\n         OTHER\n         MEND")
                .compile("test.asm", String.join("\n",
                        "TEST     CSECT", "&OP      SETC  'WANTED'",
                        "         &OP", "         END"));
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("must define macro WANTED"));
    }

    @Test
    void rejectsLibraryMemberWhosePrototypeHasAnotherName() {
        HlasmCompiler.Result result = HlasmCompiler.withLibrary(
                member -> "         MACRO\n         OTHER\n         MEND")
                .compile("test.asm", "TEST     CSECT\n         WANTED\n         END");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("must define macro WANTED"));
    }

    @Test
    void missingAndRecursiveMembersFailWithDiagnostics() {
        HlasmCompiler.Result missing = HlasmCompiler.standard().compile("test.asm",
                "TEST     CSECT\n         COPY  ABSENT\n         END");
        assertFalse(missing.succeeded());
        assertEquals(2, missing.diagnostics().get(0).line());
        assertTrue(missing.diagnostics().get(0).message().contains("not found"));

        HlasmCompiler.Result recursive = HlasmCompiler.withLibrary(
                member -> "         COPY  LOOP").compile("test.asm",
                "TEST     CSECT\n         COPY  LOOP\n         END");
        assertFalse(recursive.succeeded());
        assertTrue(recursive.diagnostics().get(0).message().contains("recursive COPY"));
    }
}
