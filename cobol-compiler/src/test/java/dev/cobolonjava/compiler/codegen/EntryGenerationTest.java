package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * IMS が呼ぶ入口 {@code ENTRY 'DLITCBL' USING} と、{@code PROGRAM-ID} の終止符の欠落 (暫定判断 P-152)。
 *
 * <p>IMS の COBOL プログラムは {@code PROCEDURE DIVISION.} に USING を書かず、先頭の ENTRY で PCB を
 * 受け取る。ENTRY の USING が引数にならなければ、連絡節は何も指さず最初の参照で止まる。
 */
@Tag("V1")
class EntryGenerationTest {

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(EntryGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static CobolCompiler.Result compile(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("       ").append(line).append('\n');
        }
        return CobolCompiler.standard().compile("IMSPGM.cbl", sb.toString());
    }

    private static CobolProgram load(CobolCompiler.Result result) {
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            return (CobolProgram) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot load the generated program", e);
        }
    }

    /** 2 つの PCB を受け、1 つ目の状態欄と 2 つ目の先頭を書き換える。 */
    private static List<String> program(String programId, String entry, String body) {
        return List.of(
                "IDENTIFICATION DIVISION.",
                programId,
                "DATA DIVISION.",
                "LINKAGE SECTION.",
                "01 IO-PCB.",
                "   05 FILLER PIC X(10).",
                "   05 IO-STAT PIC X(2).",
                "01 DB-PCB.",
                "   05 DB-NAME PIC X(8).",
                "PROCEDURE DIVISION.",
                entry,
                "BEGIN.",
                body,
                "    GOBACK.");
    }

    @Test
    @DisplayName("先頭の ENTRY の USING が引数になり、PCB の書き換えが呼ぶ側に届く (P-152)")
    void theLeadingEntryBindsTheParameters() {
        CobolProgram program = load(compile(program("PROGRAM-ID. IMSPGM.",
                "    ENTRY 'DLITCBL' USING IO-PCB, DB-PCB.",
                "    MOVE 'QC' TO IO-STAT MOVE 'CUSTOMER' TO DB-NAME.")));
        Storage caller = Storage.wrap(CodePages.DEFAULT.encode("LTERM0001 AAxxxxxxxx"));

        program.runFresh(ProgramContext.standard(), caller.view(0, 12), caller.view(12, 8));

        assertEquals("LTERM0001 QCCUSTOMER", CodePages.DEFAULT.decode(caller.array()));
    }

    @Test
    @DisplayName("流れが ENTRY に達しても何もしない (P-152)")
    void reachingTheEntryDoesNothing() {
        // 段落から GO TO で先頭の文へは戻れないので、先頭の文として 1 度通るだけを見る
        CobolProgram program = load(compile(program("PROGRAM-ID. IMSPGM.",
                "    ENTRY 'DLITCBL' USING IO-PCB DB-PCB.",
                "    MOVE 'OK' TO IO-STAT.")));
        Storage caller = Storage.wrap(CodePages.DEFAULT.encode("            DBNAME01"));

        program.runFresh(ProgramContext.standard(), caller.view(0, 12), caller.view(12, 8));

        assertEquals("          OKDBNAME01", CodePages.DEFAULT.decode(caller.array()));
    }

    @Test
    @DisplayName("手続き部の途中の ENTRY は副入口なので断る (P-152)")
    void anEntryInTheMiddleIsRefused() {
        // 先頭の文は DISPLAY で、ENTRY は段落 BEGIN の中にある
        CobolCompiler.Result result = compile(program("PROGRAM-ID. IMSPGM.",
                "    DISPLAY 'START'.",
                "    ENTRY 'ALTENTRY' USING IO-PCB."));

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("alternate entry points are not supported yet"),
                () -> result.diagnostics().toString());
    }

    @Test
    @DisplayName("見出しに USING があるプログラムの ENTRY も副入口なので断る (P-152)")
    void anEntryAfterProcedureDivisionUsingIsRefused() {
        CobolCompiler.Result result = compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. IMSPGM.",
                "DATA DIVISION.",
                "LINKAGE SECTION.",
                "01 IO-PCB PIC X(12).",
                "PROCEDURE DIVISION USING IO-PCB.",
                "    ENTRY 'DLITCBL' USING IO-PCB.",
                "    GOBACK."));

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("alternate entry points are not supported yet"),
                () -> result.diagnostics().toString());
    }

    @Test
    @DisplayName("ENTRY は COBOL-85 の予約語ではないので、データ名として使える")
    void entryRemainsAUserDefinedWord() {
        // 先頭の文が「ENTRY の次に文字定数」でなければ ENTRY 文として読まない
        CobolProgram program = load(compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. IMSPGM.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 ENTRY PIC X(2) VALUE 'AB'.",
                "01 COPIED PIC X(2).",
                "PROCEDURE DIVISION.",
                "    MOVE ENTRY TO COPIED",
                "    IF COPIED NOT = 'AB' MOVE 8 TO RETURN-CODE END-IF",
                "    GOBACK.")));
        ProgramContext context = ProgramContext.standard();

        program.runFresh(context);

        assertEquals(0, context.returnCode());
    }

    @Test
    @DisplayName("PROGRAM-ID の名前のあとの終止符が欠けていても、告げて翻訳を続ける")
    void aMissingPeriodAfterTheProgramNameIsAWarning() {
        CobolCompiler.Result result = compile(program("PROGRAM-ID. IMSPGM",
                "    ENTRY 'DLITCBL' USING IO-PCB DB-PCB.",
                "    MOVE 'OK' TO IO-STAT."));

        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.isWarning()
                && d.message().contains("PROGRAM-ID")), () -> result.diagnostics().toString());
    }
}
