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
 * {@code GO TO} を翻訳して実行し、どの段落をどの順で通ったかを記憶域で確かめる。
 *
 * <p>段落は「次にどこへ行くか」を返すメソッドとして生成される。{@code -1} なら最後まで
 * 流れたということであり、0 以上なら {@code GO TO} で飛んだ先である。
 */
@Tag("V1")
class GoToGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(GoToGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static CobolCompiler.Result compile(List<String> storage, String... procedure) {
        return CobolCompiler.standard().compile(FILE,
                FixedFormatSource.program(storage, procedure));
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

    /** どの段落を通ったかが値で分かるように、段落ごとに違う桁を足す。 */
    private static final List<String> COUNTER = List.of("01 WS-N PIC 9(3) VALUE 0.");

    @Test
    @DisplayName("GO TO は段落の残りを飛ばして飛び先へ移る (FR-061)")
    void goToSkipsTheRestOfItsParagraph() {
        // 1 + 100。あいだの SECOND を通っていれば 111 になる
        assertEquals("101", run(COUNTER,
                "MAIN-START.",
                "    ADD 1 TO WS-N",
                "    GO TO THIRD.",
                "SECOND.",
                "    ADD 10 TO WS-N.",
                "THIRD.",
                "    ADD 100 TO WS-N."));
    }

    @Test
    @DisplayName("TO は省略できる (FR-061)")
    void theToKeywordIsOptional() {
        assertEquals("101", run(COUNTER,
                "MAIN-START.",
                "    ADD 1 TO WS-N",
                "    GO THIRD.",
                "SECOND.",
                "    ADD 10 TO WS-N.",
                "THIRD.",
                "    ADD 100 TO WS-N."));
    }

    @Test
    @DisplayName("GO TO で飛んだ先からは素直に流れ続ける (FR-061)")
    void executionFallsOnwardFromTheTarget() {
        // THIRD から FOURTH へ流れ込む
        assertEquals("1101", run(
                List.of("01 WS-N PIC 9(4) VALUE 0."),
                "MAIN-START.",
                "    ADD 1 TO WS-N",
                "    GO TO THIRD.",
                "SECOND.",
                "    ADD 10 TO WS-N.",
                "THIRD.",
                "    ADD 100 TO WS-N.",
                "FOURTH.",
                "    ADD 1000 TO WS-N."));
    }

    @Test
    @DisplayName("後ろへ飛べば繰り返しになる (FR-061)")
    void aBackwardGoToMakesALoop() {
        // 5 まで数えてから DONE-P へ流れる
        assertEquals("105", run(COUNTER,
                "LOOP-TOP.",
                "    ADD 1 TO WS-N",
                "    IF WS-N < 5",
                "        GO TO LOOP-TOP",
                "    END-IF.",
                "DONE-P.",
                "    ADD 100 TO WS-N."));
    }

    @Test
    @DisplayName("その場に書いた PERFORM の中からでも飛べる (FR-061)")
    void aGoToLeavesAnInlinePerform() {
        // 3 まで数えたところで抜ける。ADD 100 は通らない
        assertEquals("013", run(COUNTER,
                "MAIN-START.",
                "    PERFORM 10 TIMES",
                "        ADD 1 TO WS-N",
                "        IF WS-N = 3",
                "            GO TO AFTER-P",
                "        END-IF",
                "    END-PERFORM",
                "    ADD 100 TO WS-N.",
                "AFTER-P.",
                "    ADD 10 TO WS-N."));
    }

    @Test
    @DisplayName("PERFORM した範囲の中でも飛べる (FR-061)")
    void aGoToWorksInsideAPerformedRange() {
        // A-P で 1 を足して C-P へ飛ぶ。B-P の 10 は通らない。C-P の終わりで PERFORM が戻る
        assertEquals("103", run(COUNTER,
                "MAIN-START.",
                "    PERFORM A-P THRU C-P",
                "    ADD 100 TO WS-N",
                "    STOP RUN.",
                "A-P.",
                "    ADD 1 TO WS-N",
                "    GO TO C-P.",
                "B-P.",
                "    ADD 10 TO WS-N.",
                "C-P.",
                "    ADD 2 TO WS-N."));
    }

    @Test
    @DisplayName("EXIT は何もしない (FR-061)")
    void exitDoesNothing() {
        // PERFORM ... THRU の範囲の終わりに置く定石である
        assertEquals("101", run(COUNTER,
                "MAIN-START.",
                "    PERFORM A-P THRU A-EXIT",
                "    ADD 100 TO WS-N",
                "    STOP RUN.",
                "A-P.",
                "    ADD 1 TO WS-N",
                "    IF WS-N > 0",
                "        GO TO A-EXIT",
                "    END-IF",
                "    ADD 10 TO WS-N.",
                "A-EXIT.",
                "    EXIT."));
    }

    @Test
    @DisplayName("PERFORM が戻るのは範囲の最後の段落を流れきったときだけ (FR-061)")
    void aPerformReturnsOnlyAtTheEndOfItsRange() {
        // B-P へ飛んでも PERFORM は戻らない。C-P を流れきってはじめて戻る
        assertEquals("113", run(COUNTER,
                "MAIN-START.",
                "    PERFORM A-P THRU C-P",
                "    ADD 100 TO WS-N",
                "    STOP RUN.",
                "A-P.",
                "    ADD 1 TO WS-N",
                "    GO TO B-P.",
                "B-P.",
                "    ADD 10 TO WS-N.",
                "C-P.",
                "    ADD 2 TO WS-N."));
    }

    @Test
    @DisplayName("段落の並びから外れたら実行は終わる (FR-061)")
    void runningOffTheLastParagraphEndsTheProgram() {
        assertEquals("001", run(COUNTER,
                "MAIN-START.",
                "    GO TO LAST-P.",
                "SECOND.",
                "    ADD 10 TO WS-N.",
                "LAST-P.",
                "    ADD 1 TO WS-N."));
    }

    @Test
    @DisplayName("範囲の外へ出たまま最後まで流れたら暗黙の STOP RUN になる (FR-061)")
    void fallingOffTheEndOutsideThePerformedRangeStopsTheRun() {
        // PERFORM の範囲は A-P だけ。LAST-P を流れきっても PERFORM へは戻らない。
        // 戻っていれば ADD 100 が通って 111 になる
        assertEquals("011", run(COUNTER,
                "MAIN-START.",
                "    PERFORM A-P THRU A-P",
                "    ADD 100 TO WS-N",
                "    STOP RUN.",
                "A-P.",
                "    ADD 1 TO WS-N",
                "    GO TO LAST-P.",
                "LAST-P.",
                "    ADD 10 TO WS-N."));
    }

    private static final List<String> SELECTOR = List.of(
            "01 WS-N PIC 9(3) VALUE 0.",
            "01 WS-K PIC 9 VALUE 0.");

    /** {@code GO TO ... DEPENDING ON} を、選ぶ値を変えながら流す。 */
    private static String depending(int selector) {
        return run(SELECTOR,
                "MAIN-START.",
                "    MOVE " + selector + " TO WS-K",
                "    GO TO A-P B-P C-P DEPENDING ON WS-K",
                "    ADD 7 TO WS-N",
                "    STOP RUN.",
                "A-P.",
                "    ADD 1 TO WS-N",
                "    STOP RUN.",
                "B-P.",
                "    ADD 2 TO WS-N",
                "    STOP RUN.",
                "C-P.",
                "    ADD 3 TO WS-N",
                "    STOP RUN.").substring(0, 3);
    }

    @Test
    @DisplayName("GO TO ... DEPENDING ON は何番目かで飛び先を選ぶ (FR-063)")
    void goToDependingPicksTheNthTarget() {
        assertEquals("001", depending(1));
        assertEquals("002", depending(2));
        assertEquals("003", depending(3));
    }

    @Test
    @DisplayName("並びの外なら飛ばずに次の文へ進む (FR-063)")
    void avalueOutsideTheListFallsThrough() {
        // 誤りにはならない。規格がそう決めている
        assertEquals("007", depending(0));
        assertEquals("007", depending(4));
    }

    @Test
    @DisplayName("DEPENDING ON がなければ飛び先は 1 つである (FR-061)")
    void withoutDependingOnlyOneTargetIsAllowed() {
        CobolCompiler.Result result = compile(COUNTER,
                "MAIN-START.",
                "    GO TO A-P B-P.",
                "A-P.",
                "    ADD 1 TO WS-N.",
                "B-P.",
                "    ADD 2 TO WS-N.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("one procedure name"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("手続き名は数字だけでもよい (FR-061)")
    void aProcedureNameMayBeAllDigits() {
        // データ名と違うところである。段分けの章は「00 SECTION 00.」と書かれる
        assertEquals("101", run(COUNTER,
                "MAIN-START.",
                "    ADD 1 TO WS-N",
                "    GO TO 50.",
                "20.",
                "    ADD 10 TO WS-N.",
                "50.",
                "    ADD 100 TO WS-N."));
    }

    @Test
    @DisplayName("章に段番号を書ける (FR-061, 暫定判断 P-066)")
    void aSectionMayCarryASegmentNumber() {
        assertEquals("101", run(COUNTER,
                "MAIN-START SECTION 00.",
                "FIRST-P.",
                "    ADD 1 TO WS-N",
                "    GO TO THIRD-P.",
                "SECOND-S SECTION 50.",
                "SECOND-P.",
                "    ADD 10 TO WS-N.",
                "THIRD-S SECTION 99.",
                "THIRD-P.",
                "    ADD 100 TO WS-N."));
    }

    @Test
    @DisplayName("ALTER は GO TO だけの段落の飛び先を書き換える (FR-063)")
    void alterChangesWhereAGoToLeads() {
        // 1 回目は B-P へ、書き換えたあとは C-P へ飛ぶ
        assertEquals("0021", run(
                List.of("01 WS-N PIC 9(4) VALUE 0."),
                "MAIN-START.",
                "    PERFORM SWITCH-P THRU AFTER-P",
                "    ALTER SWITCH-P TO PROCEED TO C-P",
                "    PERFORM SWITCH-P THRU AFTER-P",
                "    STOP RUN.",
                "SWITCH-P.",
                "    GO TO B-P.",
                "B-P.",
                "    ADD 1 TO WS-N",
                "    GO TO AFTER-P.",
                "C-P.",
                "    ADD 20 TO WS-N.",
                "AFTER-P.",
                "    EXIT."));
    }

    @Test
    @DisplayName("PROCEED TO は省略できる (FR-063)")
    void theProceedToPhraseIsOptional() {
        assertEquals("0020", run(
                List.of("01 WS-N PIC 9(4) VALUE 0."),
                "MAIN-START.",
                "    ALTER SWITCH-P TO C-P",
                "    PERFORM SWITCH-P THRU AFTER-P",
                "    STOP RUN.",
                "SWITCH-P.",
                "    GO TO B-P.",
                "B-P.",
                "    ADD 1 TO WS-N",
                "    GO TO AFTER-P.",
                "C-P.",
                "    ADD 20 TO WS-N.",
                "AFTER-P.",
                "    EXIT."));
    }

    @Test
    @DisplayName("独立段へ入り直すと ALTER が元へ戻る (FR-061, FR-063)")
    void anIndependentSegmentForgetsWhatWasAltered() {
        // 段番号 50 以上は独立段である。別の段から制御が移るたびに初期状態へ戻る。
        // 「初期状態」とは ALTER で書き換えた飛び先が元へ戻ることである
        assertEquals("0002", run(
                List.of("01 WS-N PIC 9(4) VALUE 0."),
                "DRIVER SECTION 00.",
                "MAIN-START.",
                "    ALTER SWITCH-P TO PROCEED TO C-P",
                "    PERFORM SWITCH-P THRU AFTER-P",
                "    PERFORM SWITCH-P THRU AFTER-P",
                "    STOP RUN.",
                "INDEPENDENT SECTION 50.",
                "SWITCH-P.",
                "    GO TO B-P.",
                "B-P.",
                "    ADD 1 TO WS-N",
                "    GO TO AFTER-P.",
                "C-P.",
                "    ADD 20 TO WS-N",
                "    GO TO AFTER-P.",
                "AFTER-P.",
                "    EXIT."));
    }

    @Test
    @DisplayName("常駐段なら書き換えは残る (FR-061, FR-063)")
    void aResidentSegmentKeepsWhatWasAltered() {
        // 同じ形を段番号 49 で書けば、書き換えは残ったままである
        assertEquals("0040", run(
                List.of("01 WS-N PIC 9(4) VALUE 0."),
                "DRIVER SECTION 00.",
                "MAIN-START.",
                "    ALTER SWITCH-P TO PROCEED TO C-P",
                "    PERFORM SWITCH-P THRU AFTER-P",
                "    PERFORM SWITCH-P THRU AFTER-P",
                "    STOP RUN.",
                "RESIDENT SECTION 49.",
                "SWITCH-P.",
                "    GO TO B-P.",
                "B-P.",
                "    ADD 1 TO WS-N",
                "    GO TO AFTER-P.",
                "C-P.",
                "    ADD 20 TO WS-N",
                "    GO TO AFTER-P.",
                "AFTER-P.",
                "    EXIT."));
    }

    @Test
    @DisplayName("GO TO だけでない段落は ALTER できない (FR-063)")
    void onlyAParagraphHoldingASingleGoToMayBeAltered() {
        CobolCompiler.Result result = compile(COUNTER,
                "MAIN-START.",
                "    ALTER OTHER-P TO PROCEED TO LAST-P.",
                "OTHER-P.",
                "    ADD 1 TO WS-N",
                "    GO TO LAST-P.",
                "LAST-P.",
                "    EXIT.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("a single GO TO"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("定義のない段落へ飛んだら誤りとして報告する (FR-061)")
    void anUndefinedTargetIsReported() {
        CobolCompiler.Result result = compile(COUNTER,
                "MAIN-START.",
                "    GO TO NO-SUCH-PARAGRAPH.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("undefined paragraph"),
                result.diagnostics().toString());
    }
}
