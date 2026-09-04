package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * {@code PERFORM VARYING} を翻訳して実行し、記憶域で確かめる。
 *
 * <p>見るのは繰り返した回数だけではない。<b>抜けたときに変数へ何が残っているか</b>も
 * 見る。条件が成り立ったときの値がそのまま残るのが規則であり、回数だけでは
 * 1 回分の進めかたの誤りが見つからない。
 */
@Tag("V1")
class VaryingGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(VaryingGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String run(List<String> storage, String... procedure) {
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
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, sb.toString());
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

    /** 数える項目と、変える項目。 */
    private static final List<String> ONE = List.of(
            "01 WS-N PIC 9(3) VALUE 0.",
            "01 WS-I PIC 9(3) VALUE 0.");

    @Test
    @DisplayName("VARYING は初期値から順に変えながら繰り返す (FR-061)")
    void varyingStepsItsIdentifier() {
        // 1 2 3 を足して 6。抜けたときの WS-I は条件が成り立った 4 である
        assertEquals("006004", run(ONE,
                "MAIN-START.",
                "    PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 3",
                "        ADD WS-I TO WS-N",
                "    END-PERFORM."));
    }

    @Test
    @DisplayName("条件が最初から成り立てば初期値だけが入る (FR-061)")
    void aConditionThatHoldsAtOnceStillAssignsTheInitialValue() {
        assertEquals("000005", run(ONE,
                "MAIN-START.",
                "    PERFORM VARYING WS-I FROM 5 BY 1 UNTIL WS-I > 0",
                "        ADD 1 TO WS-N",
                "    END-PERFORM."));
    }

    @Test
    @DisplayName("BY には負の値も書ける (FR-061)")
    void theStepMayBeNegative() {
        // 10 7 4 を足して 21。次は 1 で条件が成り立つ
        assertEquals("021001", run(ONE,
                "MAIN-START.",
                "    PERFORM VARYING WS-I FROM 10 BY -3 UNTIL WS-I < 3",
                "        ADD WS-I TO WS-N",
                "    END-PERFORM."));
    }

    @Test
    @DisplayName("初期値と増分はデータ項目でも書ける (FR-061)")
    void theInitialValueAndStepMayBeDataItems() {
        // 2 5 8 の 3 回。次は 11 で条件が成り立つ
        assertEquals("003011002003", run(
                List.of("01 WS-N PIC 9(3) VALUE 0.",
                        "01 WS-I PIC 9(3) VALUE 0.",
                        "01 WS-F PIC 9(3) VALUE 2.",
                        "01 WS-B PIC 9(3) VALUE 3."),
                "MAIN-START.",
                "    PERFORM VARYING WS-I FROM WS-F BY WS-B UNTIL WS-I > 9",
                "        ADD 1 TO WS-N",
                "    END-PERFORM."));
    }

    @Test
    @DisplayName("小数を持つ項目も小数点で位置を合わせて進む (FR-061)")
    void aScaledIdentifierStepsByItsOwnScale() {
        // 1.00 1.50 2.00 の 3 回。次は 2.50 で条件が成り立つ
        assertEquals("00300250", run(
                List.of("01 WS-N PIC 9(3) VALUE 0.", "01 WS-I PIC 9(3)V99 VALUE 0."),
                "MAIN-START.",
                "    PERFORM VARYING WS-I FROM 1 BY 0.5 UNTIL WS-I > 2",
                "        ADD 1 TO WS-N",
                "    END-PERFORM."));
    }

    @Test
    @DisplayName("段落を呼ぶ形でも VARYING を書ける (FR-061)")
    void varyingAlsoDrivesAParagraph() {
        // 繰り返しで 1 2 3、そのあと素直な流れが WS-I = 4 のまま通って 10
        assertEquals("010004", run(ONE,
                "MAIN-START.",
                "    PERFORM ADD-I VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 3.",
                "ADD-I.",
                "    ADD WS-I TO WS-N."));
    }

    @Test
    @DisplayName("WITH TEST AFTER は中身を実行してから条件を見る (FR-061)")
    void withTestAfterRunsTheBodyBeforeTesting() {
        // TEST BEFORE なら 3 回。あとから見るので 4 回目も通る
        assertEquals("004004", run(ONE,
                "MAIN-START.",
                "    PERFORM WITH TEST AFTER",
                "            VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 3",
                "        ADD 1 TO WS-N",
                "    END-PERFORM."));
    }

    @Test
    @DisplayName("AFTER は入れ子の内側になる (FR-061)")
    void afterMakesAnInnerLoop() {
        // 2 x 3 = 6 回。抜けたときの内側は初期値に戻っている
        assertEquals("00631", run(
                List.of("01 WS-N PIC 9(3) VALUE 0.",
                        "01 WS-I PIC 9 VALUE 0.",
                        "01 WS-J PIC 9 VALUE 0."),
                "MAIN-START.",
                "    PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 2",
                "            AFTER WS-J FROM 1 BY 1 UNTIL WS-J > 3",
                "        ADD 1 TO WS-N",
                "    END-PERFORM."));
    }

    @Test
    @DisplayName("内側は外側が 1 進むたびに初期値へ戻る (FR-061)")
    void theInnerIdentifierRestartsForEachOuterStep() {
        // 内側の並びが 1 2 3 1 2 3 になる。戻さなければ 1 2 3 だけで終わる
        assertEquals("007123123" + "3" + "1", run(
                List.of("01 WS-P PIC 9(3) VALUE 1.",
                        "01 WS-OUT PIC X(6) VALUE SPACES.",
                        "01 WS-I PIC 9 VALUE 0.",
                        "01 WS-J PIC 9 VALUE 0."),
                "MAIN-START.",
                "    PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 2",
                "            AFTER WS-J FROM 1 BY 1 UNTIL WS-J > 3",
                "        MOVE WS-J TO WS-OUT (WS-P:1)",
                "        ADD 1 TO WS-P",
                "    END-PERFORM."));
    }

    @Test
    @DisplayName("AFTER は 3 段以上でも入れ子になる (FR-061)")
    void afterPhrasesNestToAnyDepth() {
        // 2 x 2 x 2 = 8 回
        assertEquals("008311", run(
                List.of("01 WS-N PIC 9(3) VALUE 0.",
                        "01 WS-I PIC 9 VALUE 0.",
                        "01 WS-J PIC 9 VALUE 0.",
                        "01 WS-K PIC 9 VALUE 0."),
                "MAIN-START.",
                "    PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 2",
                "            AFTER WS-J FROM 1 BY 1 UNTIL WS-J > 2",
                "            AFTER WS-K FROM 1 BY 1 UNTIL WS-K > 2",
                "        ADD 1 TO WS-N",
                "    END-PERFORM."));
    }

    @Test
    @DisplayName("内側の初期値は戻すたびに評価しなおす (FR-061)")
    void theInnerInitialValueIsEvaluatedAgainOnEachRestart() {
        // 内側の初期値 WS-F を中身が書き換える。1 回目は 1 から 3 回、
        // 2 回目は 3 から 1 回。捕まえたままなら 3 回 3 回になる
        assertEquals("004333", run(
                List.of("01 WS-N PIC 9(3) VALUE 0.",
                        "01 WS-F PIC 9 VALUE 1.",
                        "01 WS-I PIC 9 VALUE 0.",
                        "01 WS-J PIC 9 VALUE 0."),
                "MAIN-START.",
                "    PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 2",
                "            AFTER WS-J FROM WS-F BY 1 UNTIL WS-J > 3",
                "        ADD 1 TO WS-N",
                "        MOVE 3 TO WS-F",
                "    END-PERFORM."));
    }

    @Test
    @DisplayName("1 回分の増分が 1 でなくても条件どおりに止まる (FR-061)")
    void aStepLargerThanOneStillStopsAtItsCondition() {
        // 1 3 5 7 9 を足して 25。次は 11 で条件が成り立つ
        assertEquals("025011", run(ONE,
                "MAIN-START.",
                "    PERFORM VARYING WS-I FROM 1 BY 2 UNTIL WS-I > 9",
                "        ADD WS-I TO WS-N",
                "    END-PERFORM."));
    }
}
