package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code RETURN-CODE} 特殊レジスタ (要件 FR-084)。
 *
 * <p>実行の全体で 1 つである。呼ぶ側と呼ばれる側が<b>同じものを見る</b>ため、
 * プログラムごとの記憶域ではなく実行時の入口が持つ置き場にある。写し取る仕組みは要らない。
 */
@Tag("V1")
class ReturnCodeGenerationTest {

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(ReturnCodeGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String source(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("       ").append(line).append('\n');
        }
        return sb.toString();
    }

    /** 1 本のプログラムを実行し、RETURN-CODE を返す。 */
    private static int runOne(List<String> storage, String... procedure) {
        List<String> lines = new ArrayList<>(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MAIN.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION."));
        lines.addAll(storage);
        lines.add("PROCEDURE DIVISION.");
        lines.add("MAIN-START.");
        lines.addAll(List.of(procedure));
        return run(List.of(lines)).returnCode();
    }

    /** 複数のプログラムを同じローダへ読み込み、最初のものを実行する。 */
    private static ProgramContext run(List<List<String>> programs) {
        GeneratedLoader loader = new GeneratedLoader();
        List<CobolProgram> loaded = new ArrayList<>();
        for (List<String> lines : programs) {
            CobolCompiler.Result result =
                    CobolCompiler.standard().compile("PGM.cbl", source(lines));
            assertTrue(result.succeeded(),
                    () -> "unexpected diagnostics: " + result.diagnostics());
            try {
                Class<?> type = loader.define(result.className(), result.classFile());
                loaded.add((CobolProgram) type.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot load the generated program", e);
            }
        }
        ProgramContext context = ProgramContext.standard();
        loaded.get(0).runFresh(context);
        return context;
    }

    @Test
    @DisplayName("RETURN-CODE は書き込める (FR-084)")
    void theRegisterCanBeSet() {
        assertEquals(8, runOne(List.of(), "MOVE 8 TO RETURN-CODE."));
    }

    @Test
    @DisplayName("何も書かなければ 0 のままである (FR-084)")
    void itStartsAtZero() {
        assertEquals(0, runOne(List.of(), "CONTINUE."));
    }

    @Test
    @DisplayName("算術文の受取項目にできる (FR-084)")
    void itCanReceiveArithmetic() {
        assertEquals(12, runOne(List.of(), "MOVE 4 TO RETURN-CODE", "ADD 8 TO RETURN-CODE."));
    }

    @Test
    @DisplayName("読み出して比べられる (FR-084)")
    void itCanBeRead() {
        // 8 を入れてから条件で見る。読めていなければ 99 になる
        assertEquals(1, runOne(List.of(),
                "MOVE 8 TO RETURN-CODE",
                "IF RETURN-CODE = 8",
                "    MOVE 1 TO RETURN-CODE",
                "ELSE",
                "    MOVE 99 TO RETURN-CODE",
                "END-IF."));
    }

    @Test
    @DisplayName("負の値も入る (FR-084)")
    void itHoldsNegativeValues() {
        assertEquals(-1, runOne(
                List.of("01 WS-N PIC S9(4) VALUE -1."), "MOVE WS-N TO RETURN-CODE."));
    }

    @Test
    @DisplayName("データ項目へ写せる (FR-084)")
    void itCanBeMovedIntoADataItem() {
        List<String> lines = List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MAIN.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-N PIC 9(4).",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    MOVE 42 TO RETURN-CODE",
                "    MOVE RETURN-CODE TO WS-N",
                "    DISPLAY WS-N.");
        GeneratedLoader loader = new GeneratedLoader();
        CobolCompiler.Result result =
                CobolCompiler.standard().compile("PGM.cbl", source(lines));
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        try {
            Class<?> type = loader.define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            assertEquals("0042", CodePages.DEFAULT.decode(program.runFresh().array()));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
    }

    @Test
    @DisplayName("呼ばれた側が入れた値を呼ぶ側が見る (FR-084)")
    void theCalleeSetsWhatTheCallerSees() {
        assertEquals(5, run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'RCSUB'."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. RCSUB.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    MOVE 5 TO RETURN-CODE",
                        "    GOBACK."))).returnCode());
    }

    @Test
    @DisplayName("呼ぶ側が入れた値を呼ばれた側が見る (FR-084)")
    void theCalleeSeesWhatTheCallerSet() {
        // 呼ぶ側の 3 を読んで倍にする。写し取る仕組みなら 0 が見えてしまう
        assertEquals(6, run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    MOVE 3 TO RETURN-CODE",
                        "    CALL 'RCDOUBLE'."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. RCDOUBLE.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    ADD RETURN-CODE TO RETURN-CODE",
                        "    GOBACK."))).returnCode());
    }

    @Test
    @DisplayName("同じ名前をデータ部に書けばそちらが勝つ (FR-084)")
    void aDeclaredItemOfTheSameNameWins() {
        // 特殊レジスタを探すのは、データ項目が見つからなかったときだけである
        assertEquals(0, runOne(
                List.of("01 RETURN-CODE PIC 9(4)."), "MOVE 7 TO RETURN-CODE."));
    }
}
