package dev.cobolonjava.compiler.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class ProcessStatementTest {

    private static final String FILE = "MAIN.cbl";

    private static ProcessStatement.Scan scan(String... lines) {
        return ProcessStatement.scan(String.join("\n", lines));
    }

    @Test
    @DisplayName("CBL のオプションを読む (FR-093)")
    void optionsOfACblStatementAreRead() {
        CompilerOptions options = scan("CBL SOURCEFORMAT(FREE),TRUNC(BIN),APOST").options();
        assertEquals(Optional.of("FREE"), options.value("SOURCEFORMAT"));
        assertEquals(Optional.of("BIN"), options.value("TRUNC"));
        assertTrue(options.has("APOST"));
        assertFalse(options.has("QUOTE"));
    }

    @Test
    @DisplayName("PROCESS も CBL と同じに扱う (FR-093)")
    void processIsTheSameAsCbl() {
        assertTrue(scan("PROCESS NOSEQ").options().has("NOSEQ"));
    }

    @Test
    @DisplayName("名前は大文字と小文字を区別しない (FR-093)")
    void optionNamesAreCaseInsensitive() {
        assertTrue(scan("cbl apost").options().has("APOST"));
    }

    @Test
    @DisplayName("括弧の中の読点は区切りではない (FR-093)")
    void aCommaInsideParenthesesIsNotASeparator() {
        assertEquals(Optional.of("SHORT,FULL"), scan("CBL XREF(SHORT,FULL)").options()
                .value("XREF"));
    }

    @Test
    @DisplayName("括弧が閉じていなければ誤りとして検出する (FR-093)")
    void anUnbalancedParenthesisIsRejected() {
        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> scan("CBL TRUNC(BIN"));
        assertTrue(e.getMessage().contains("unbalanced"), e.getMessage());
    }

    @Test
    @DisplayName("一連番号領域があってもプロセス文と分かる (FR-093)")
    void aSequenceNumberDoesNotHideTheStatement() {
        assertTrue(scan("000100 CBL APOST").options().has("APOST"));
    }

    @Test
    @DisplayName("プロセス文は複数行書ける (FR-093)")
    void severalProcessStatementsMayAppear() {
        CompilerOptions options = scan("CBL APOST", "PROCESS TRUNC(STD)").options();
        assertTrue(options.has("APOST"));
        assertEquals(Optional.of("STD"), options.value("TRUNC"));
    }

    @Test
    @DisplayName("プロセス文の前には注釈と空行を置ける (FR-093)")
    void commentsAndBlankLinesMayPrecedeTheStatement() {
        assertTrue(scan("", "      * a remark", "CBL APOST").options().has("APOST"));
    }

    @Test
    @DisplayName("本文が始まったらもう探さない (FR-093)")
    void theSearchStopsAtTheFirstContentLine() {
        assertFalse(scan(
                "       IDENTIFICATION DIVISION.",
                "CBL APOST").options().has("APOST"));
    }

    @Test
    @DisplayName("プロセス文の行は空行に置き換える (FR-093, FR-094)")
    void theStatementLineIsBlankedRatherThanRemoved() {
        // 行を削ると、以降のすべての行番号がずれて診断が元のソースを指せなくなる
        ProcessStatement.Scan scan = scan("CBL APOST", "       MOVE A TO B.");
        assertEquals("\n       MOVE A TO B.", scan.source());
    }

    @Test
    @DisplayName("PROCESS- で始まる語はプロセス文ではない (FR-093)")
    void aWordThatMerelyStartsWithProcessIsNotAStatement() {
        assertFalse(scan("       PROCESS-RECORD.").options().has("RECORD"));
    }

    @Test
    @DisplayName("SOURCEFORMAT(FREE) が読み取り器を切り替える (FR-002, FR-093)")
    void sourceFormatSwitchesTheReader() {
        // 固定形式として構成しても、ソースの指定が優先する
        NormalizedSource result = Preprocessor.withoutCopybooks().process(FILE, String.join("\n",
                "CBL SOURCEFORMAT(FREE)",
                "MOVE A TO B."));
        assertEquals("MOVE A TO B.", result.text());
        assertEquals(1, result.originOf(0).column(), "自由形式なので 1 桁目から本文である");
        assertEquals(2, result.originOf(0).line());
    }

    @Test
    @DisplayName("SOURCEFORMAT(FIXED) は自由形式の構成より優先する (FR-002, FR-093)")
    void sourceFormatFixedOverridesAFreeFormatConfiguration() {
        NormalizedSource result =
                Preprocessor.with((name, library) -> Optional.empty(), FreeFormatReader.standard())
                        .process(FILE, String.join("\n",
                                "CBL SOURCEFORMAT(FIXED)",
                                "       MOVE A TO B."));
        assertEquals("MOVE A TO B.", result.text());
        assertEquals(8, result.originOf(0).column(), "固定形式なので本文は 8 桁目から");
    }

    @Test
    @DisplayName("知らない SOURCEFORMAT の値は誤りとして検出する (FR-093)")
    void anUnknownSourceFormatValueIsRejected() {
        SourceFormatException e = assertThrows(SourceFormatException.class,
                () -> scan("CBL SOURCEFORMAT(TERSE)").options().sourceFormat());
        assertTrue(e.getMessage().contains("SOURCEFORMAT"), e.getMessage());
    }

    @Test
    @DisplayName("プロセス文がなければソースはそのままである (FR-093)")
    void aSourceWithoutAProcessStatementIsUntouched() {
        String source = "       MOVE A TO B.\n";
        ProcessStatement.Scan scan = ProcessStatement.scan(source);
        assertEquals(source, scan.source());
        assertEquals(CompilerOptions.NONE, scan.options());
    }
}
