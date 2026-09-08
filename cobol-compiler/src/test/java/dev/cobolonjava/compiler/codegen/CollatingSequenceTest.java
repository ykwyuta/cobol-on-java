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
 * {@code ALPHABET} 句と {@code PROGRAM COLLATING SEQUENCE} (要件 FR-054)。
 *
 * <p>確かめるのは<b>比較の向きが変わること</b>である。EBCDIC では英字がすべて数字より
 * 小さいが、ASCII の並びでは逆になる。差し替えが効いていれば、同じ条件が逆の枝を通る。
 */
@Tag("V1")
class CollatingSequenceTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(CollatingSequenceTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    /**
     * 環境部を書き足せるプログラムを組み立てる。
     *
     * @param environment 構成節の中身。空なら環境部そのものを書かない
     */
    private static CobolCompiler.Result compile(List<String> environment, List<String> storage,
                                                String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. HELLO.")) {
            sb.append("       ").append(line).append('\n');
        }
        if (!environment.isEmpty()) {
            sb.append("       ENVIRONMENT DIVISION.\n");
            sb.append("       CONFIGURATION SECTION.\n");
            for (String line : environment) {
                sb.append("       ").append(line).append('\n');
            }
        }
        sb.append("       DATA DIVISION.\n");
        sb.append("       WORKING-STORAGE SECTION.\n");
        for (String line : storage) {
            sb.append("       ").append(line).append('\n');
        }
        sb.append("       PROCEDURE DIVISION.\n");
        for (String line : procedure) {
            sb.append("       ").append(line).append('\n');
        }
        return CobolCompiler.standard().compile(FILE, sb.toString());
    }

    private static String run(List<String> environment, List<String> storage,
                              String... procedure) {
        CobolCompiler.Result result = compile(environment, storage, procedure);
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

    private static final List<String> TWO_LETTERS = List.of(
            "01 WS-A PIC X VALUE 'A'.",
            "01 WS-B PIC X VALUE '1'.",
            "01 WS-R PIC X.");

    private static final String COMPARE =
            "IF WS-A < WS-B MOVE 'T' TO WS-R ELSE MOVE 'F' TO WS-R END-IF.";

    @Test
    @DisplayName("既定では EBCDIC の並びで比べる (FR-053)")
    void withoutAnAlphabetTheCodePageOrderIsUsed() {
        // EBCDIC では英字 (0xC1) が数字 (0xF1) より小さい
        assertEquals("T", run(List.of(), TWO_LETTERS, COMPARE).substring(2));
    }

    @Test
    @DisplayName("STANDARD-2 を選ぶと ASCII の並びで比べる (FR-054)")
    void anAsciiAlphabetTurnsTheComparisonAround() {
        // ASCII では数字 (0x31) が英字 (0x41) より小さいので、向きが逆になる
        assertEquals("F", run(
                List.of("OBJECT-COMPUTER.",
                        "    COBOL-ON-JAVA",
                        "    PROGRAM COLLATING SEQUENCE IS ASCII-ORDER.",
                        "SPECIAL-NAMES.",
                        "    ALPHABET ASCII-ORDER IS STANDARD-2."),
                TWO_LETTERS, COMPARE).substring(2));
    }

    @Test
    @DisplayName("NATIVE を選んでも既定と同じである (FR-054)")
    void theNativeAlphabetChangesNothing() {
        assertEquals("T", run(
                List.of("OBJECT-COMPUTER.",
                        "    COBOL-ON-JAVA",
                        "    PROGRAM COLLATING SEQUENCE IS N-A-T-I-V-E.",
                        "SPECIAL-NAMES.",
                        "    ALPHABET N-A-T-I-V-E IS NATIVE."),
                TWO_LETTERS, COMPARE).substring(2));
    }

    @Test
    @DisplayName("文字を並べて書くと、書いた順が先頭になる (FR-054)")
    void listedCharactersComeFirstInTheOrderWritten() {
        // "1" を先頭に置けば、EBCDIC の並びでは大きいはずの数字が小さくなる
        assertEquals("F", run(
                List.of("OBJECT-COMPUTER.",
                        "    COBOL-ON-JAVA",
                        "    PROGRAM COLLATING SEQUENCE IS DIGITS-FIRST.",
                        "SPECIAL-NAMES.",
                        "    ALPHABET DIGITS-FIRST IS \"1\" \"2\" \"3\"."),
                TWO_LETTERS, COMPARE).substring(2));
    }

    @Test
    @DisplayName("ALSO で並べた文字は同じ位置になる (FR-054)")
    void charactersJoinedByAlsoCompareEqual() {
        assertEquals("T", run(
                List.of("OBJECT-COMPUTER.",
                        "    COBOL-ON-JAVA",
                        "    PROGRAM COLLATING SEQUENCE IS FOLDED.",
                        "SPECIAL-NAMES.",
                        "    ALPHABET FOLDED IS \"A\" ALSO \"1\"."),
                TWO_LETTERS,
                "IF WS-A = WS-B MOVE 'T' TO WS-R ELSE MOVE 'F' TO WS-R END-IF.").substring(2));
    }

    @Test
    @DisplayName("CHAR と ORD は差し替えた並びの位置で答える (FR-054, FR-070)")
    void charAndOrdFollowTheProgramCollatingSequence() {
        // "1" を 1 番目に置いた並びでは ORD("1") が 1 になる
        assertEquals("+00000100", run(
                List.of("OBJECT-COMPUTER.",
                        "    COBOL-ON-JAVA",
                        "    PROGRAM COLLATING SEQUENCE IS DIGITS-FIRST.",
                        "SPECIAL-NAMES.",
                        "    ALPHABET DIGITS-FIRST IS \"1\" \"2\" \"3\"."),
                List.of("01 WS-N PIC S9(6)V99 SIGN IS LEADING SEPARATE VALUE 0."),
                "COMPUTE WS-N = FUNCTION ORD(\"1\")."));
        assertEquals("1", run(
                List.of("OBJECT-COMPUTER.",
                        "    COBOL-ON-JAVA",
                        "    PROGRAM COLLATING SEQUENCE IS DIGITS-FIRST.",
                        "SPECIAL-NAMES.",
                        "    ALPHABET DIGITS-FIRST IS \"1\" \"2\" \"3\"."),
                List.of("01 WS-C PIC X."),
                "MOVE FUNCTION CHAR(1) TO WS-C."));
    }

    @Test
    @DisplayName("知らない ALPHABET 名は誤りとして報告する (FR-054)")
    void anUndefinedAlphabetNameIsReported() {
        CobolCompiler.Result result = compile(
                List.of("OBJECT-COMPUTER.",
                        "    COBOL-ON-JAVA",
                        "    PROGRAM COLLATING SEQUENCE IS MISSING-ONE."),
                TWO_LETTERS, COMPARE);

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("undefined alphabet-name"),
                result.diagnostics().toString());
    }
}
