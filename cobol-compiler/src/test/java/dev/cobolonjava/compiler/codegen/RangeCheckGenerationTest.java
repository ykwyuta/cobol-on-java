package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.compiler.source.ProcessStatement;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.RangeCheckException;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code SSRANGE} 指定時の添字と部分参照の範囲検査 (要件 FR-024, FR-026)。
 *
 * <p>指定がなければ<b>検査しない</b>。範囲外の添字は記憶域の別の場所を読み書きする。
 * 参照実装の既定もそうであり、そこを変えると移行時に挙動が食い違う。
 */
@Tag("V1")
class RangeCheckGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(RangeCheckGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    /** 3 個の表と、そのすぐ後ろに置いた項目。範囲外の書き込みはここへ届く。 */
    private static final List<String> TABLE = List.of(
            "01 WS-T.",
            "   05 WS-E OCCURS 3 TIMES PIC X VALUE 'A'.",
            "01 WS-I PIC 9(3) VALUE 1.",
            "01 WS-TAIL PIC X(4) VALUE 'ZZZZ'.");

    /** 部分参照を試すための項目。 */
    private static final List<String> FIELD = List.of(
            "01 WS-I PIC 9(3) VALUE 1.",
            "01 WS-A PIC X(5) VALUE SPACES.");

    private static String sourceOf(String processLine, List<String> storage,
                                   String... procedure) {
        StringBuilder sb = new StringBuilder();
        if (processLine != null) {
            sb.append("       ").append(processLine).append('\n');
        }
        List<String> head = new ArrayList<>(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION."));
        head.addAll(storage);
        for (String line : head) {
            sb.append("       ").append(line).append('\n');
        }
        sb.append("       PROCEDURE DIVISION.\n");
        for (String line : procedure) {
            sb.append("       ").append(line).append('\n');
        }
        return sb.toString();
    }

    /** 起動時のオプションを与えて翻訳する。 */
    private static CobolCompiler.Result compile(String given, String source) {
        CompilerOptions options = given == null
                ? CompilerOptions.NONE
                : ProcessStatement.parse(given);
        return CobolCompiler.standard().withOptions(options).compile(FILE, source);
    }

    private static String run(CobolCompiler.Result result) {
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

    private static String run(String given, List<String> storage, String... procedure) {
        return run(compile(given, sourceOf(null, storage, procedure)));
    }

    @Test
    @DisplayName("指定がなければ範囲外の添字を検査しない (FR-024)")
    void withoutSsrangeAnOutOfRangeSubscriptIsNotChecked() {
        // 4 番目は表の外である。すぐ後ろの WS-I の 1 バイト目が書き換わる
        assertEquals("AAA" + "B04" + "ZZZZ", run(null, TABLE,
                "MOVE 4 TO WS-I",
                "MOVE 'B' TO WS-E (WS-I)."));
    }

    @Test
    @DisplayName("SSRANGE を指定すれば範囲外の添字で止まる (FR-024)")
    void ssrangeStopsAnOutOfRangeSubscript() {
        RangeCheckException thrown = assertThrows(RangeCheckException.class,
                () -> run("SSRANGE", TABLE,
                        "MOVE 4 TO WS-I",
                        "MOVE 'B' TO WS-E (WS-I)."));

        assertTrue(thrown.getMessage().contains("subscript 4 is outside 1..3"),
                thrown.getMessage());
        assertTrue(thrown.getMessage().contains("WS-E"), thrown.getMessage());
    }

    @Test
    @DisplayName("0 番目も範囲外である (FR-024)")
    void subscriptZeroIsOutOfRange() {
        RangeCheckException thrown = assertThrows(RangeCheckException.class,
                () -> run("SSRANGE", TABLE,
                        "MOVE 0 TO WS-I",
                        "MOVE 'B' TO WS-E (WS-I)."));

        assertTrue(thrown.getMessage().contains("outside 1..3"), thrown.getMessage());
    }

    @Test
    @DisplayName("範囲の中なら SSRANGE を指定しても素通りする (FR-024)")
    void anInRangeSubscriptPassesTheCheck() {
        assertEquals("ABA" + "002" + "ZZZZ", run("SSRANGE", TABLE,
                "MOVE 2 TO WS-I",
                "MOVE 'B' TO WS-E (WS-I)."));
    }

    @Test
    @DisplayName("SSRANGE は部分参照が項目からはみ出すのも見る (FR-026)")
    void ssrangeChecksReferenceModification() {
        // 4 から 3 バイトは 5 バイトの項目からはみ出す
        RangeCheckException thrown = assertThrows(RangeCheckException.class,
                () -> run("SSRANGE", FIELD,
                        "MOVE 4 TO WS-I",
                        "MOVE 'ABC' TO WS-A (WS-I:3)."));

        assertTrue(thrown.getMessage().contains("runs past the end of WS-A"),
                thrown.getMessage());
    }

    @Test
    @DisplayName("SSRANGE は部分参照の開始位置も見る (FR-026)")
    void ssrangeChecksTheStartOfAReferenceModification() {
        RangeCheckException thrown = assertThrows(RangeCheckException.class,
                () -> run("SSRANGE", FIELD,
                        "MOVE 0 TO WS-I",
                        "MOVE 'ABC' TO WS-A (WS-I:3)."));

        assertTrue(thrown.getMessage().contains("starts at 0"), thrown.getMessage());
    }

    @Test
    @DisplayName("部分参照が項目に収まっていれば素通りする (FR-026)")
    void anInRangeReferenceModificationPassesTheCheck() {
        assertEquals("003" + "  ABC", run("SSRANGE", FIELD,
                "MOVE 3 TO WS-I",
                "MOVE 'ABC' TO WS-A (WS-I:3)."));
    }

    @Test
    @DisplayName("ソースの CBL に書いた指定も効く (FR-093)")
    void theOptionMayBeWrittenInTheSource() {
        String source = sourceOf("CBL SSRANGE", TABLE,
                "MOVE 4 TO WS-I",
                "MOVE 'B' TO WS-E (WS-I).");

        CobolCompiler.Result result = compile(null, source);
        assertThrows(RangeCheckException.class, () -> run(result));
    }

    @Test
    @DisplayName("ソースの指定は起動時の指定にあとから重なる (FR-093)")
    void theSourceOptionOverridesTheInvocation() {
        // 起動時は SSRANGE だが、ソースの NOSSRANGE があとに重なるので検査しない
        String source = sourceOf("CBL NOSSRANGE", TABLE,
                "MOVE 4 TO WS-I",
                "MOVE 'B' TO WS-E (WS-I).");

        assertEquals("AAA" + "B04" + "ZZZZ", run(compile("SSRANGE", source)));
    }

    @Test
    @DisplayName("ON と OFF の対はあとに書いたものが効く (FR-093)")
    void theLastOfAToggledPairWins() {
        assertTrue(ProcessStatement.parse("NOSSRANGE,SSRANGE").subscriptRangeChecks());
        assertTrue(!ProcessStatement.parse("SSRANGE,NOSSRANGE").subscriptRangeChecks());
        assertTrue(!ProcessStatement.parse("ARITH(EXTEND)").subscriptRangeChecks());
    }
}
