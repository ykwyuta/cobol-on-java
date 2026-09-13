package dev.cobolonjava.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.interop.ProgramId;
import dev.cobolonjava.runtime.interop.ProgramParameter;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.procedure.ProcedureId;
import dev.cobolonjava.runtime.procedure.ProcedureKind;
import dev.cobolonjava.runtime.procedure.ProcedureManifest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class ProcedureManifestGenerationTest {

    private static final class GeneratedLoader extends ClassLoader {
        private GeneratedLoader() {
            super(ProcedureManifestGenerationTest.class.getClassLoader());
        }

        private Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }

    @Test
    void emitsAProgramSignatureFromProcedureUsingAndLinkageLayout() {
        String source = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. SIG-PGM.",
                "       DATA DIVISION.",
                "       LINKAGE SECTION.",
                "       01 LK-REQUEST.",
                "          05 LK-CODE PIC 9(3).",
                "          05 LK-TEXT PIC X(5).",
                "       01 LK-RESULT PIC S9(4) COMP-3.",
                "       PROCEDURE DIVISION USING LK-REQUEST LK-RESULT.",
                "       MAIN-START.",
                "           GOBACK.");

        CobolCompiler.Result result = CobolCompiler.standard().compile("SIG.cbl", source);

        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertEquals(ProgramId.of("SIG-PGM"), result.programSignature().programId());
        assertEquals(2, result.programSignature().parameters().size());
        ProgramParameter request = result.programSignature().parameters().get(0);
        assertEquals("LK-REQUEST", request.name());
        assertEquals(8, request.minimumBytes());
        assertEquals(ProgramParameter.PassingMode.REFERENCE, request.passingMode());
        assertEquals(64, request.layoutHash().length());
        assertEquals(64, result.programSignature().layoutHash().length());

        CobolCompiler.Result sameLengthDifferentUsage = CobolCompiler.standard()
                .compile("SIG.cbl", source.replace("PIC X(5)", "PIC 9(5)"));
        assertTrue(sameLengthDifferentUsage.succeeded(),
                () -> sameLengthDifferentUsage.diagnostics().toString());
        assertNotEquals(request.layoutHash(), sameLengthDifferentUsage.programSignature()
                .parameters().get(0).layoutHash());
        assertNotEquals(result.programSignature().layoutHash(),
                sameLengthDifferentUsage.programSignature().layoutHash());

        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram generated = (CobolProgram) type.getDeclaredConstructor().newInstance();
            assertEquals(result.programSignature(), generated.programSignature());
            assertEquals(result.procedureManifest(), generated.procedureManifest());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void emitsSectionsParagraphsAndAContentHash() {
        CobolCompiler.Result result = compile("CONTINUE");

        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        ProcedureManifest manifest = result.procedureManifest();
        assertEquals(ProgramId.of("META-PGM"), manifest.programId());
        assertTrue(manifest.contains(ProcedureId.section("META-PGM", "READ-RATE")));
        assertTrue(manifest.contains(new ProcedureId(ProgramId.of("META-PGM"),
                ProcedureKind.PARAGRAPH, "MAIN-START")));
        assertTrue(manifest.contains(new ProcedureId(ProgramId.of("META-PGM"),
                ProcedureKind.PARAGRAPH, "READ-P")));
        assertEquals(64, manifest.procedureHash().length());
        assertFalse(manifest.procedures().stream().anyMatch(
                procedure -> procedure.declarative()));
        dev.cobolonjava.runtime.procedure.ProcedureDescriptor section = manifest.procedures()
                .stream()
                .filter(procedure -> procedure.id().equals(
                        ProcedureId.section("META-PGM", "READ-RATE")))
                .findFirst().orElseThrow();
        assertTrue(section.directInvocationEligible());
        assertEquals(1, section.firstParagraph());
        assertEquals(2, section.lastParagraph());
        assertEquals("META.cbl", section.sourceFile());
    }

    @Test
    void changesTheHashWhenProcedureNamesChange() {
        ProcedureManifest first = compile("CONTINUE").procedureManifest();
        String renamed = source("CONTINUE").replace("READ-P.", "READ-P-2.");
        CobolCompiler.Result second = CobolCompiler.standard().compile("META.cbl", renamed);

        assertTrue(second.succeeded(), () -> second.diagnostics().toString());
        assertNotEquals(first.procedureHash(), second.procedureManifest().procedureHash());
    }

    @Test
    void omitsTheInternalAnonymousParagraph() {
        String source = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. ANONPGM.",
                "       PROCEDURE DIVISION.",
                "           DISPLAY 'OK'",
                "           GOBACK.");
        CobolCompiler.Result result = CobolCompiler.standard().compile("ANON.cbl", source);

        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertTrue(result.procedureManifest().procedures().isEmpty());
    }

    @Test
    void conservativelyRejectsUnstructuredTransfersIncludingNestedOnes() {
        String source = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FLOWMETA.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01 WS-A PIC 9 VALUE 1.",
                "       PROCEDURE DIVISION.",
                "       MAIN-START.",
                "           GOBACK.",
                "       NEXT-SEC SECTION.",
                "       NEXT-P.",
                "           IF WS-A = 1 NEXT SENTENCE END-IF",
                "           CONTINUE.",
                "       DEP-SEC SECTION.",
                "       DEP-P.",
                "           GO TO D1 D2 DEPENDING ON WS-A.",
                "       D1.",
                "           CONTINUE.",
                "       D2.",
                "           CONTINUE.",
                "       ALTER-SEC SECTION.",
                "       ALTER-P.",
                "           ALTER SWITCH-P TO PROCEED TO D1.",
                "       SWITCH-P.",
                "           GO TO D2.");
        CobolCompiler.Result result = CobolCompiler.standard().compile("FLOWMETA.cbl", source);

        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertTrue(reason(result, "NEXT-SEC").contains("NEXT SENTENCE"));
        assertTrue(reason(result, "DEP-SEC").contains("GO TO DEPENDING ON"));
        assertTrue(reason(result, "ALTER-SEC").contains("ALTER"));
    }

    private static String reason(CobolCompiler.Result result, String section) {
        return result.procedureManifest().procedures().stream()
                .filter(procedure -> procedure.id().equals(
                        ProcedureId.section("FLOWMETA", section)))
                .findFirst().orElseThrow().ineligibilityReason();
    }

    private static CobolCompiler.Result compile(String statement) {
        return CobolCompiler.standard().compile("META.cbl", source(statement));
    }

    private static String source(String statement) {
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. META-PGM.",
                "       PROCEDURE DIVISION.",
                "       MAIN-START.",
                "           PERFORM READ-RATE",
                "           GOBACK.",
                "       READ-RATE SECTION.",
                "       READ-P.",
                "           " + statement + ".");
    }
}
