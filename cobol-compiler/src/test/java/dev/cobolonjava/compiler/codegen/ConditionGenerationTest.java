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
 * 条件と {@code IF} を翻訳して実行し、どちらの枝を通ったかを記憶域で確かめる。
 *
 * <p>どの試験も {@code WS-R} という 1 バイトの印を置き、通った枝で書き分ける。
 */
@Tag("V1")
class ConditionGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(ConditionGenerationTest.class.getClassLoader());
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

    /** 数値 2 個と 1 バイトの印。印は条件が成り立てば {@code T}、成り立たなければ {@code F}。 */
    private static String branchTaken(String condition) {
        return run(List.of("01 WS-A PIC 9(3) VALUE 010.",
                        "01 WS-B PIC 9(3) VALUE 020.",
                        "01 WS-R PIC X."),
                "IF " + condition,
                "    MOVE 'T' TO WS-R",
                "ELSE",
                "    MOVE 'F' TO WS-R",
                "END-IF.").substring(6);
    }

    @Test
    @DisplayName("関係条件は記号でも語でも書ける (FR-046)")
    void aRelationCanBeWrittenWithSymbolsOrWords() {
        assertEquals("T", branchTaken("WS-A < WS-B"));
        assertEquals("T", branchTaken("WS-A IS LESS THAN WS-B"));
        assertEquals("F", branchTaken("WS-A > WS-B"));
        assertEquals("F", branchTaken("WS-A IS GREATER THAN WS-B"));
        assertEquals("T", branchTaken("WS-A NOT = WS-B"));
        assertEquals("T", branchTaken("WS-A <= WS-B"));
        assertEquals("T", branchTaken("WS-A IS LESS THAN OR EQUAL TO WS-B"));
    }

    @Test
    @DisplayName("数値比較は内部表現の違いに影響されない (FR-046)")
    void numericComparisonIgnoresTheInternalRepresentation() {
        // 10 は DISPLAY でも COMP-3 でも 10 である
        assertEquals("T", run(List.of(
                        "01 WS-A PIC 9(3) VALUE 010.",
                        "01 WS-B PIC 9(5) COMP-3 VALUE 10.",
                        "01 WS-R PIC X."),
                "IF WS-A = WS-B MOVE 'T' TO WS-R ELSE MOVE 'F' TO WS-R END-IF.").substring(6));
    }

    @Test
    @DisplayName("英数字比較はコードページの照合順序による (FR-046, FR-053)")
    void alphanumericComparisonFollowsTheCodePage() {
        // EBCDIC では英字が数字より小さい。ASCII とは逆である
        assertEquals("T", run(List.of(
                        "01 WS-A PIC X VALUE 'A'.",
                        "01 WS-B PIC X VALUE '1'.",
                        "01 WS-R PIC X."),
                "IF WS-A < WS-B MOVE 'T' TO WS-R ELSE MOVE 'F' TO WS-R END-IF.").substring(2));
    }

    @Test
    @DisplayName("AND と OR は短絡する形で組み立てる (FR-046)")
    void andAndOrAreCombined() {
        assertEquals("T", branchTaken("WS-A < WS-B AND WS-B > 0"));
        assertEquals("F", branchTaken("WS-A > WS-B AND WS-B > 0"));
        assertEquals("T", branchTaken("WS-A > WS-B OR WS-B > 0"));
        assertEquals("F", branchTaken("WS-A > WS-B OR WS-B < 0"));
    }

    @Test
    @DisplayName("NOT は条件を反転する (FR-046)")
    void notInvertsItsCondition() {
        assertEquals("F", branchTaken("NOT WS-A < WS-B"));
        assertEquals("T", branchTaken("NOT (WS-A > WS-B AND WS-B > 0)"));
    }

    @Test
    @DisplayName("括弧で結び付きを変えられる (FR-046)")
    void parenthesesChangeTheGrouping() {
        // AND は OR より強く結び付く。括弧がなければ真になる
        assertEquals("T", branchTaken("WS-A > WS-B AND WS-B > 0 OR WS-A < WS-B"));
        assertEquals("F", branchTaken("WS-A > WS-B AND (WS-B > 0 OR WS-A < WS-B)"));
    }

    @Test
    @DisplayName("符号条件はゼロとの比較になる (FR-046)")
    void aSignConditionComparesWithZero() {
        assertEquals("T", branchTaken("WS-A IS POSITIVE"));
        assertEquals("F", branchTaken("WS-A IS NEGATIVE"));
        assertEquals("F", branchTaken("WS-A IS ZERO"));
        assertEquals("T", branchTaken("WS-A IS NOT ZERO"));
    }

    @Test
    @DisplayName("条件名は親の項目と値の比較へ展開される (FR-022, FR-046)")
    void aConditionNameExpandsToComparisonsOnItsParent() {
        List<String> storage = List.of(
                "01 WS-FLAG PIC X VALUE 'Y'.",
                "   88 WS-YES VALUE 'Y'.",
                "   88 WS-NO  VALUE 'N' 'n'.",
                "   88 WS-DIGIT VALUE '0' THRU '9'.",
                "01 WS-R PIC X.");

        assertEquals("T", run(storage,
                "IF WS-YES MOVE 'T' TO WS-R ELSE MOVE 'F' TO WS-R END-IF.").substring(1));
        assertEquals("F", run(storage,
                "IF WS-NO MOVE 'T' TO WS-R ELSE MOVE 'F' TO WS-R END-IF.").substring(1));
        assertEquals("F", run(storage,
                "IF WS-DIGIT MOVE 'T' TO WS-R ELSE MOVE 'F' TO WS-R END-IF.").substring(1));
        assertEquals("T", run(List.of(
                        "01 WS-FLAG PIC X VALUE '5'.",
                        "   88 WS-DIGIT VALUE '0' THRU '9'.",
                        "01 WS-R PIC X."),
                "IF WS-DIGIT MOVE 'T' TO WS-R ELSE MOVE 'F' TO WS-R END-IF.").substring(1));
    }

    @Test
    @DisplayName("ELSE がなくても書ける (FR-061)")
    void theElseBranchIsOptional() {
        assertEquals("40", java.util.HexFormat.of().withUpperCase().formatHex(
                        CodePages.DEFAULT.encode(run(
                                List.of("01 WS-A PIC 9 VALUE 1.", "01 WS-R PIC X."),
                                "IF WS-A > 5 MOVE 'T' TO WS-R END-IF.").substring(1))),
                "条件が成り立たなければ印は空白のままである");
    }

    @Test
    @DisplayName("END-IF がなければ本体は終止符まで続く (FR-061)")
    void withoutEndIfTheBodyRunsToThePeriod() {
        // 2 つの MOVE がどちらも IF の本体に入る
        assertEquals("TT", run(
                List.of("01 WS-A PIC 9 VALUE 1.", "01 WS-R PIC X.", "01 WS-S PIC X."),
                "IF WS-A = 1 MOVE 'T' TO WS-R MOVE 'T' TO WS-S.").substring(1));
    }

    @Test
    @DisplayName("IF は入れ子にでき、ELSE は内側に付く (FR-061)")
    void ifStatementsNestAndElseBindsToTheInnermost() {
        assertEquals("I", run(
                List.of("01 WS-A PIC 9 VALUE 1.", "01 WS-B PIC 9 VALUE 2.", "01 WS-R PIC X."),
                "IF WS-A = 1",
                "    IF WS-B = 9",
                "        MOVE 'O' TO WS-R",
                "    ELSE",
                "        MOVE 'I' TO WS-R",
                "    END-IF",
                "END-IF.").substring(2));
    }

    @Test
    @DisplayName("CONTINUE は何もしない (FR-061)")
    void continueDoesNothing() {
        assertEquals("40", java.util.HexFormat.of().withUpperCase().formatHex(
                CodePages.DEFAULT.encode(run(
                        List.of("01 WS-R PIC X."), "IF WS-R = ' ' CONTINUE END-IF."))));
    }

    @Test
    @DisplayName("定義のない条件名は誤りとして報告する (FR-022)")
    void anUndefinedConditionNameIsReported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-R PIC X."), "IF WS-NOPE MOVE 'T' TO WS-R END-IF.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("condition-name"),
                result.diagnostics().toString());
    }
}
