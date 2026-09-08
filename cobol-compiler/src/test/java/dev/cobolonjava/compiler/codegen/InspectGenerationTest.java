package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@code INSPECT} を翻訳して実行し、記憶域の中身で確かめる。 */
@Tag("V1")
class InspectGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(InspectGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static CobolCompiler.Result compile(List<String> storage, String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.")) {
            sb.append("       ").append(line).append('\n');
        }
        for (String line : storage) {
            sb.append("       ").append(line).append('\n');
        }
        sb.append("       PROCEDURE DIVISION.\n");
        for (String line : procedure) {
            sb.append("       ").append(line).append('\n');
        }
        return CobolCompiler.standard().compile(FILE, sb.toString());
    }

    private static String run(List<String> storage, String... procedure) {
        CobolCompiler.Result result = compile(storage, procedure);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            Storage executed = program.runFresh();
            return CodePages.DEFAULT.decode(executed.array());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
    }

    @Test
    @DisplayName("TALLYING FOR ALL は一致の数を数える (FR-065)")
    void tallyingForAllCountsMatches() {
        assertEquals("ABABA02", run(
                List.of("01 WS-D PIC X(5) VALUE 'ABABA'.", "01 WS-N PIC 9(2) VALUE 0."),
                "INSPECT WS-D TALLYING WS-N FOR ALL 'AB'."));
    }

    @Test
    @DisplayName("数は計数の項目へ足し込まれる (FR-065)")
    void theCountIsAddedToTheCounter() {
        // 0 に代入するのではなく足し込む。COBOL は初期化を書き手に任せている
        assertEquals("ABABA12", run(
                List.of("01 WS-D PIC X(5) VALUE 'ABABA'.", "01 WS-N PIC 9(2) VALUE 10."),
                "INSPECT WS-D TALLYING WS-N FOR ALL 'AB'."));
    }

    @Test
    @DisplayName("句は書かれた順に試される (FR-065, P-017)")
    void clausesAreTriedInWrittenOrder() {
        // 独立に適用すると WS-M も 1 になってしまう
        assertEquals("AB0100", run(
                List.of("01 WS-D PIC X(2) VALUE 'AB'.",
                        "01 WS-N PIC 9(2) VALUE 0.",
                        "01 WS-M PIC 9(2) VALUE 0."),
                "INSPECT WS-D TALLYING WS-N FOR ALL 'AB' WS-M FOR ALL 'B'."));
    }

    @Test
    @DisplayName("1 つの計数に複数の指定を並べられる (FR-065)")
    void oneCounterMayHaveSeveralSpecs() {
        // A が 2 つ、B が 2 つで合わせて 4
        assertEquals("ABAB04", run(
                List.of("01 WS-D PIC X(4) VALUE 'ABAB'.", "01 WS-N PIC 9(2) VALUE 0."),
                "INSPECT WS-D TALLYING WS-N FOR ALL 'A' ALL 'B'."));
    }

    @Test
    @DisplayName("LEADING は先頭の連なりだけを数える (FR-065)")
    void leadingCountsTheRunOnly() {
        assertEquals("AABAA02", run(
                List.of("01 WS-D PIC X(5) VALUE 'AABAA'.", "01 WS-N PIC 9(2) VALUE 0."),
                "INSPECT WS-D TALLYING WS-N FOR LEADING 'A'."));
    }

    @Test
    @DisplayName("CHARACTERS は範囲内の文字数を数える (FR-065)")
    void charactersCountsEveryByte() {
        assertEquals("ABCDE05", run(
                List.of("01 WS-D PIC X(5) VALUE 'ABCDE'.", "01 WS-N PIC 9(2) VALUE 0."),
                "INSPECT WS-D TALLYING WS-N FOR CHARACTERS."));
    }

    @Test
    @DisplayName("BEFORE と AFTER が範囲を絞る (FR-065)")
    void beforeAndAfterNarrowTheRegion() {
        assertEquals("A-A-A0102", run(
                List.of("01 WS-D PIC X(5) VALUE 'A-A-A'.",
                        "01 WS-N PIC 9(2) VALUE 0.",
                        "01 WS-M PIC 9(2) VALUE 0."),
                "INSPECT WS-D TALLYING WS-N FOR ALL 'A' BEFORE INITIAL '-'",
                "                          WS-M FOR ALL 'A' AFTER INITIAL '-'."));
    }

    @Test
    @DisplayName("AFTER の区切りが見つからなければ検査しない (FR-065)")
    void anAbsentAfterDelimiterInspectsNothing() {
        // 「全体を検査する」のではない。取り違えると意図しない置換が起きる
        assertEquals("AAA00", run(
                List.of("01 WS-D PIC X(3) VALUE 'AAA'.", "01 WS-N PIC 9(2) VALUE 0."),
                "INSPECT WS-D TALLYING WS-N FOR ALL 'A' AFTER INITIAL 'Z'."));
    }

    @Test
    @DisplayName("REPLACING ALL は一致をすべて置き換える (FR-065)")
    void replacingAllRewritesEveryMatch() {
        assertEquals("XBXBX", run(
                List.of("01 WS-D PIC X(5) VALUE 'ABABA'."),
                "INSPECT WS-D REPLACING ALL 'A' BY 'X'."));
    }

    @Test
    @DisplayName("REPLACING FIRST は最初の一致だけを置き換える (FR-065)")
    void replacingFirstRewritesOnce() {
        assertEquals("XBABA", run(
                List.of("01 WS-D PIC X(5) VALUE 'ABABA'."),
                "INSPECT WS-D REPLACING FIRST 'A' BY 'X'."));
    }

    @Test
    @DisplayName("REPLACING CHARACTERS は範囲を埋める (FR-065)")
    void replacingCharactersFillsTheRegion() {
        assertEquals("***DE", run(
                List.of("01 WS-D PIC X(5) VALUE 'ABCDE'."),
                "INSPECT WS-D REPLACING CHARACTERS BY '*' BEFORE INITIAL 'D'."));
    }

    @Test
    @DisplayName("数える句と置き換える句を同じ文に書ける (FR-065)")
    void tallyingAndReplacingShareOneStatement() {
        assertEquals("XBXBX03", run(
                List.of("01 WS-D PIC X(5) VALUE 'ABABA'.", "01 WS-N PIC 9(2) VALUE 0."),
                "INSPECT WS-D TALLYING WS-N FOR ALL 'A'",
                "             REPLACING ALL 'A' BY 'X'."));
    }

    @Test
    @DisplayName("CONVERTING は 1 バイトずつ読み替える (FR-065)")
    void convertingRewritesByteByByte() {
        assertEquals("ABCXY", run(
                List.of("01 WS-D PIC X(5) VALUE 'ABCAB'."),
                "INSPECT WS-D CONVERTING 'ABC' TO 'XYZ'",
                "             AFTER INITIAL 'C'."));
    }

    @Test
    @DisplayName("照合する並びをデータ項目で書ける (FR-065)")
    void thePatternMayBeADataItem() {
        assertEquals("AABAA02A", run(
                List.of("01 WS-D PIC X(5) VALUE 'AABAA'.",
                        "01 WS-N PIC 9(2) VALUE 0.",
                        "01 WS-P PIC X VALUE 'A'."),
                "INSPECT WS-D TALLYING WS-N FOR LEADING WS-P."));
    }

    @Test
    @DisplayName("ALL / LEADING は、そのあとの被演算子すべてに効く (FR-065)")
    void allAndLeadingCarryOverToLaterOperands() {
        // NC216A が「FOR LEADING "S" AFTER WS-Y "S" AFTER "U" ...」と 4 組を並べている。
        // ここでは A と B を 1 つの ALL で並べる。数えるのは 3 個 (A A B) である
        assertEquals("AABAA03", run(
                List.of("01 WS-D PIC X(5) VALUE 'AABAA'.",
                        "01 WS-N PIC 9(2) VALUE 0."),
                "INSPECT WS-D TALLYING WS-N FOR ALL 'A' BEFORE 'B' 'B'."));
    }

    @Test
    @DisplayName("REPLACING でも指定は後ろへ効く (FR-065)")
    void replacingCarriesOverToLaterOperands() {
        assertEquals("XXYXX", run(
                List.of("01 WS-D PIC X(5) VALUE 'AABAA'."),
                "INSPECT WS-D REPLACING ALL 'A' BY 'X' 'B' BY 'Y'."));
    }

    @Test
    @DisplayName("数値でない計数は誤りとして報告する (FR-065)")
    void aNonNumericCounterIsReported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-D PIC X(3).", "01 WS-N PIC X(3)."),
                "INSPECT WS-D TALLYING WS-N FOR CHARACTERS.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("numeric counter"),
                result.diagnostics().toString());
    }
}
