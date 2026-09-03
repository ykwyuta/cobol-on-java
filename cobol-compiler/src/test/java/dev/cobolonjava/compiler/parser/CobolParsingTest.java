package dev.cobolonjava.compiler.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.source.MapCopyBookResolver;
import dev.cobolonjava.compiler.source.Preprocessor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class CobolParsingTest {

    private static final String FILE = "MAIN.cbl";

    private static String source(String... contents) {
        StringBuilder sb = new StringBuilder();
        for (String content : contents) {
            sb.append("       ").append(content).append('\n');
        }
        return sb.toString();
    }

    private static CobolParsing.Result parse(String... contents) {
        return CobolParsing.parse(Preprocessor.withoutCopybooks(), FILE, source(contents));
    }

    private static CobolParsing.Result parseOk(String... contents) {
        CobolParsing.Result result = parse(contents);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        return result;
    }

    @Test
    @DisplayName("最小のプログラムを解析できる (ARC-8)")
    void aMinimalProgramParses() {
        CobolParsing.Result result = parseOk(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.");
        CobolParser.ProgramUnitContext unit = result.tree().programUnit(0);
        assertEquals("HELLO",
                unit.identificationDivision().programIdParagraph().programName().getText());
    }

    @Test
    @DisplayName("ID DIVISION の短い綴りも受け付ける (ARC-8)")
    void theShortSpellingOfTheIdentificationDivisionIsAccepted() {
        parseOk("ID DIVISION.", "PROGRAM-ID. HELLO.");
    }

    @Test
    @DisplayName("4 つの部をひととおり解析できる (ARC-8)")
    void allFourDivisionsParse() {
        parseOk(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "ENVIRONMENT DIVISION.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "PROCEDURE DIVISION.",
                "END PROGRAM HELLO.");
    }

    @Test
    @DisplayName("データ記述項の PICTURE は島のまま構文木へ入る (ARC-8, D-14)")
    void aPictureStringReachesTheTreeAsOneToken() {
        CobolParsing.Result result = parseOk(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-AMOUNT PIC S9(7)V99 COMP-3.");

        CobolParser.DataDescriptionEntryContext entry = firstEntry(result);
        assertEquals("01", entry.levelNumber().getText());
        assertEquals("WS-AMOUNT", entry.dataName().getText());
        assertEquals("S9(7)V99", entry.dataClause(0).pictureClause().PICTURE_STRING().getText());
        assertEquals("COMP-3", entry.dataClause(1).usageClause().getText());
    }

    @Test
    @DisplayName("PICTURE の中のピリオドで項目が切れない (Q-15, ARC-8)")
    void aPeriodInsideAPictureDoesNotEndTheEntry() {
        // 島にしていなければ、ここで項目が 2 つに割れる
        CobolParsing.Result result = parseOk(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-EDITED PIC ZZ,ZZ9.99.");

        CobolParser.DataDescriptionEntryContext entry = firstEntry(result);
        assertEquals("ZZ,ZZ9.99", entry.dataClause(0).pictureClause().PICTURE_STRING().getText());
    }

    @Test
    @DisplayName("データ記述項の各種の句を解析できる (ARC-8)")
    void theClausesOfADataDescriptionEntryParse() {
        parseOk(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-REC.",
                "   05 WS-COUNT PIC 9(4) COMP VALUE ZERO.",
                "   05 WS-NAME  PIC X(20) VALUE SPACES.",
                "   05 WS-FLAG  PIC X JUSTIFIED RIGHT.",
                "   05 WS-SIGNED PIC S9(5) SIGN IS LEADING SEPARATE.",
                "   05 WS-TABLE OCCURS 10 TIMES INDEXED BY WS-I.",
                "      10 WS-ITEM PIC X(3).",
                "   05 WS-ALIAS REDEFINES WS-REC PIC X(40).",
                "   05 WS-MONEY PIC ZZ9.99 BLANK WHEN ZERO.");
    }

    @Test
    @DisplayName("利用者定義語と予約語を綴りで見分ける (ARC-8)")
    void reservedWordsAreDistinguishedBySpelling() {
        // COBOL の予約語でない語は、文法へ宣言していないので利用者定義語になる
        CobolParsing.Result result = parseOk(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 LEVEL-CODE PIC X.");
        assertEquals("LEVEL-CODE", firstEntry(result).dataName().getText());
    }

    @Test
    @DisplayName("誤りは例外にせず診断として集める (FR-183)")
    void errorsAreCollectedRatherThanThrown() {
        // 1 つ目の誤りで止まると、移行作業で使い物にならない
        CobolParsing.Result result = parse(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID.");
        assertFalse(result.succeeded());
        assertFalse(result.diagnostics().isEmpty());
    }

    @Test
    @DisplayName("診断は元のソース上の位置を指す (FR-094, FR-183)")
    void aDiagnosticPointsAtTheOriginalSource() {
        CobolParsing.Result result = parse(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-A PIC X(3) REDEFINES.");

        Diagnostic first = result.diagnostics().get(0);
        assertEquals(FILE, first.origin().fileName());
        assertEquals(5, first.origin().line());
    }

    @Test
    @DisplayName("診断はコピー句の中の位置も指せる (FR-090, FR-094)")
    void aDiagnosticCanPointInsideACopybook() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("BADREC", source("01 WS-A.", "05 WS-B PIC X REDEFINES."));

        CobolParsing.Result result = CobolParsing.parse(Preprocessor.with(resolver), FILE, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "COPY BADREC."));

        Diagnostic first = result.diagnostics().get(0);
        assertEquals("BADREC", first.origin().fileName(), "展開後ではなくコピー句を指す");
        assertEquals(2, first.origin().line());
    }

    @Test
    @DisplayName("コピー句から来たデータ項目も解析できる (FR-090, ARC-8)")
    void dataItemsFromACopybookParse() {
        MapCopyBookResolver resolver = new MapCopyBookResolver()
                .put("CUSTREC", source("01 CUST-REC.", "   05 CUST-ID PIC 9(5)."));

        CobolParsing.Result result = CobolParsing.parse(Preprocessor.with(resolver), FILE, source(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "COPY CUSTREC."));

        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        List<CobolParser.DataDescriptionEntryContext> entries = entries(result);
        assertEquals(2, entries.size());
        assertEquals("CUST-REC", entries.get(0).dataName().getText());
        assertEquals("9(5)", entries.get(1).dataClause(0).pictureClause().PICTURE_STRING().getText());
    }

    private static List<CobolParser.DataDescriptionEntryContext> entries(CobolParsing.Result result) {
        return result.tree().programUnit(0).dataDivision().dataDivisionSection(0)
                .workingStorageSection().dataDescriptionEntry();
    }

    private static CobolParser.DataDescriptionEntryContext firstEntry(CobolParsing.Result result) {
        return entries(result).get(0);
    }
}
