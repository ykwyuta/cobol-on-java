package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code SEARCH ALL} の 2 分探索 (要件 FR-066)。
 *
 * <p>逐次の {@code SEARCH} と違い<b>指標を用意しなくてよい</b>。探索そのものが範囲を狭めながら
 * 指標を決める。当たれば指標はその位置を指す。
 *
 * <p>書ける条件は鍵と値の等号だけである。大きいか小さいかで半分を捨てる仕組みだからである。
 */
@Tag("V1")
class SearchAllGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(SearchAllGenerationTest.class.getClassLoader());
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

    /** 鍵が昇順に並んだ 5 個の表。 */
    private static final List<String> ASCENDING = List.of(
            "01 WS-TAB.",
            "   05 WS-E OCCURS 5 TIMES",
            "            ASCENDING KEY IS WS-K INDEXED BY WS-I.",
            "      10 WS-K PIC 9(3).",
            "      10 WS-V PIC X(3).");

    /** 鍵が降順に並んだ 5 個の表。 */
    private static final List<String> DESCENDING = List.of(
            "01 WS-TAB.",
            "   05 WS-E OCCURS 5 TIMES",
            "            DESCENDING KEY IS WS-K INDEXED BY WS-I.",
            "      10 WS-K PIC 9(3).",
            "      10 WS-V PIC X(3).");

    /** 昇順の表へ 010〜050 を詰める文。 */
    private static String[] filled(String... tail) {
        List<String> lines = new ArrayList<>();
        String[] values = {"aaa", "bbb", "ccc", "ddd", "eee"};
        for (int i = 1; i <= 5; i++) {
            lines.add("    MOVE " + (i * 10) + " TO WS-K (" + i + ")");
            lines.add("    MOVE '" + values[i - 1] + "' TO WS-V (" + i + ")");
        }
        lines.addAll(List.of(tail));
        return lines.toArray(new String[0]);
    }

    /** 降順の表へ 050〜010 を詰める文。 */
    private static String[] filledDescending(String... tail) {
        List<String> lines = new ArrayList<>();
        String[] values = {"eee", "ddd", "ccc", "bbb", "aaa"};
        for (int i = 1; i <= 5; i++) {
            lines.add("    MOVE " + ((6 - i) * 10) + " TO WS-K (" + i + ")");
            lines.add("    MOVE '" + values[i - 1] + "' TO WS-V (" + i + ")");
        }
        lines.addAll(List.of(tail));
        return lines.toArray(new String[0]);
    }

    @Test
    @DisplayName("真ん中の鍵を見つける (FR-066)")
    void findsAKeyInTheMiddle() {
        assertEquals("[ccc]|", run(ASCENDING, filled(
                "    SEARCH ALL WS-E",
                "        AT END DISPLAY 'none'",
                "        WHEN WS-K (WS-I) = 30",
                "            DISPLAY '[' WS-V (WS-I) ']'",
                "    END-SEARCH.")));
    }

    @Test
    @DisplayName("最初の鍵を見つける (FR-066)")
    void findsTheFirstKey() {
        assertEquals("[aaa]|", run(ASCENDING, filled(
                "    SEARCH ALL WS-E",
                "        AT END DISPLAY 'none'",
                "        WHEN WS-K (WS-I) = 10",
                "            DISPLAY '[' WS-V (WS-I) ']'",
                "    END-SEARCH.")));
    }

    @Test
    @DisplayName("最後の鍵を見つける (FR-066)")
    void findsTheLastKey() {
        assertEquals("[eee]|", run(ASCENDING, filled(
                "    SEARCH ALL WS-E",
                "        AT END DISPLAY 'none'",
                "        WHEN WS-K (WS-I) = 50",
                "            DISPLAY '[' WS-V (WS-I) ']'",
                "    END-SEARCH.")));
    }

    @Test
    @DisplayName("どの鍵も当たらなければ AT END を通る (FR-066)")
    void atEndRunsWhenNothingMatches() {
        assertEquals("none|", run(ASCENDING, filled(
                "    SEARCH ALL WS-E",
                "        AT END DISPLAY 'none'",
                "        WHEN WS-K (WS-I) = 35",
                "            DISPLAY 'found'",
                "    END-SEARCH.")));
    }

    @Test
    @DisplayName("範囲の外の値でも AT END を通る (FR-066)")
    void aValueOutsideTheRangeAlsoEndsAtTheEnd() {
        assertEquals("none|none|", run(ASCENDING, filled(
                "    SEARCH ALL WS-E",
                "        AT END DISPLAY 'none'",
                "        WHEN WS-K (WS-I) = 5",
                "            DISPLAY 'found'",
                "    END-SEARCH",
                "    SEARCH ALL WS-E",
                "        AT END DISPLAY 'none'",
                "        WHEN WS-K (WS-I) = 99",
                "            DISPLAY 'found'",
                "    END-SEARCH.")));
    }

    @Test
    @DisplayName("指標は使う側が用意しなくてよい (FR-066)")
    void theIndexNeedNotBeSetFirst() {
        // SET を書かずにすべての鍵を引ける。逐次の SEARCH との違いである
        assertEquals("[aaa][bbb][ccc][ddd][eee] |", run(
                List.of("01 WS-TAB.",
                        "   05 WS-E OCCURS 5 TIMES",
                        "            ASCENDING KEY IS WS-K INDEXED BY WS-I.",
                        "      10 WS-K PIC 9(3).",
                        "      10 WS-V PIC X(3).",
                        "01 WS-N PIC 9(3) VALUE 0."),
                filled(
                        "    PERFORM VARYING WS-N FROM 10 BY 10 UNTIL WS-N > 50",
                        "        SEARCH ALL WS-E",
                        "            AT END DISPLAY 'none' WITH NO ADVANCING",
                        "            WHEN WS-K (WS-I) = WS-N",
                        "                DISPLAY '[' WS-V (WS-I) ']' WITH NO ADVANCING",
                        "        END-SEARCH",
                        "    END-PERFORM",
                        "    DISPLAY ' '.")));
    }

    @Test
    @DisplayName("降順の表でも見つける (FR-066)")
    void aDescendingTableIsSearchedTheOtherWay() {
        // 捨てる半分が逆になる。昇順のつもりで書けば見つからない
        assertEquals("[ccc]|", run(DESCENDING, filledDescending(
                "    SEARCH ALL WS-E",
                "        AT END DISPLAY 'none'",
                "        WHEN WS-K (WS-I) = 30",
                "            DISPLAY '[' WS-V (WS-I) ']'",
                "    END-SEARCH.")));
    }

    @Test
    @DisplayName("英数字の鍵も引ける (FR-066)")
    void anAlphanumericKeyWorks() {
        assertEquals("[003]|", run(
                List.of("01 WS-TAB.",
                        "   05 WS-E OCCURS 3 TIMES",
                        "            ASCENDING KEY IS WS-K INDEXED BY WS-I.",
                        "      10 WS-K PIC X(3).",
                        "      10 WS-V PIC 9(3)."),
                "    MOVE 'aaa' TO WS-K (1)",
                "    MOVE 1 TO WS-V (1)",
                "    MOVE 'bbb' TO WS-K (2)",
                "    MOVE 2 TO WS-V (2)",
                "    MOVE 'ccc' TO WS-K (3)",
                "    MOVE 3 TO WS-V (3)",
                "    SEARCH ALL WS-E",
                "        AT END DISPLAY 'none'",
                "        WHEN WS-K (WS-I) = 'ccc'",
                "            DISPLAY '[' WS-V (WS-I) ']'",
                "    END-SEARCH."));
    }

    @Test
    @DisplayName("鍵は 2 つ以上書ける (FR-066)")
    void twoKeysNarrowTheSearch() {
        assertEquals("[y]|", run(
                List.of("01 WS-TAB.",
                        "   05 WS-E OCCURS 4 TIMES",
                        "            ASCENDING KEY IS WS-A WS-B INDEXED BY WS-I.",
                        "      10 WS-A PIC 9.",
                        "      10 WS-B PIC 9.",
                        "      10 WS-V PIC X."),
                "    MOVE 1 TO WS-A (1)",
                "    MOVE 1 TO WS-B (1)",
                "    MOVE 'w' TO WS-V (1)",
                "    MOVE 1 TO WS-A (2)",
                "    MOVE 2 TO WS-B (2)",
                "    MOVE 'x' TO WS-V (2)",
                "    MOVE 2 TO WS-A (3)",
                "    MOVE 1 TO WS-B (3)",
                "    MOVE 'y' TO WS-V (3)",
                "    MOVE 2 TO WS-A (4)",
                "    MOVE 2 TO WS-B (4)",
                "    MOVE 'z' TO WS-V (4)",
                "    SEARCH ALL WS-E",
                "        AT END DISPLAY 'none'",
                "        WHEN WS-A (WS-I) = 2 AND WS-B (WS-I) = 1",
                "            DISPLAY '[' WS-V (WS-I) ']'",
                "    END-SEARCH."));
    }

    @Test
    @DisplayName("KEY のない表は SEARCH ALL できない (FR-066)")
    void aTableWithoutKeysIsReported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-TAB.",
                        "   05 WS-E OCCURS 3 TIMES INDEXED BY WS-I.",
                        "      10 WS-K PIC 9(3)."),
                "SEARCH ALL WS-E WHEN WS-K (WS-I) = 1 CONTINUE END-SEARCH.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("ASCENDING or DESCENDING KEY"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("等号でない条件は誤りとして報告する (FR-066)")
    void aNonEqualityConditionIsReported() {
        CobolCompiler.Result result = compile(ASCENDING,
                "SEARCH ALL WS-E WHEN WS-K (WS-I) > 30 CONTINUE END-SEARCH.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("equality tests"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("宣言と違う鍵を試したら誤りとして報告する (FR-066)")
    void testingTheWrongKeyIsReported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-TAB.",
                        "   05 WS-E OCCURS 3 TIMES",
                        "            ASCENDING KEY IS WS-K INDEXED BY WS-I.",
                        "      10 WS-K PIC 9(3).",
                        "      10 WS-V PIC 9(3)."),
                "SEARCH ALL WS-E WHEN WS-V (WS-I) = 1 CONTINUE END-SEARCH.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("expected WS-K"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("探索の指標で引いていなければ誤りとして報告する (FR-066)")
    void aKeyNotSubscriptedByTheSearchIndexIsReported() {
        CobolCompiler.Result result = compile(ASCENDING,
                "SEARCH ALL WS-E WHEN WS-K (1) = 30 CONTINUE END-SEARCH.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("subscripted by the search"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("WHEN は 1 つだけである (FR-066)")
    void onlyOneWhenIsAllowed() {
        CobolCompiler.Result result = compile(ASCENDING,
                "SEARCH ALL WS-E",
                "    WHEN WS-K (WS-I) = 10 CONTINUE",
                "    WHEN WS-K (WS-I) = 20 CONTINUE",
                "END-SEARCH.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("single WHEN"),
                result.diagnostics().toString());
    }
}
