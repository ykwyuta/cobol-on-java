package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 英数字編集項目への転記 (要件 FR-030, FR-060、設計 60)。
 *
 * <p>{@code PICTURE XX0XXBXXX} のような項目は、{@code A} と {@code X} の<b>文字位置</b>
 * にだけ送出データを詰め、{@code 0} {@code B} {@code /} はその場所に置く。ただの
 * バイト詰めにすると挿入文字が消えるので、<b>差が出る形</b>で確かめる。
 */
@Tag("V1")
class AlphanumericEditedMoveTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(AlphanumericEditedMoveTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String run(List<String> storage, String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. AEMOVE.",
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
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            program.runFresh(ProgramContext.capturing(sink));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    private static final List<String> STORAGE = List.of(
            "01 WS-EDIT PIC XX0XXBXXX.",
            "01 WS-SRC  PIC X(9) VALUE 'ABCDEFGHI'.",
            "01 WS-NUM  PIC 9(10) VALUE 0123456789.");

    @Test
    @DisplayName("挿入文字は文字位置を占めない (FR-030)")
    void insertionCharactersTakeTheirOwnPosition() {
        // 送出は 9 文字あるが、文字位置は 7 つしかない。7 文字だけ採る
        assertEquals("[AB0CD EFG]|", run(STORAGE,
                "MOVE WS-SRC TO WS-EDIT.",
                "DISPLAY '[' WS-EDIT ']'.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("数字項目からの転記でも挿入文字は置かれる (FR-060)")
    void movingFromANumericItemStillInserts() {
        // NC105A の MOVE-TEST-F1-52 と同じ形。10 桁のうち左から 7 桁を採る
        assertEquals("[01023 456]|", run(STORAGE,
                "MOVE WS-NUM TO WS-EDIT.",
                "DISPLAY '[' WS-EDIT ']'.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("図形定数は文字位置の数だけ広がる (FR-060)")
    void aFigurativeConstantFillsOnlyTheCharacterPositions() {
        // 項目の長さ (9) ぶん広げてしまうと、挿入文字の分だけ多く採ってしまう
        assertEquals("[00000 000]|[  0      ]|", run(STORAGE,
                "MOVE ZERO TO WS-EDIT.",
                "DISPLAY '[' WS-EDIT ']'.",
                "MOVE SPACE TO WS-EDIT.",
                "DISPLAY '[' WS-EDIT ']'.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("送出が短ければ残りの文字位置は空白になる (FR-030)")
    void aShortSenderLeavesTheRestBlank() {
        assertEquals("[AB0C     ]|", run(STORAGE,
                "MOVE 'ABC' TO WS-EDIT.",
                "DISPLAY '[' WS-EDIT ']'.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("斜線も挿入文字である (FR-030)")
    void theSolidusIsAnInsertionCharacterToo() {
        assertEquals("[12/31/99]|", run(
                List.of("01 WS-DATE PIC XX/XX/XX."),
                "MOVE '123199' TO WS-DATE.",
                "DISPLAY '[' WS-DATE ']'.",
                "STOP RUN."));
    }
}
