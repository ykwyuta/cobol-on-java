package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 生成したクラスをその場で読み込んで実行し、記憶域のバイト列を確かめる。
 *
 * <p>期待値は IBM-1047 で書く。空白は {@code 40}、数字は {@code F0}〜{@code F9}、
 * {@code A} {@code B} {@code C} は {@code C1}〜{@code C3} である。
 */
@Tag("V1")
class ProgramGeneratorTest {

    private static final String FILE = "MAIN.cbl";

    /** 生成したクラスだけを読み込む。親には委譲するので、ランタイムはそのまま使える。 */
    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(ProgramGeneratorTest.class.getClassLoader());
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

    /** 翻訳して実行し、記憶域の全体を 16 進で返す。 */
    private static String run(List<String> storage, String... procedure) {
        CobolCompiler.Result result = compile(storage, procedure);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            Storage executed = program.runFresh();
            return HexFormat.of().withUpperCase().formatHex(executed.array());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
    }

    @Test
    @DisplayName("翻訳したプログラムが実際に動く (ARC-3)")
    void aCompiledProgramRuns() {
        // WS-A が 'AB ' で始まり、MOVE で WS-B へ写る
        assertEquals("C1C240C1C240", run(
                List.of("01 WS-A PIC X(3) VALUE 'AB'.", "01 WS-B PIC X(3)."),
                "MOVE WS-A TO WS-B."));
    }

    @Test
    @DisplayName("初期イメージは VALUE 句から作られる (FR-013)")
    void theInitialStorageComesFromTheValueClauses() {
        assertEquals("C1C2C3404040", run(
                List.of("01 WS-A PIC X(3) VALUE 'ABC'.", "01 WS-B PIC X(3)."),
                "MOVE SPACES TO WS-B."));
    }

    @Test
    @DisplayName("定数を転記できる (FR-060)")
    void aLiteralIsMovedIntoStorage() {
        assertEquals("C1C240", run(List.of("01 WS-B PIC X(3)."), "MOVE 'AB' TO WS-B."));
    }

    @Test
    @DisplayName("図形定数は受取項目いっぱいを埋める (FR-060)")
    void aFigurativeConstantFillsItsReceiver() {
        assertEquals("FFFFFF", run(List.of("01 WS-B PIC X(3)."), "MOVE HIGH-VALUES TO WS-B."));
    }

    @Test
    @DisplayName("JUSTIFIED RIGHT は右詰めになる (FR-060)")
    void justifiedRightPacksToTheRight() {
        assertEquals("4040C1C2", run(
                List.of("01 WS-B PIC X(4) JUSTIFIED RIGHT."), "MOVE 'AB' TO WS-B."));
    }

    @Test
    @DisplayName("数値転記は小数点で位置を合わせる (FR-060)")
    void aNumericMoveAlignsOnTheDecimalPoint() {
        // 12.5 を PIC 9(3)V99 へ移すと 01250 になる
        assertEquals("F0F1F2F5F0F0F1F2F5F0", run(
                List.of("01 WS-A PIC 9(3)V99 VALUE 12.5.", "01 WS-B PIC 9(3)V99."),
                "MOVE WS-A TO WS-B."));
    }

    @Test
    @DisplayName("転記の桁あふれは黙って切り捨てられる (FR-060)")
    void anOverflowingNumericMoveIsTruncated() {
        // 小数点で位置を合わせるので、落ちるのは上位桁である。12345 は 345 になる
        assertEquals("F1F2F3F4F5F3F4F5", run(
                List.of("01 WS-A PIC 9(5) VALUE 12345.", "01 WS-B PIC 9(3)."),
                "MOVE WS-A TO WS-B."));
    }

    @Test
    @DisplayName("パック 10 進への転記もランタイムが行う (FR-031, FR-060)")
    void aPackedDecimalReceiverGoesThroughTheRuntime() {
        // S9(3) COMP-3 は 2 バイト。123 は 12 3C になる
        assertEquals("F1F2F3123C", run(
                List.of("01 WS-A PIC 9(3) VALUE 123.", "01 WS-B PIC S9(3) COMP-3."),
                "MOVE WS-A TO WS-B."));
    }

    @Test
    @DisplayName("数字編集項目へは編集して書き込む (FR-033, FR-060)")
    void anEditedReceiverIsFormatted() {
        // PIC ZZ9.99 は Z Z 9 . 9 9 の 6 バイト。12.5 は " 12.50" になる
        assertEquals("F0F1F2F5F0" + "40F1F24BF5F0", run(
                List.of("01 WS-A PIC 9(3)V99 VALUE 12.5.", "01 WS-B PIC ZZ9.99."),
                "MOVE WS-A TO WS-B."));
    }

    @Test
    @DisplayName("英数字項目から数値項目へは符号なし整数として読む (FR-060)")
    void anAlphanumericSourceIsReadAsAnUnsignedInteger() {
        assertEquals("F1F2F3F1F2F3", run(
                List.of("01 WS-A PIC X(3) VALUE '123'.", "01 WS-B PIC 9(3)."),
                "MOVE WS-A TO WS-B."));
    }

    @Test
    @DisplayName("添字が定数なら位置は翻訳時に決まる (FR-024)")
    void aConstantSubscriptIsResolvedAtCompileTime() {
        // 'AAA' で始まり、2 番目と 3 番目だけが書き換わる。WS-B は VALUE がないので空白
        assertEquals("C1C2C340", run(
                List.of("01 WS-T.", "   05 WS-E OCCURS 3 TIMES PIC X VALUE 'A'.",
                        "01 WS-B PIC X."),
                "MOVE 'B' TO WS-E (2)",
                "MOVE 'C' TO WS-E (3)."));
    }

    @Test
    @DisplayName("部分参照した位置へ書き込める (FR-026)")
    void aReferenceModifiedReceiverIsWrittenInPlace() {
        assertEquals("C1C2C34040", run(
                List.of("01 WS-A PIC X(5)."),
                "MOVE 'ABC' TO WS-A (1:3)."));
    }

    @Test
    @DisplayName("複数の受取側へ同じ値を配る (FR-060)")
    void oneSourceReachesEveryTarget() {
        assertEquals("C1C1C1", run(
                List.of("01 WS-A PIC X.", "01 WS-B PIC X.", "01 WS-C PIC X."),
                "MOVE 'A' TO WS-A WS-B WS-C."));
    }

    @Test
    @DisplayName("部分参照の長さがデータ項目ならまだ生成できないと報告する (P-027)")
    void aVariableReferenceModificationLengthIsReportedAsUnsupported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-I PIC 9(3) COMP VALUE 1.", "01 WS-A PIC X(5)."),
                "MOVE 'A' TO WS-A (1:WS-I).");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("not a constant"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("クラス名は COBOL のプログラム名から作る (ARC-3)")
    void theClassNameComesFromTheProgramName() {
        assertEquals("cobol.generated.PAY_CALC", ProgramGenerator.classNameOf("PAY-CALC"));
        assertEquals("cobol.generated._9TO5", ProgramGenerator.classNameOf("9TO5"));
    }
}
