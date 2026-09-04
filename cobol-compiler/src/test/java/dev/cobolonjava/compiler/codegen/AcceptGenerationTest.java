package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.Storage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code ACCEPT} (要件 FR-060、テスト時の固定は FR-204)。
 *
 * <p>送出側は<b>符号なし整数の表示形式</b>である。日付でも端末からの入力でも同じ形であり、
 * 受け取る項目への詰め方が分類で決まる。時計を固定して値を確かめる。
 */
@Tag("V1")
class AcceptGenerationTest {

    private static final String FILE = "MAIN.cbl";

    /** 2026-09-04 (金) 13:45:07.89 UTC。曜日は金曜なので 5 である。 */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-04T13:45:07.890Z"), ZoneOffset.UTC);

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(AcceptGenerationTest.class.getClassLoader());
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

    /** 時計を固定して実行する。 */
    private static String run(List<String> storage, String... procedure) {
        return run(ProgramContext.standard().withClock(FIXED), storage, procedure);
    }

    /** 端末からの入力を与えて実行する。 */
    private static String withInput(List<String> lines, List<String> storage,
                                    String... procedure) {
        Deque<String> queue = new ArrayDeque<>(lines);
        ProgramContext context = ProgramContext.standard().withClock(FIXED)
                .withInput(() -> queue.isEmpty() ? null : queue.removeFirst());
        return run(context, storage, procedure);
    }

    private static String run(ProgramContext context, List<String> storage,
                              String... procedure) {
        CobolCompiler.Result result = compile(storage, procedure);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            Storage executed = program.runFresh(context);
            return CodePages.DEFAULT.decode(executed.array());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
    }

    @Test
    @DisplayName("DATE は YYMMDD の 6 桁である (FR-060)")
    void dateIsSixDigits() {
        assertEquals("260904", run(List.of("01 WS-D PIC X(6)."), "ACCEPT WS-D FROM DATE."));
    }

    @Test
    @DisplayName("DATE YYYYMMDD は 8 桁である (FR-060)")
    void dateYyyymmddIsEightDigits() {
        assertEquals("20260904", run(
                List.of("01 WS-D PIC X(8)."), "ACCEPT WS-D FROM DATE YYYYMMDD."));
    }

    @Test
    @DisplayName("DAY は YYDDD の 5 桁である (FR-060)")
    void dayIsTheDayOfYear() {
        // 2026-09-04 は年の 247 日目である
        assertEquals("26247", run(List.of("01 WS-D PIC X(5)."), "ACCEPT WS-D FROM DAY."));
    }

    @Test
    @DisplayName("DAY YYYYDDD は 7 桁である (FR-060)")
    void dayYyyydddIsSevenDigits() {
        assertEquals("2026247", run(
                List.of("01 WS-D PIC X(7)."), "ACCEPT WS-D FROM DAY YYYYDDD."));
    }

    @Test
    @DisplayName("DAY-OF-WEEK は月曜が 1 である (FR-060)")
    void dayOfWeekCountsFromMonday() {
        // 2026-09-04 は金曜日
        assertEquals("5", run(List.of("01 WS-D PIC X."), "ACCEPT WS-D FROM DAY-OF-WEEK."));
    }

    @Test
    @DisplayName("TIME は HHMMSSss の 8 桁である (FR-060)")
    void timeIsEightDigits() {
        assertEquals("13450789", run(List.of("01 WS-T PIC X(8)."), "ACCEPT WS-T FROM TIME."));
    }

    @Test
    @DisplayName("数値項目は整数として受け取る (FR-060)")
    void aNumericReceiverTakesTheValueAsAnInteger() {
        assertEquals("260904", run(List.of("01 WS-D PIC 9(6)."), "ACCEPT WS-D FROM DATE."));
    }

    @Test
    @DisplayName("受取項目が短ければ切り捨てられる (FR-060)")
    void ashorterReceiverTruncates() {
        // 英数字転記なので左から詰める
        assertEquals("2609", run(List.of("01 WS-D PIC X(4)."), "ACCEPT WS-D FROM DATE."));
    }

    @Test
    @DisplayName("受取項目が長ければ空白で埋まる (FR-060)")
    void aLongerReceiverIsPadded() {
        assertEquals("260904  ", run(List.of("01 WS-D PIC X(8)."), "ACCEPT WS-D FROM DATE."));
    }

    @Test
    @DisplayName("数値項目は小数点で位置を合わせる (FR-060)")
    void aNumericReceiverAlignsOnTheDecimalPoint() {
        // 260904 を PIC 9(4) へ入れると上位が落ちて 0904 になる
        assertEquals("0904", run(List.of("01 WS-D PIC 9(4)."), "ACCEPT WS-D FROM DATE."));
    }

    @Test
    @DisplayName("FROM を書かなければ端末から 1 行読む (FR-060)")
    void withoutFromItReadsALine() {
        assertEquals("hello     ", withInput(List.of("hello"),
                List.of("01 WS-A PIC X(10)."), "ACCEPT WS-A."));
    }

    @Test
    @DisplayName("読むたびに次の行へ進む (FR-060)")
    void eachAcceptReadsTheNextLine() {
        assertEquals("one  " + "two  ", withInput(List.of("one", "two"),
                List.of("01 WS-A PIC X(5).", "01 WS-B PIC X(5)."),
                "ACCEPT WS-A", "ACCEPT WS-B."));
    }

    @Test
    @DisplayName("入力が尽きれば空白になる (FR-060)")
    void anExhaustedInputGivesSpaces() {
        // 参照実装も入力がなければ止まらない。例外にはしない
        assertEquals("     ", withInput(List.of(),
                List.of("01 WS-A PIC X(5) VALUE 'abcde'."), "ACCEPT WS-A."));
    }

    @Test
    @DisplayName("数値項目でも端末から受け取れる (FR-060)")
    void aNumericReceiverTakesConsoleInput() {
        assertEquals("00042", withInput(List.of("00042"),
                List.of("01 WS-N PIC 9(5)."), "ACCEPT WS-N."));
    }

    @Test
    @DisplayName("FROM 呼び名はまだ書けないと報告する (FR-060)")
    void aMnemonicSourceIsReportedAsUnsupported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-A PIC X(5)."), "ACCEPT WS-A FROM CONSOLE-IN.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("mnemonic"),
                result.diagnostics().toString());
    }
}
