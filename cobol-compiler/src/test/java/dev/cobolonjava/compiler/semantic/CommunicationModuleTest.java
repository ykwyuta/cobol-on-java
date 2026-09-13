package dev.cobolonjava.compiler.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 通信機能 ({@code COMMUNICATION SECTION} と通信の文) は支えていない (制約 C-5)。
 *
 * <p>支えていないものでも<b>どこまでがそれか</b>は読む。読まないと
 * 「extraneous input 'COMMUNICATION'」としか言えず、支えていないのか書き方が悪いのかが
 * 読む側に分からない (要件 FR-190)。
 */
@Tag("V1")
class CommunicationModuleTest {

    private static final String FILE = "MAIN.cbl";

    private static String source(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("       ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static List<String> diagnosticsOf(String... lines) {
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, source(lines));
        assertFalse(result.succeeded(), () -> "expected a diagnostic: " + result.diagnostics());
        return result.diagnostics().stream().map(Object::toString).toList();
    }

    @Test
    @DisplayName("通信節は、節と名指しして断る (C-5、FR-190)")
    void theCommunicationSectionIsRejectedByName() {
        List<String> diagnostics = diagnosticsOf(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. COMMS.",
                "DATA DIVISION.",
                "COMMUNICATION SECTION.",
                "CD  CM-IN FOR INITIAL INPUT",
                "    END KEY IS END-KEY",
                "    STATUS KEY IS STATUS-KEY.",
                "01  CM-REC.",
                "    03 CM-TEXT PIC X(20).",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    STOP RUN.");

        // 節の中身を読み飛ばせているので、診断は 1 つだけである。
        // 読み飛ばせていなければ CD や END KEY のところで構文誤りが並ぶ
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.get(0).contains("COMMUNICATION SECTION is not supported"),
                diagnostics.toString());
    }

    @Test
    @DisplayName("通信の文は、文と名指しして断る (C-5、FR-190)")
    void theCommunicationStatementsAreRejectedByName() {
        for (String statement : List.of(
                "ENABLE INPUT CM-IN WITH KEY 'PASS'.",
                "DISABLE INPUT CM-IN WITH KEY 'PASS'.",
                "RECEIVE CM-IN MESSAGE INTO WS-TEXT NO DATA CONTINUE.",
                "SEND CM-OUT FROM WS-TEXT.",
                "PURGE CM-OUT.")) {
            List<String> diagnostics = diagnosticsOf(
                    "IDENTIFICATION DIVISION.",
                    "PROGRAM-ID. COMMS.",
                    "DATA DIVISION.",
                    "WORKING-STORAGE SECTION.",
                    "01  WS-TEXT PIC X(20).",
                    "PROCEDURE DIVISION.",
                    "MAIN-START.",
                    "    " + statement,
                    "    STOP RUN.");

            assertEquals(1, diagnostics.size(), statement + ": " + diagnostics);
            assertTrue(diagnostics.get(0).contains("the communication module is not supported"),
                    statement + ": " + diagnostics);
        }
    }

    @Test
    @DisplayName("ACCEPT ... MESSAGE COUNT も通信の文である (C-5)")
    void acceptMessageCountIsACommunicationStatement() {
        // MESSAGE は書いても書かなくてもよい。CM101M は両方の形を書いている
        for (String statement : List.of("ACCEPT CM-IN MESSAGE COUNT.", "ACCEPT CM-IN COUNT.")) {
            List<String> diagnostics = diagnosticsOf(
                    "IDENTIFICATION DIVISION.",
                    "PROGRAM-ID. COMMS.",
                    "DATA DIVISION.",
                    "WORKING-STORAGE SECTION.",
                    "01  CM-IN PIC X(20).",
                    "PROCEDURE DIVISION.",
                    "MAIN-START.",
                    "    " + statement,
                    "    STOP RUN.");

            assertEquals(1, diagnostics.size(), statement + ": " + diagnostics);
            assertTrue(diagnostics.get(0).contains("ACCEPT MESSAGE COUNT"),
                    statement + ": " + diagnostics);
        }
    }

    @Test
    @DisplayName("通信節を読み飛ばしても、そのあとの節は読む (C-5)")
    void whatFollowsTheCommunicationSectionIsStillRead() {
        // 読み飛ばしが行き過ぎていないことを、<b>あとの節の誤り</b>で確かめる。
        // 飲み込みすぎていれば、この 2 つ目の診断が出ない
        List<String> diagnostics = diagnosticsOf(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. COMMS.",
                "DATA DIVISION.",
                "COMMUNICATION SECTION.",
                "CD  CM-IN FOR INPUT.",
                "01  CM-REC PIC X(20).",
                "WORKING-STORAGE SECTION.",
                "01  WS-GRP.",
                "    03 WS-A.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    STOP RUN.");

        assertEquals(2, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.get(0).contains("COMMUNICATION SECTION is not supported"),
                diagnostics.toString());
        assertTrue(diagnostics.get(1).contains("WS-A"), diagnostics.toString());
    }
}
