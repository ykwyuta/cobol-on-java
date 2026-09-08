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

/** {@code PERFORM} を翻訳して実行し、何回どの順で通ったかを記憶域で確かめる。 */
@Tag("V1")
class PerformGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(PerformGenerationTest.class.getClassLoader());
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

    /** 通った回数を数える 3 桁の項目。 */
    private static final List<String> COUNTER = List.of("01 WS-N PIC 9(3) VALUE 0.");

    @Test
    @DisplayName("PERFORM は段落を 1 度呼ぶ (FR-061)")
    void performCallsItsParagraphOnce() {
        // 素直に流れる分と PERFORM の分で 2 回通る
        assertEquals("002", run(COUNTER,
                "MAIN-START.",
                "    PERFORM ADD-ONE.",
                "ADD-ONE.",
                "    ADD 1 TO WS-N."));
    }

    @Test
    @DisplayName("PERFORM は次の段落へ流れ込まない (FR-061)")
    void performDoesNotFallIntoTheNextParagraph() {
        // PERFORM で +1、そのあと素直な流れが ADD-ONE と ADD-TEN を通って +1 +10 で 12。
        // PERFORM が次の段落へ流れ込んでいれば 22 になる (THRU の試験がその値である)
        assertEquals("012", run(COUNTER,
                "MAIN-START.",
                "    PERFORM ADD-ONE.",
                "ADD-ONE.",
                "    ADD 1 TO WS-N.",
                "ADD-TEN.",
                "    ADD 10 TO WS-N."));
    }

    @Test
    @DisplayName("PERFORM ... THRU は範囲の段落を順に呼ぶ (FR-061)")
    void performThruCallsTheWholeRange() {
        assertEquals("022", run(COUNTER,
                "MAIN-START.",
                "    PERFORM ADD-ONE THRU ADD-TEN.",
                "ADD-ONE.",
                "    ADD 1 TO WS-N.",
                "ADD-TEN.",
                "    ADD 10 TO WS-N."));
    }

    @Test
    @DisplayName("PERFORM n TIMES は指定した回数だけ繰り返す (FR-061)")
    void performTimesRepeatsItsBody() {
        assertEquals("004", run(COUNTER,
                "MAIN-START.",
                "    PERFORM ADD-ONE 3 TIMES.",
                "ADD-ONE.",
                "    ADD 1 TO WS-N."));
    }

    @Test
    @DisplayName("繰り返しの回数はデータ項目でも書ける (FR-061)")
    void theRepeatCountMayBeADataItem() {
        // 5 回 + 0 に戻して 5 回 + 素直な流れの 1 回で 6
        assertEquals("006005", run(
                List.of("01 WS-N PIC 9(3) VALUE 0.", "01 WS-C PIC 9(3) VALUE 5."),
                "MAIN-START.",
                "    PERFORM ADD-ONE WS-C TIMES",
                "    MOVE 0 TO WS-N",
                "    PERFORM ADD-ONE WS-C TIMES.",
                "ADD-ONE.",
                "    ADD 1 TO WS-N."));
    }

    @Test
    @DisplayName("回数が 0 なら 1 度も通らない (FR-061)")
    void aZeroCountRunsNothing() {
        assertEquals("001", run(COUNTER,
                "MAIN-START.",
                "    PERFORM ADD-ONE 0 TIMES.",
                "ADD-ONE.",
                "    ADD 1 TO WS-N."));
    }

    @Test
    @DisplayName("PERFORM UNTIL の条件はやめる条件である (FR-061)")
    void performUntilStopsWhenItsConditionHolds() {
        // 5 になったらやめる。素直な流れでもう 1 度通って 6
        assertEquals("006", run(COUNTER,
                "MAIN-START.",
                "    PERFORM ADD-ONE UNTIL WS-N >= 5.",
                "ADD-ONE.",
                "    ADD 1 TO WS-N."));
    }

    @Test
    @DisplayName("条件が最初から成り立てば 1 度も通らない (FR-061)")
    void aConditionThatHoldsAtOnceRunsNothing() {
        assertEquals("001", run(COUNTER,
                "MAIN-START.",
                "    PERFORM ADD-ONE UNTIL WS-N >= 0.",
                "ADD-ONE.",
                "    ADD 1 TO WS-N."));
    }

    @Test
    @DisplayName("WITH TEST AFTER は中身を 1 度実行してから条件を見る (FR-061)")
    void withTestAfterRunsTheBodyFirst() {
        assertEquals("002", run(COUNTER,
                "MAIN-START.",
                "    PERFORM ADD-ONE WITH TEST AFTER UNTIL WS-N >= 0.",
                "ADD-ONE.",
                "    ADD 1 TO WS-N."));
    }

    @Test
    @DisplayName("その場に本体を書ける (FR-061)")
    void anInlineBodyRunsInPlace() {
        assertEquals("003", run(COUNTER,
                "MAIN-START.",
                "    PERFORM 3 TIMES",
                "        ADD 1 TO WS-N",
                "    END-PERFORM."));
    }

    @Test
    @DisplayName("その場の本体でも UNTIL を書ける (FR-061)")
    void anInlineBodyMayUseUntil() {
        assertEquals("004", run(COUNTER,
                "MAIN-START.",
                "    PERFORM UNTIL WS-N >= 4",
                "        ADD 1 TO WS-N",
                "    END-PERFORM."));
    }

    @Test
    @DisplayName("PERFORM は入れ子にできる (FR-061)")
    void performStatementsNest() {
        // 3 x 4 = 12
        assertEquals("012", run(COUNTER,
                "MAIN-START.",
                "    PERFORM 3 TIMES",
                "        PERFORM 4 TIMES",
                "            ADD 1 TO WS-N",
                "        END-PERFORM",
                "    END-PERFORM."));
    }

    @Test
    @DisplayName("PERFORM の中で IF を書ける (FR-061)")
    void ifStatementsWorkInsideAPerform() {
        // 1 から 10 まで足し、5 を超えたところで止める
        assertEquals("006", run(COUNTER,
                "MAIN-START.",
                "    PERFORM 10 TIMES",
                "        IF WS-N < 6",
                "            ADD 1 TO WS-N",
                "        END-IF",
                "    END-PERFORM."));
    }

    @Test
    @DisplayName("定義のない段落を呼んだら誤りとして報告する (FR-061)")
    void anUndefinedParagraphIsReported() {
        CobolCompiler.Result result = compile(COUNTER,
                "MAIN-START.",
                "    PERFORM NO-SUCH-PARAGRAPH.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("undefined paragraph"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("2 つ目の手続き名が物理的に前にあってもよい (FR-061)")
    void theSecondProcedureMayComeFirstInTheSource() {
        // 範囲が終わるのは<b>2 つ目の段落を最後まで流れきったとき</b>であって、
        // 並び順ではない。GO TO で行き来してそこへ達すればよい。
        // NIST の検査スイートはこれを「ALL THIS IS LEGAL」と書いている (NC102A)。
        // ここを「逆順は誤り」と断っていた
        assertEquals("011", run(COUNTER,
                "MAIN-START.",
                "    PERFORM ADD-TEN THRU ADD-ONE",
                "    STOP RUN.",
                "ADD-ONE.",
                "    ADD 1 TO WS-N.",
                "ADD-TEN.",
                "    ADD 10 TO WS-N",
                "    GO TO ADD-ONE."));
    }
}
