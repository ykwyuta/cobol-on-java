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

/**
 * {@code INITIALIZE} と {@code SET 条件名 TO TRUE} (要件 FR-060, FR-068)。
 *
 * <p>{@code INITIALIZE} は配下の基本項目それぞれへの転記の集まりだが、入る値が翻訳時に
 * 決まるので<b>書き込むバイト列も決まる</b>。まとめて書いた結果が、1 個ずつ転記した結果と
 * 同じであることを見る。
 */
@Tag("V1")
class InitializeGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(InitializeGenerationTest.class.getClassLoader());
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
    @DisplayName("数値にはゼロ、英数字には空白が入る (FR-060)")
    void eachCategoryGetsItsDefault() {
        assertEquals("   " + "000", run(
                List.of("01 WS-R.",
                        "   05 WS-A PIC X(3) VALUE 'abc'.",
                        "   05 WS-N PIC 9(3) VALUE 123."),
                "INITIALIZE WS-R."));
    }

    @Test
    @DisplayName("数字編集項目には編集した結果が入る (FR-033, FR-060)")
    void anEditedItemGetsTheEditedZero() {
        // PIC ZZ9.99 にゼロを転記すると "  0.00" になる。ゼロで埋めるのとは違う。
        // 数字編集項目の VALUE は英数字定数で書く
        assertEquals("  0.00", run(
                List.of("01 WS-E PIC ZZ9.99 VALUE '12.34'."),
                "INITIALIZE WS-E."));
    }

    @Test
    @DisplayName("パック 10 進にはゼロの表現が入る (FR-031, FR-060)")
    void aPackedItemGetsItsOwnZero() {
        // S9(3) COMP-3 の 0 は 00 0C である。ゼロで埋めるのとは違う
        CobolCompiler.Result result = compile(
                List.of("01 WS-P PIC S9(3) COMP-3 VALUE 123."),
                "INITIALIZE WS-P.");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            assertEquals("000C", java.util.HexFormat.of().withUpperCase()
                    .formatHex(program.runFresh().array()));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
    }

    @Test
    @DisplayName("表はすべての回が初期化される (FR-024, FR-060)")
    void everyOccurrenceIsInitialized() {
        assertEquals("000000000", run(
                List.of("01 WS-T.",
                        "   05 WS-E OCCURS 3 TIMES PIC 9(3) VALUE 999."),
                "INITIALIZE WS-T."));
    }

    @Test
    @DisplayName("FILLER は初期化しない (FR-060)")
    void fillerIsLeftAlone() {
        assertEquals("   " + "---" + "000", run(
                List.of("01 WS-R.",
                        "   05 WS-A PIC X(3) VALUE 'abc'.",
                        "   05 FILLER PIC X(3) VALUE '---'.",
                        "   05 WS-N PIC 9(3) VALUE 123."),
                "INITIALIZE WS-R."));
    }

    @Test
    @DisplayName("WITH FILLER を書けば FILLER も初期化する (FR-060)")
    void withFillerIncludesIt() {
        assertEquals("   " + "   " + "000", run(
                List.of("01 WS-R.",
                        "   05 WS-A PIC X(3) VALUE 'abc'.",
                        "   05 FILLER PIC X(3) VALUE '---'.",
                        "   05 WS-N PIC 9(3) VALUE 123."),
                "INITIALIZE WS-R WITH FILLER."));
    }

    @Test
    @DisplayName("REDEFINES で重ねた項目は初期化しない (FR-021, FR-060)")
    void aRedefiningItemIsNotInitialized() {
        // 重ねる先を初期化すれば同じバイトが変わる。二重に書かない
        assertEquals("000" + "   ", run(
                List.of("01 WS-R.",
                        "   05 WS-N PIC 9(3) VALUE 123.",
                        "   05 WS-X REDEFINES WS-N PIC X(3).",
                        "   05 WS-A PIC X(3) VALUE 'abc'."),
                "INITIALIZE WS-R."));
    }

    @Test
    @DisplayName("基本項目そのものも初期化できる (FR-060)")
    void anElementaryItemMayBeInitialized() {
        assertEquals("000" + "abc", run(
                List.of("01 WS-N PIC 9(3) VALUE 123.", "01 WS-A PIC X(3) VALUE 'abc'."),
                "INITIALIZE WS-N."));
    }

    @Test
    @DisplayName("項目は複数書ける (FR-060)")
    void severalItemsMayBeInitialized() {
        assertEquals("000" + "   ", run(
                List.of("01 WS-N PIC 9(3) VALUE 123.", "01 WS-A PIC X(3) VALUE 'abc'."),
                "INITIALIZE WS-N WS-A."));
    }

    @Test
    @DisplayName("REPLACING は分類ごとに入れる値を変える (FR-060)")
    void replacingChangesTheValuePerCategory() {
        assertEquals("abc" + "007", run(
                List.of("01 WS-R.",
                        "   05 WS-A PIC X(3).",
                        "   05 WS-N PIC 9(3)."),
                "INITIALIZE WS-R",
                "    REPLACING ALPHANUMERIC DATA BY 'abc'",
                "              NUMERIC DATA BY 7."));
    }

    @Test
    @DisplayName("REPLACING に当たらない項目は変えない (FR-060)")
    void replacingLeavesUnmatchedItemsAlone() {
        // 英数字だけを書き換える。数値は元のままである
        assertEquals("abc" + "123", run(
                List.of("01 WS-R.",
                        "   05 WS-A PIC X(3) VALUE 'xyz'.",
                        "   05 WS-N PIC 9(3) VALUE 123."),
                "INITIALIZE WS-R REPLACING ALPHANUMERIC DATA BY 'abc'."));
    }

    @Test
    @DisplayName("添字を書いた表の 1 回分だけを初期化できる (FR-024, FR-060)")
    void oneOccurrenceMayBeInitialized() {
        assertEquals("999" + "000" + "999", run(
                List.of("01 WS-T.",
                        "   05 WS-E OCCURS 3 TIMES PIC 9(3) VALUE 999."),
                "INITIALIZE WS-E (2)."));
    }

    @Test
    @DisplayName("SET 条件名 TO TRUE は親へその値を入れる (FR-068)")
    void setToTrueAssignsTheConditionValue() {
        assertEquals("Y", run(
                List.of("01 WS-F PIC X VALUE 'N'.", "   88 WS-DONE VALUE 'Y'."),
                "SET WS-DONE TO TRUE."));
    }

    @Test
    @DisplayName("値が複数あれば最初のものを入れる (FR-068)")
    void theFirstValueIsUsed() {
        assertEquals("A", run(
                List.of("01 WS-F PIC X VALUE 'N'.", "   88 WS-OK VALUE 'A' 'B'."),
                "SET WS-OK TO TRUE."));
    }

    @Test
    @DisplayName("SET したあとはその条件が成り立つ (FR-068)")
    void theConditionHoldsAfterwards() {
        assertEquals("Y" + "1", run(
                List.of("01 WS-F PIC X VALUE 'N'.",
                        "   88 WS-DONE VALUE 'Y'.",
                        "01 WS-N PIC 9 VALUE 0."),
                "SET WS-DONE TO TRUE",
                "IF WS-DONE",
                "    MOVE 1 TO WS-N",
                "END-IF."));
    }

    @Test
    @DisplayName("条件名は複数書ける (FR-068)")
    void severalConditionNamesMayBeSet() {
        assertEquals("Y" + "Z", run(
                List.of("01 WS-A PIC X VALUE 'N'.",
                        "   88 WS-A-ON VALUE 'Y'.",
                        "01 WS-B PIC X VALUE 'N'.",
                        "   88 WS-B-ON VALUE 'Z'."),
                "SET WS-A-ON WS-B-ON TO TRUE."));
    }

    @Test
    @DisplayName("定義のない条件名は誤りとして報告する (FR-068)")
    void anUndefinedConditionNameIsReported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-F PIC X."), "SET NO-SUCH TO TRUE.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("undefined condition-name"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("REPLACING BY にデータ項目を書ける (FR-060)")
    void replacingByADataItemMovesItsValue() {
        // 値が実行時に決まるので、まとめて 1 回では書けない。基本項目ごとの転記になる
        assertEquals("123123456", run(
                List.of("01 WS-N PIC 9(3) VALUE 123.",
                        "01 WS-R.",
                        "   05 WS-M PIC 9(3).",
                        "   05 WS-T PIC X(3) VALUE '456'."),
                "INITIALIZE WS-R REPLACING NUMERIC DATA BY WS-N."));
    }

    @Test
    @DisplayName("REPLACING BY データ項目は、反復のある項目も 1 回ずつ埋める (FR-060)")
    void replacingByADataItemFillsEveryOccurrence() {
        assertEquals("07007007007", run(
                List.of("01 WS-N PIC 9(2) VALUE 7.",
                        "01 WS-R.",
                        "   05 WS-E OCCURS 3 TIMES PIC 9(3)."),
                "INITIALIZE WS-R REPLACING NUMERIC DATA BY WS-N."));
    }

    @Test
    @DisplayName("当たらなかった分類は変わらない (FR-060)")
    void categoriesNotNamedAreLeftAlone() {
        assertEquals("123XYZ123", run(
                List.of("01 WS-N PIC 9(3) VALUE 123.",
                        "01 WS-R.",
                        "   05 WS-T PIC X(3) VALUE 'XYZ'.",
                        "   05 WS-M PIC 9(3)."),
                "INITIALIZE WS-R REPLACING NUMERIC DATA BY WS-N."));
    }
}
