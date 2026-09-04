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
 * {@code MOVE CORRESPONDING} を翻訳して実行し、記憶域で確かめる (要件 FR-060)。
 *
 * <p>対応付けは意味解析で済ませ、名前の合う組の数だけ普通の {@code MOVE} へ展開する。
 * したがって見るべきは<b>どの組が選ばれたか</b>であり、転記そのものの規則ではない。
 */
@Tag("V1")
class CorrespondingGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(CorrespondingGenerationTest.class.getClassLoader());
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
    @DisplayName("名前の合う項目だけを移す (FR-060)")
    void onlyItemsWithMatchingNamesAreMoved() {
        // WS-B の中で名前が合うのは B のみ。X は動かない
        assertEquals("12" + "3" + "2" + "9", run(
                List.of("01 WS-A.",
                        "   05 A PIC X VALUE '1'.",
                        "   05 B PIC X VALUE '2'.",
                        "   05 C PIC X VALUE '3'.",
                        "01 WS-B.",
                        "   05 B PIC X VALUE '8'.",
                        "   05 X PIC X VALUE '9'.",
                        ""),
                "MOVE CORRESPONDING WS-A TO WS-B."));
    }

    @Test
    @DisplayName("CORR と書いても同じである (FR-060)")
    void corrIsTheSameAsCorresponding() {
        assertEquals("12" + "1" + "2", run(
                List.of("01 WS-A.",
                        "   05 A PIC X VALUE '1'.",
                        "   05 B PIC X VALUE '2'.",
                        "01 WS-B.",
                        "   05 A PIC X VALUE '8'.",
                        "   05 B PIC X VALUE '9'."),
                "MOVE CORR WS-A TO WS-B."));
    }

    @Test
    @DisplayName("両方が集団項目なら下へ降りる (FR-060)")
    void twoGroupsAreDescendedInto() {
        // G は両方とも集団項目なので、その中の P だけが移る。Q は名前が合わない
        assertEquals("1" + "2" + "1" + "9", run(
                List.of("01 WS-A.",
                        "   05 G.",
                        "      10 P PIC X VALUE '1'.",
                        "      10 Q PIC X VALUE '2'.",
                        "01 WS-B.",
                        "   05 G.",
                        "      10 P PIC X VALUE '8'.",
                        "      10 R PIC X VALUE '9'."),
                "MOVE CORRESPONDING WS-A TO WS-B."));
    }

    @Test
    @DisplayName("片方が基本項目ならそこで組になる (FR-060)")
    void aGroupAndAnElementaryFormAPair() {
        // 受取側の G は基本項目である。集団項目 G の 2 バイトがそのまま入る
        assertEquals("12" + "12", run(
                List.of("01 WS-A.",
                        "   05 G.",
                        "      10 P PIC X VALUE '1'.",
                        "      10 Q PIC X VALUE '2'.",
                        "01 WS-B.",
                        "   05 G PIC X(2) VALUE '89'."),
                "MOVE CORRESPONDING WS-A TO WS-B."));
    }

    @Test
    @DisplayName("組ごとに転記の規則が決まる (FR-060)")
    void eachPairFollowsItsOwnMoveRules() {
        // N は小数点を合わせる数値転記、E は編集して書き込む転記になる
        assertEquals("0125" + "125" + "00125" + "125.00", run(
                List.of("01 WS-A.",
                        "   05 N PIC 9(2)V99 VALUE 1.25.",
                        "   05 E PIC 9(3) VALUE 125.",
                        "01 WS-B.",
                        "   05 N PIC 9(3)V99.",
                        "   05 E PIC ZZ9.99."),
                "MOVE CORRESPONDING WS-A TO WS-B."));
    }

    @Test
    @DisplayName("OCCURS の項目は対応付けから外す (FR-060)")
    void tableItemsAreNotCorresponding() {
        // T は表なので組にならない。組になるのは B だけである
        assertEquals("xy" + "2" + "ab" + "2", run(
                List.of("01 WS-A.",
                        "   05 T OCCURS 2 TIMES PIC X.",
                        "   05 B PIC X VALUE '2'.",
                        "01 WS-B.",
                        "   05 T OCCURS 2 TIMES PIC X.",
                        "   05 B PIC X VALUE '9'."),
                "MOVE 'x' TO T OF WS-A (1)",
                "MOVE 'y' TO T OF WS-A (2)",
                "MOVE 'a' TO T OF WS-B (1)",
                "MOVE 'b' TO T OF WS-B (2)",
                "MOVE CORRESPONDING WS-A TO WS-B."));
    }

    @Test
    @DisplayName("FILLER は対応付けない (FR-060)")
    void fillerIsNeverCorresponding() {
        assertEquals("1-" + "1-", run(
                List.of("01 WS-A.",
                        "   05 A PIC X VALUE '1'.",
                        "   05 FILLER PIC X VALUE '-'.",
                        "01 WS-B.",
                        "   05 A PIC X VALUE '8'.",
                        "   05 FILLER PIC X VALUE '-'."),
                "MOVE CORRESPONDING WS-A TO WS-B."));
    }

    @Test
    @DisplayName("受取項目は複数書ける (FR-060)")
    void severalReceiversAreExpandedInTurn() {
        assertEquals("1" + "1" + "1", run(
                List.of("01 WS-A.",
                        "   05 A PIC X VALUE '1'.",
                        "01 WS-B.",
                        "   05 A PIC X VALUE '8'.",
                        "01 WS-C.",
                        "   05 A PIC X VALUE '9'."),
                "MOVE CORRESPONDING WS-A TO WS-B WS-C."));
    }

    @Test
    @DisplayName("添字は集団項目に書いたものを引き継ぐ (FR-024, FR-060)")
    void subscriptsOnTheGroupCarryIntoEachPair() {
        assertEquals("12" + "34" + "34", run(
                List.of("01 WS-A.",
                        "   05 R OCCURS 2 TIMES.",
                        "      10 A PIC X.",
                        "      10 B PIC X.",
                        "01 WS-B.",
                        "   05 A PIC X.",
                        "   05 B PIC X."),
                "MOVE '1' TO A OF R (1)",
                "MOVE '2' TO B OF R (1)",
                "MOVE '3' TO A OF R (2)",
                "MOVE '4' TO B OF R (2)",
                "MOVE CORRESPONDING R (2) TO WS-B."));
    }

    @Test
    @DisplayName("基本項目を書いたら誤りとして報告する (FR-060)")
    void anElementaryOperandIsReported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-A PIC X.", "01 WS-B.", "   05 A PIC X."),
                "MOVE CORRESPONDING WS-A TO WS-B.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("requires a group item"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("名前の合う項目が 1 つもなければ誤りとして報告する (FR-060)")
    void anEmptyCorrespondenceIsReported() {
        // 何も移さない MOVE は書き間違いである
        CobolCompiler.Result result = compile(
                List.of("01 WS-A.", "   05 A PIC X.", "01 WS-B.", "   05 Z PIC X."),
                "MOVE CORRESPONDING WS-A TO WS-B.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("no corresponding items"),
                result.diagnostics().toString());
    }
}
