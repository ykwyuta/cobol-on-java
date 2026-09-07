package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 指標名と {@code SEARCH} (要件 FR-025, FR-066)。
 *
 * <p>指標名は COBOL のデータ項目ではないが、<b>反復の番号を持つ入れ物</b>である。
 * 記憶域の後ろへ 2 進項目として足し、添字も {@code SET} も普通の項目と同じ道を通す。
 *
 * <p>記憶域の全体を読み返すと指標の分が混ざるので、確かめるのは {@code DISPLAY} の出力である。
 */
@Tag("V1")
class SearchGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(SearchGenerationTest.class.getClassLoader());
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

    /** 翻訳して実行し、DISPLAY の出力を返す。 */
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

    /** 3 個の表と、それを引く指標。 */
    private static final List<String> TABLE = List.of(
            "01 WS-T.",
            "   05 WS-E OCCURS 3 TIMES INDEXED BY WS-I.",
            "      10 WS-KEY  PIC X(3).",
            "      10 WS-DATA PIC X(3).");

    /** 表へ値を詰める文。 */
    private static final List<String> FILL = List.of(
            "    MOVE 'aaa' TO WS-KEY (1)",
            "    MOVE '111' TO WS-DATA (1)",
            "    MOVE 'bbb' TO WS-KEY (2)",
            "    MOVE '222' TO WS-DATA (2)",
            "    MOVE 'ccc' TO WS-KEY (3)",
            "    MOVE '333' TO WS-DATA (3)");

    private static String[] procedure(String... tail) {
        List<String> lines = new java.util.ArrayList<>(FILL);
        lines.addAll(List.of(tail));
        return lines.toArray(new String[0]);
    }

    @Test
    @DisplayName("指標名は添字に書ける (FR-025)")
    void anIndexNameWorksAsASubscript() {
        assertEquals("[222]|", run(TABLE, procedure(
                "    SET WS-I TO 2",
                "    DISPLAY '[' WS-DATA (WS-I) ']'.")));
    }

    @Test
    @DisplayName("SET ... UP BY は指標を進める (FR-025)")
    void setUpByAdvancesTheIndex() {
        assertEquals("[333]|", run(TABLE, procedure(
                "    SET WS-I TO 1",
                "    SET WS-I UP BY 2",
                "    DISPLAY '[' WS-DATA (WS-I) ']'.")));
    }

    @Test
    @DisplayName("SET ... DOWN BY は指標を戻す (FR-025)")
    void setDownByMovesTheIndexBack() {
        assertEquals("[111]|", run(TABLE, procedure(
                "    SET WS-I TO 3",
                "    SET WS-I DOWN BY 2",
                "    DISPLAY '[' WS-DATA (WS-I) ']'.")));
    }

    @Test
    @DisplayName("SEARCH は当たったところで止まる (FR-066)")
    void searchStopsAtTheFirstMatch() {
        assertEquals("[222]|", run(TABLE, procedure(
                "    SET WS-I TO 1",
                "    SEARCH WS-E",
                "        WHEN WS-KEY (WS-I) = 'bbb'",
                "            DISPLAY '[' WS-DATA (WS-I) ']'",
                "    END-SEARCH.")));
    }

    @Test
    @DisplayName("見つからなければ AT END を通る (FR-066)")
    void atEndRunsWhenNothingMatches() {
        assertEquals("none|", run(TABLE, procedure(
                "    SET WS-I TO 1",
                "    SEARCH WS-E",
                "        AT END DISPLAY 'none'",
                "        WHEN WS-KEY (WS-I) = 'zzz'",
                "            DISPLAY 'found'",
                "    END-SEARCH.")));
    }

    @Test
    @DisplayName("SEARCH は指標を初期化しない (FR-066)")
    void searchDoesNotResetTheIndex() {
        // 3 から見はじめるので、2 番目には当たらない
        assertEquals("none|", run(TABLE, procedure(
                "    SET WS-I TO 3",
                "    SEARCH WS-E",
                "        AT END DISPLAY 'none'",
                "        WHEN WS-KEY (WS-I) = 'bbb'",
                "            DISPLAY 'found'",
                "    END-SEARCH.")));
    }

    @Test
    @DisplayName("すでに範囲の外なら一度も見ずに AT END へ行く (FR-066)")
    void anIndexPastTheEndGoesStraightToAtEnd() {
        assertEquals("none|", run(TABLE, procedure(
                "    SET WS-I TO 4",
                "    SEARCH WS-E",
                "        AT END DISPLAY 'none'",
                "        WHEN WS-KEY (WS-I) = 'aaa'",
                "            DISPLAY 'found'",
                "    END-SEARCH.")));
    }

    @Test
    @DisplayName("抜けたときの指標は当たった位置を指す (FR-066)")
    void theIndexPointsAtTheMatchAfterwards() {
        // SEARCH のあとも指標はそのままなので、そこから表を引ける
        assertEquals("[333]|", run(TABLE, procedure(
                "    SET WS-I TO 1",
                "    SEARCH WS-E",
                "        WHEN WS-KEY (WS-I) = 'ccc'",
                "            CONTINUE",
                "    END-SEARCH",
                "    DISPLAY '[' WS-DATA (WS-I) ']'.")));
    }

    @Test
    @DisplayName("WHEN は書かれた順に試す (FR-066)")
    void whenClausesAreTriedInOrder() {
        // 2 番目の要素は 2 つ目の条件にも当たるが、先に書いたほうが勝つ
        assertEquals("first|", run(TABLE, procedure(
                "    SET WS-I TO 1",
                "    SEARCH WS-E",
                "        WHEN WS-KEY (WS-I) = 'bbb'",
                "            DISPLAY 'first'",
                "        WHEN WS-DATA (WS-I) = '222'",
                "            DISPLAY 'second'",
                "    END-SEARCH.")));
    }

    @Test
    @DisplayName("VARYING の項目も一緒に進む (FR-066)")
    void theVaryingItemAdvancesToo() {
        assertEquals("[003]|", run(
                List.of("01 WS-T.",
                        "   05 WS-E OCCURS 3 TIMES INDEXED BY WS-I.",
                        "      10 WS-KEY  PIC X(3).",
                        "      10 WS-DATA PIC X(3).",
                        "01 WS-N PIC 9(3) VALUE 1."),
                procedure(
                        "    SET WS-I TO 1",
                        "    SEARCH WS-E VARYING WS-N",
                        "        WHEN WS-KEY (WS-I) = 'ccc'",
                        "            CONTINUE",
                        "    END-SEARCH",
                        "    DISPLAY '[' WS-N ']'.")));
    }

    @Test
    @DisplayName("VARYING に指標データ項目を書ける (FR-025, FR-066)")
    void theVaryingItemMayBeAnIndexDataItem() {
        // USAGE INDEX の項目には INDEXED BY のような印が付かない。
        // 表の指標名と取り違えると、名前を切り出すところで壊れる
        assertEquals("[3]|", run(
                List.of("01 WS-T.",
                        "   05 WS-E OCCURS 3 TIMES INDEXED BY WS-I.",
                        "      10 WS-KEY  PIC X(3).",
                        "      10 WS-DATA PIC X(3).",
                        "01 WS-J USAGE IS INDEX.",
                        "01 WS-SHOW PIC 9."),
                procedure(
                        "    SET WS-I TO 1",
                        "    SET WS-J TO 1",
                        "    SEARCH WS-E VARYING WS-J",
                        "        WHEN WS-KEY (WS-I) = 'ccc'",
                        "            CONTINUE",
                        "    END-SEARCH",
                        "    SET WS-SHOW TO WS-J",
                        "    DISPLAY '[' WS-SHOW ']'.")));
    }

    @Test
    @DisplayName("指標名へ MOVE はできない (FR-025)")
    void anIndexNameCannotReceiveAMove() {
        CobolCompiler.Result result = compile(TABLE, "MOVE 1 TO WS-I.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("index name cannot receive"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("SET ... TO の受取側は指標名か整数の項目である (FR-025)")
    void setToRefusesSomethingThatIsNeither() {
        // 文字の項目は受け取れない。何番目かを入れる先ではない
        CobolCompiler.Result result = compile(
                List.of("01 WS-X PIC X(3)."), "SET WS-X TO 1.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message()
                        .contains("an index name or an integer item"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("INDEXED BY のない表は SEARCH できない (FR-066)")
    void aTableWithoutAnIndexIsReported() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-T.", "   05 WS-E OCCURS 3 TIMES PIC X(3)."),
                "SEARCH WS-E WHEN WS-E (1) = 'a' CONTINUE END-SEARCH.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("INDEXED BY"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("SEARCH ALL はまだ書けないと報告する (FR-066)")
    void searchAllIsReportedAsUnsupported() {
        CobolCompiler.Result result = compile(TABLE,
                "SEARCH ALL WS-E WHEN WS-KEY (WS-I) = 'a' CONTINUE END-SEARCH.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("SEARCH ALL"),
                result.diagnostics().toString());
    }

    // ---- SET の受取側 (FR-025、暫定判断 P-035) ----

    @Test
    @DisplayName("SET ... TO は整数の項目へも書ける (FR-025)")
    void setToMayWriteAnIntegerItem() {
        // 規格がそう決めており、実資産も SET WS-COUNT TO IDX と書く。
        // 指標名に限ると、表の何番目にいるかを取り出す手立てが無くなる
        List<String> storage = new java.util.ArrayList<>(TABLE);
        storage.add("01 WS-N PIC 9(4) VALUE 0.");

        assertEquals("[0003]|", run(storage, procedure(
                "    SET WS-I TO 3",
                "    SET WS-N TO WS-I",
                "    DISPLAY '[' WS-N ']'.")));
    }

    @Test
    @DisplayName("USAGE INDEX の項目も SET の受取側になる (FR-025, 暫定判断 P-035)")
    void anIndexDataItemCanBeSet() {
        List<String> storage = new java.util.ArrayList<>(TABLE);
        storage.add("01 WS-SAVE USAGE IS INDEX.");

        assertEquals("[222]|", run(storage, procedure(
                "    SET WS-I TO 2",
                "    SET WS-SAVE TO WS-I",
                "    SET WS-I TO 1",
                "    SET WS-I TO WS-SAVE",
                "    DISPLAY '[' WS-DATA (WS-I) ']'.")));
    }

    @Test
    @DisplayName("SET ... UP BY の受取側は指標名に限る (FR-025)")
    void setUpByNeedsAnIndexName() {
        // 動かしているのは表の中の位置そのものである
        List<String> storage = new java.util.ArrayList<>(TABLE);
        storage.add("01 WS-N PIC 9(4) VALUE 0.");

        CobolCompiler.Result result = compile(storage, procedure("    SET WS-N UP BY 1."));

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("UP/DOWN BY"),
                result.diagnostics().toString());
    }
}
