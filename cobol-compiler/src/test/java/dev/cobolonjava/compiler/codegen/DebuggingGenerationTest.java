package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.file.DataSetCatalog;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code USE FOR DEBUGGING} (要件 FR-193、設計 80)。
 *
 * <p>見張られた手続きへ<b>制御が移るたび</b>にデバッグの節が動く。{@code DEBUG-ITEM} に
 * 誰がどこから移したかが入る。{@code WITH DEBUGGING MODE} を書かなければ、宣言まるごと
 * 注釈と同じ扱いになる。<b>動く・動かないの差が出る形</b>で確かめる。
 */
@Tag("V1")
class DebuggingGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(DebuggingGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String source(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("       ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static String run(String source) {
        return run(null, source);
    }

    private static String run(Path directory, String source) {
        return run(directory, source, true);
    }

    private static String run(Path directory, String source, boolean debuggingProcedures) {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, source);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        ProgramContext context = ProgramContext.capturing(sink)
                .withDebuggingProcedures(debuggingProcedures);
        if (directory != null) {
            context = context.withCatalog(new DataSetCatalog(directory));
        }
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            program.runFresh(context);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    /** 3 バイトのレコードを持つ順編成のファイルを置く。 */
    private static void seed(Path directory, String content) {
        try {
            Files.write(directory.resolve("INDD"), CodePages.DEFAULT.encode(content));
            Files.write(directory.resolve("INDD.meta"),
                    "recfm=F\nlrecl=3\ncodepage=IBM-1047\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** ファイル名を見張るプログラム。 */
    private static String[] fileWatcher() {
        return new String[] {
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. MAIN.",
            "ENVIRONMENT DIVISION.",
            "CONFIGURATION SECTION.",
            "SOURCE-COMPUTER. JVM WITH DEBUGGING MODE.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT IN-FILE ASSIGN TO INDD.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  IN-FILE.",
            "01  IN-REC PIC X(3).",
            "WORKING-STORAGE SECTION.",
            "01  WS-EOF PIC X VALUE 'N'.",
            "PROCEDURE DIVISION.",
            "DECLARATIVES.",
            "WATCH SECTION.",
            "    USE FOR DEBUGGING ON IN-FILE.",
            "WATCH-BODY.",
            "    DISPLAY '<' DEBUG-NAME '|' DEBUG-CONTENTS '>'.",
            "END DECLARATIVES.",
        };
    }

    /** 見出しから宣言までを組み立てる。{@code mode} が {@code true} ならデバッグを有効にする。 */
    private static String[] head(boolean mode) {
        return new String[] {
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. MAIN.",
            "ENVIRONMENT DIVISION.",
            "CONFIGURATION SECTION.",
            "SOURCE-COMPUTER. JVM" + (mode ? " WITH DEBUGGING MODE" : "") + ".",
            "DATA DIVISION.",
            "PROCEDURE DIVISION.",
            "DECLARATIVES.",
            "WATCH SECTION.",
            "    USE FOR DEBUGGING ON TARGET-P.",
            "WATCH-BODY.",
            "    DISPLAY \"SAW \" DEBUG-NAME.",
            "END DECLARATIVES.",
        };
    }

    /** {@code DEBUG-NAME} は 30 桁である。書かれたとおりの名前を左詰めにする。 */
    private static String saw(String name) {
        return "SAW " + name + " ".repeat(30 - name.length());
    }

    private static String[] join(String[] head, String... tail) {
        String[] all = new String[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    @Test
    @DisplayName("見張られた段落へ制御が移ると、デバッグの節が動く")
    void enteringAWatchedParagraphRunsTheDebuggingSection() {
        String out = run(source(join(head(true),
                "MAIN SECTION.",
                "START-P.",
                "    DISPLAY \"BEFORE\".",
                "    GO TO TARGET-P.",
                "TARGET-P.",
                "    DISPLAY \"IN\".",
                "    STOP RUN.")));
        assertEquals("BEFORE|" + saw("TARGET-P") + "|IN|", out);
    }

    @Test
    @DisplayName("WITH DEBUGGING MODE を書かなければ、宣言は注釈と同じになる")
    void withoutDebuggingModeTheDeclarativeIsACommentary() {
        String out = run(source(join(head(false),
                "MAIN SECTION.",
                "START-P.",
                "    DISPLAY \"BEFORE\".",
                "    GO TO TARGET-P.",
                "TARGET-P.",
                "    DISPLAY \"IN\".",
                "    STOP RUN.")));
        assertEquals("BEFORE|IN|", out);
    }

    @Test
    @DisplayName("DEBUG-LINE には制御を移した文の行番号が入る")
    void theLineNumberIsTheOneOfTheTransferringStatement() {
        String out = run(source(join(head(true),
                "MAIN SECTION.",
                "START-P.",
                "    DISPLAY \"L=\" DEBUG-LINE.",
                "    GO TO TARGET-P.",
                "TARGET-P.",
                "    STOP RUN.")));
        // GO TO は 17 行目である。制御を移す前は DEBUG-LINE も空白のままである
        assertEquals("L=      |" + saw("TARGET-P") + "|", out);
    }

    @Test
    @DisplayName("ALTER も見張られる。DEBUG-CONTENTS に書き換え先が入る")
    void alteringAWatchedParagraphRunsTheDebuggingSection() {
        String out = run(source(join(new String[] {
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. MAIN.",
            "ENVIRONMENT DIVISION.",
            "CONFIGURATION SECTION.",
            "SOURCE-COMPUTER. JVM WITH DEBUGGING MODE.",
            "DATA DIVISION.",
            "PROCEDURE DIVISION.",
            "DECLARATIVES.",
            "WATCH SECTION.",
            "    USE FOR DEBUGGING ON TARGET-P.",
            "WATCH-BODY.",
            "    DISPLAY \"SAW \" DEBUG-NAME \"/\" DEBUG-CONTENTS.",
            "END DECLARATIVES.",
        },
                "MAIN SECTION.",
                "START-P.",
                "    ALTER TARGET-P TO PROCEED TO SECOND-P.",
                "    GO TO TARGET-P.",
                "TARGET-P.",
                "    GO TO FIRST-P.",
                "FIRST-P.",
                "    DISPLAY \"FIRST\".",
                "    STOP RUN.",
                "SECOND-P.",
                "    DISPLAY \"SECOND\".",
                "    STOP RUN.")));
        // ALTER の直後に 1 度、GO TO で入って 1 度、あわせて 2 度動く。
        // DEBUG-CONTENTS は書き換え先を持つが、制御が移ったほうでは空白である
        assertEquals(saw("TARGET-P") + "/SECOND-P" + " ".repeat(22) + "|"
                + saw("TARGET-P") + "/" + " ".repeat(30) + "|"
                + "SECOND|", out);
    }

    @Test
    @DisplayName("見張られていない段落では動かない")
    void anUnwatchedParagraphDoesNotRunTheSection() {
        String out = run(source(join(head(true),
                "MAIN SECTION.",
                "START-P.",
                "    GO TO OTHER-P.",
                "OTHER-P.",
                "    DISPLAY \"OTHER\".",
                "    STOP RUN.")));
        assertEquals("OTHER|", out);
    }

    @Test
    @DisplayName("ALL PROCEDURES はどの段落でも動く")
    void allProceduresWatchesEveryParagraph() {
        String out = run(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MAIN.",
                "ENVIRONMENT DIVISION.",
                "CONFIGURATION SECTION.",
                "SOURCE-COMPUTER. JVM WITH DEBUGGING MODE.",
                "DATA DIVISION.",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "WATCH SECTION.",
                "    USE FOR DEBUGGING ON ALL PROCEDURES.",
                "WATCH-BODY.",
                "    DISPLAY \"SAW \" DEBUG-NAME.",
                "END DECLARATIVES.",
                "MAIN SECTION.",
                "START-P.",
                "    PERFORM OTHER-P.",
                "    STOP RUN.",
                "OTHER-P.",
                "    DISPLAY \"OTHER\"."));
        // 章の見出しと最初の段落でも動く。章へ入ると<b>2 度</b>動くのが規格である
        assertEquals(saw("MAIN") + "|" + saw("START-P") + "|" + saw("OTHER-P") + "|OTHER|",
                out);
    }

    @Test
    @DisplayName("7 桁目の D の行は WITH DEBUGGING MODE のときだけ動く")
    void aDebuggingLineRunsOnlyWithDebuggingMode() {
        String[] lines = {
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. MAIN.",
            "       ENVIRONMENT DIVISION.",
            "       CONFIGURATION SECTION.",
            "       SOURCE-COMPUTER. JVM%s.",
            "       DATA DIVISION.",
            "       PROCEDURE DIVISION.",
            "       MAIN-P.",
            "           DISPLAY \"A\".",
            "      D    DISPLAY \"D\".",
            "           STOP RUN.",
        };
        assertEquals("A|D|", run(String.join("\n",
                String.join("\n", lines).formatted(" WITH DEBUGGING MODE")) + "\n"));
        assertEquals("A|", run(String.join("\n", lines).formatted("") + "\n"));
    }

    @Test
    @DisplayName("注釈に書かれた WITH DEBUGGING MODE では有効にならない")
    void aCommentMentioningDebuggingModeDoesNotEnableIt() {
        String out = run(String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. MAIN.",
                "      * SOURCE-COMPUTER. JVM WITH DEBUGGING MODE.",
                "       ENVIRONMENT DIVISION.",
                "       CONFIGURATION SECTION.",
                "       SOURCE-COMPUTER. JVM.",
                "       DATA DIVISION.",
                "       PROCEDURE DIVISION.",
                "       MAIN-P.",
                "           DISPLAY \"A\".",
                "      D    DISPLAY \"D\".",
                "           STOP RUN.") + "\n");
        assertEquals("A|", out);
    }

    @Test
    @DisplayName("ファイル名を見張ると、その入出力文のあとで節が動く (FR-193)")
    void watchingAFileRunsTheSectionAfterEachIoStatement(@TempDir Path directory) {
        seed(directory, "ABCDEF");
        String out = run(directory, source(join(fileWatcher(),
                "MAIN SECTION.",
                "START-P.",
                "    OPEN INPUT IN-FILE.",
                "    READ IN-FILE AT END MOVE 'Y' TO WS-EOF.",
                "    CLOSE IN-FILE.",
                "    STOP RUN.")));
        // OPEN と CLOSE では DEBUG-CONTENTS は空白、READ では読んだレコードが入る
        assertEquals(watched("IN-FILE", "") + "|"
                + watched("IN-FILE", "ABC") + "|"
                + watched("IN-FILE", "") + "|", out);
    }

    @Test
    @DisplayName("読めなかった READ では節は動かない (FR-193)")
    void anUnsuccessfulReadDoesNotRunTheSection() {
        // AT END は「レコードが渡らなかった」ということである。DEBUG-CONTENTS に
        // 入れるものが無い (DB203A の READ-TEST-2)
        String out = runWithEmptyFile();
        assertEquals(watched("IN-FILE", "") + "|"
                + "AT END|"
                + watched("IN-FILE", "") + "|", out);
    }

    private static String runWithEmptyFile() {
        try {
            Path directory = Files.createTempDirectory("debug-empty");
            seed(directory, "");
            return run(directory, source(join(fileWatcher(),
                    "MAIN SECTION.",
                    "START-P.",
                    "    OPEN INPUT IN-FILE.",
                    "    READ IN-FILE AT END DISPLAY 'AT END'.",
                    "    CLOSE IN-FILE.",
                    "    STOP RUN.")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("見張られていないファイルでは動かない (FR-193)")
    void anUnwatchedFileDoesNotRunTheSection(@TempDir Path directory) {
        seed(directory, "ABC");
        String[] head = fileWatcher();
        head[17] = "    USE FOR DEBUGGING ON ALL PROCEDURES.";
        String out = run(directory, source(join(head,
                "MAIN SECTION.",
                "START-P.",
                "    OPEN INPUT IN-FILE.",
                "    CLOSE IN-FILE.",
                "    STOP RUN.")));
        // ALL PROCEDURES なので段落では動くが、ファイルの入出力では動かない
        assertEquals(watched("MAIN", "START PROGRAM") + "|"
                + watched("START-P", "FALL THROUGH") + "|", out);
    }

    /** 見張りの節が印字する 1 行。DEBUG-NAME は 30 桁、DEBUG-CONTENTS はレコードの幅。 */
    private static String watched(String name, String contents) {
        return "<" + name + " ".repeat(30 - name.length())
                + "|" + contents + " ".repeat(30 - contents.length()) + ">";
    }

    @Test
    @DisplayName("DEBUG-CONTENTS には、なぜその手続きへ来たかが入る (FR-193)")
    void theContentsSayWhyTheProcedureWasEntered() {
        String out = run(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MAIN.",
                "ENVIRONMENT DIVISION.",
                "CONFIGURATION SECTION.",
                "SOURCE-COMPUTER. JVM WITH DEBUGGING MODE.",
                "DATA DIVISION.",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "WATCH SECTION.",
                "    USE FOR DEBUGGING ON ALL PROCEDURES.",
                "WATCH-BODY.",
                "    DISPLAY '[' DEBUG-NAME '|' DEBUG-CONTENTS ']'.",
                "END DECLARATIVES.",
                "MAIN SECTION.",
                "START-P.",
                "    PERFORM CALLED-P.",
                "    GO TO JUMPED-P.",
                "NEVER-P.",
                "    DISPLAY 'NEVER'.",
                "JUMPED-P.",
                "    STOP RUN.",
                "CALLED-P.",
                "    CONTINUE.",
                "NEXT-P.",
                "    EXIT."));
        // 章の見出し MAIN は「いちばん最初に入った手続き」であり START PROGRAM。
        // START-P はそこから落ちたので FALL THROUGH。CALLED-P は PERFORM で
        // 入ったので PERFORM LOOP。JUMPED-P は GO TO なので空白である。
        // NEXT-P は PERFORM の範囲の外なので入らない
        assertEquals(why("MAIN", "START PROGRAM") + "|"
                + why("START-P", "FALL THROUGH") + "|"
                + why("CALLED-P", "PERFORM LOOP") + "|"
                + why("JUMPED-P", "") + "|", out);
    }

    @Test
    @DisplayName("書き換えられる段落へ GO TO で入っても、理由は空白である (FR-193)")
    void anAlteredParagraphIsStillAnExplicitTransfer() {
        // 中身が GO TO 1 つだけの段落は本体を出さずに飛び先を返す。行番号と理由を
        // そこで控え損ねると、1 つ前の落ち込みの理由が残ってしまう
        String out = run(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MAIN.",
                "ENVIRONMENT DIVISION.",
                "CONFIGURATION SECTION.",
                "SOURCE-COMPUTER. JVM WITH DEBUGGING MODE.",
                "DATA DIVISION.",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "WATCH SECTION.",
                "    USE FOR DEBUGGING ON SWITCH-P.",
                "WATCH-BODY.",
                "    DISPLAY '[' DEBUG-NAME '|' DEBUG-CONTENTS ']'.",
                "END DECLARATIVES.",
                "MAIN SECTION.",
                "START-P.",
                "    ALTER SWITCH-P TO PROCEED TO TARGET-P.",
                "FALLEN-P.",
                "    GO TO SWITCH-P.",
                "SWITCH-P.",
                "    GO TO NEVER-P.",
                "NEVER-P.",
                "    DISPLAY 'NEVER'.",
                "TARGET-P.",
                "    STOP RUN."));
        // ALTER そのものでも 1 度動く。そのときの DEBUG-CONTENTS は書き換え先である
        assertEquals(why("SWITCH-P", "TARGET-P") + "|" + why("SWITCH-P", "") + "|", out);
    }

    /** 見張りの節が印字する 1 行。 */
    private static String why(String name, String contents) {
        return "[" + name + " ".repeat(30 - name.length())
                + "|" + contents + " ".repeat(30 - contents.length()) + "]";
    }

    @Test
    @DisplayName("実行時の切り替えを切ると、デバッグ行は動いて節は動かない (FR-193)")
    void theObjectTimeSwitchStopsTheSectionsButNotTheDebuggingLines() {
        String[] lines = {
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. MAIN.",
            "       ENVIRONMENT DIVISION.",
            "       CONFIGURATION SECTION.",
            "       SOURCE-COMPUTER. JVM WITH DEBUGGING MODE.",
            "       DATA DIVISION.",
            "       PROCEDURE DIVISION.",
            "       DECLARATIVES.",
            "       WATCH SECTION.",
            "           USE FOR DEBUGGING ON ALL PROCEDURES.",
            "       WATCH-BODY.",
            "           DISPLAY \"SAW \" DEBUG-NAME.",
            "       END DECLARATIVES.",
            "       MAIN SECTION.",
            "       START-P.",
            "           DISPLAY \"A\".",
            "      D    DISPLAY \"D\".",
            "           STOP RUN.",
        };
        String text = String.join("\n", lines) + "\n";
        // 切り替えが立っていれば、章と段落で 2 度動く
        assertEquals(saw("MAIN") + "|" + saw("START-P") + "|A|D|", run(null, text, true));
        // 切ると節だけが止まる。7 桁目の D の行は動いたままである
        assertEquals("A|D|", run(null, text, false));
    }

    /** 一意名を見張るプログラムの見出し。{@code target} が見張りの書き方である。 */
    private static String[] itemWatcher(String target) {
        return new String[] {
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. MAIN.",
            "ENVIRONMENT DIVISION.",
            "CONFIGURATION SECTION.",
            "SOURCE-COMPUTER. JVM WITH DEBUGGING MODE.",
            "DATA DIVISION.",
            "WORKING-STORAGE SECTION.",
            "01  WS-A PIC 99 VALUE 0.",
            "01  WS-B PIC 99 VALUE 0.",
            "01  WS-T.",
            "    02 WS-E PIC X OCCURS 5.",
            "PROCEDURE DIVISION.",
            "DECLARATIVES.",
            "WATCH SECTION.",
            "    USE FOR DEBUGGING ON " + target + ".",
            "WATCH-BODY.",
            "    DISPLAY '<' DEBUG-NAME '|' DEBUG-CONTENTS '>'.",
            "END DECLARATIVES.",
        };
    }

    /** 見張りの節が印字する 1 行。 */
    private static String saw(String name, String contents) {
        return "<" + name + " ".repeat(30 - name.length())
                + "|" + contents + " ".repeat(30 - contents.length()) + ">";
    }

    @Test
    @DisplayName("ALL REFERENCES OF は指した文のたびに動く (FR-193)")
    void allReferencesFiresOnEveryStatementThatNamesTheItem() {
        String out = run(source(join(itemWatcher("ALL REFERENCES OF WS-A"),
                "MAIN SECTION.",
                "START-P.",
                "    MOVE 5 TO WS-A.",
                "    MOVE WS-A TO WS-B.",
                "    MOVE 7 TO WS-B.",
                "    STOP RUN.")));
        // 3 つ目は WS-A を指していないので動かない
        assertEquals(saw("WS-A", "05") + "|" + saw("WS-A", "05") + "|", out);
    }

    @Test
    @DisplayName("ALL REFERENCES OF を書かなければ、受け取る側のときだけ動く (FR-193)")
    void aPlainWatchFiresOnlyWhenTheItemReceives() {
        String out = run(source(join(itemWatcher("WS-A"),
                "MAIN SECTION.",
                "START-P.",
                "    MOVE 5 TO WS-A.",
                "    MOVE WS-A TO WS-B.",
                "    STOP RUN.")));
        assertEquals(saw("WS-A", "05") + "|", out);
    }

    @Test
    @DisplayName("同じ名前を何度書いても、1 つの文では 1 度である (FR-193)")
    void oneStatementFiresOnceEvenWithSeveralReferences() {
        // ADD WS-A WS-A WS-A TO WS-B は 1 度である (DB201A の ADD-TEST-1)
        String out = run(source(join(itemWatcher("ALL REFERENCES OF WS-A"),
                "MAIN SECTION.",
                "START-P.",
                "    MOVE 1 TO WS-A.",
                "    ADD WS-A WS-A WS-A TO WS-B.",
                "    STOP RUN.")));
        assertEquals(saw("WS-A", "01") + "|" + saw("WS-A", "01") + "|", out);
    }

    @Test
    @DisplayName("添字は DEBUG-SUB-1 へ入る (FR-193)")
    void theSubscriptGoesIntoDebugSubOne() {
        String out = run(source(join(new String[] {
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. MAIN.",
            "ENVIRONMENT DIVISION.",
            "CONFIGURATION SECTION.",
            "SOURCE-COMPUTER. JVM WITH DEBUGGING MODE.",
            "DATA DIVISION.",
            "WORKING-STORAGE SECTION.",
            "01  WS-I PIC 9 VALUE 3.",
            "01  WS-T.",
            "    02 WS-E PIC X OCCURS 5.",
            "PROCEDURE DIVISION.",
            "DECLARATIVES.",
            "WATCH SECTION.",
            "    USE FOR DEBUGGING ON WS-E.",
            "WATCH-BODY.",
            "    DISPLAY '<' DEBUG-NAME '|' DEBUG-SUB-1 '>'.",
            "END DECLARATIVES.",
        },
                "MAIN SECTION.",
                "START-P.",
                "    MOVE 'Z' TO WS-E (WS-I).",
                "    STOP RUN.")));
        assertEquals("<WS-E" + " ".repeat(26) + "|+0003>|", out);
    }

    @Test
    @DisplayName("PERFORM VARYING は置くたび・見るたびに動く (FR-193)")
    void aVaryingPerformFiresOnEveryStepAndTest() {
        // VARYING WS-A ... UNTIL WS-A > 3 は 4 + 4 = 8 度である
        // (DB201A の PERFORM-VARY-2)
        String out = run(source(join(itemWatcher("WS-A"),
                "MAIN SECTION.",
                "START-P.",
                "    PERFORM DO-NOTHING VARYING WS-A FROM 1 BY 1",
                "        UNTIL WS-A IS GREATER THAN 3.",
                "    STOP RUN.",
                "DO-NOTHING SECTION.",
                "DO-NOTHING-P.",
                "    CONTINUE.")));
        // 置いてから見る、を 4 周。02 で始まらないのは、置いた直後に動くからである
        assertEquals(saw("WS-A", "01") + "|" + saw("WS-A", "01") + "|"
                + saw("WS-A", "02") + "|" + saw("WS-A", "02") + "|"
                + saw("WS-A", "03") + "|" + saw("WS-A", "03") + "|"
                + saw("WS-A", "04") + "|" + saw("WS-A", "04") + "|", out);
    }

    @Test
    @DisplayName("修飾を書けば DEBUG-NAME にも入る (FR-193)")
    void aQualifiedNameIsShownQualified() {
        String out = run(source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MAIN.",
                "ENVIRONMENT DIVISION.",
                "CONFIGURATION SECTION.",
                "SOURCE-COMPUTER. JVM WITH DEBUGGING MODE.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01  G-ONE.",
                "    02 WS-X PIC XX.",
                "01  G-TWO.",
                "    02 WS-X PIC XX.",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "WATCH SECTION.",
                "    USE FOR DEBUGGING ON ALL REFERENCES OF WS-X OF G-TWO.",
                "WATCH-BODY.",
                "    DISPLAY '<' DEBUG-NAME '>'.",
                "END DECLARATIVES.",
                "MAIN SECTION.",
                "START-P.",
                "    MOVE 'AB' TO WS-X OF G-ONE.",
                "    MOVE 'CD' TO WS-X OF G-TWO.",
                "    STOP RUN."));
        assertEquals("<WS-X OF G-TWO" + " ".repeat(17) + ">|", out);
    }
}
