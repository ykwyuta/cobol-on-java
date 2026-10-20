package dev.cobolonjava.compiler;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.source.CicsSystemCopyBookResolver;
import dev.cobolonjava.compiler.source.Db2SystemCopyBookResolver;
import dev.cobolonjava.compiler.source.LanguageEnvironmentCopyBookResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@code docs/unsupported-syntax-and-rationale.md} の表の 1 行ずつを、翻訳器にかけて確かめる。
 *
 * <p>一覧は文書だけで持っていたあいだに古くなった。実装済みの BMS、チャネル、{@code RENAMES} を
 * 「未対応」と書いたまま、{@code WHENEVER} や {@code RECORDING MODE U} のような本当に断る形を
 * 載せていなかった。表の行と同じ名前の事例をここに置き、実装が変われば試験が落ちて
 * 文書を直すことになるようにする。
 *
 * <p>期待は「通る」か「この文言で断る」かだけで、通ったものの振る舞いは見ない (それは各機能の試験の役目)。
 */
class UnsupportedSyntaxCatalogTest {

    /** 翻訳が通ること。 */
    private static final String ACCEPTED = "";

    private static final List<String> FILE_CONTROL = List.of(
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT F ASSIGN TO DDF.");

    private record Source(String name, List<String> environment, List<String> data, List<String> procedure) {
    }

    private static Source program(String name, List<String> environment, List<String> data,
                                  List<String> procedure) {
        return new Source(name, environment, data, procedure);
    }

    private static Source program(String name, List<String> data, List<String> procedure) {
        return new Source(name, List.of(), data, procedure);
    }

    private static String text(Source source) {
        List<String> lines = new ArrayList<>();
        lines.add("IDENTIFICATION DIVISION.");
        lines.add("PROGRAM-ID. " + source.name() + ".");
        lines.addAll(source.environment());
        lines.add("DATA DIVISION.");
        lines.addAll(source.data());
        lines.add("PROCEDURE DIVISION.");
        for (String statement : source.procedure()) {
            lines.add("    " + statement);
        }
        lines.add("    GOBACK.");
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String card = "       " + line;
            if (card.length() > 72) {
                // 72 桁を超えると原文の側が壊れる (CLAUDE.md §6)。試験の誤りとして止める
                throw new IllegalArgumentException("test source exceeds column 72: " + card);
            }
            out.append(card).append('\n');
        }
        return out.toString();
    }

    private static List<String> ws(String... lines) {
        List<String> out = new ArrayList<>();
        out.add("WORKING-STORAGE SECTION.");
        out.addAll(List.of(lines));
        return out;
    }

    private static List<String> file(String fd) {
        return List.of("FILE SECTION.", fd, "01 R PIC X(80).");
    }

    private static List<String> report(String controls, String footing) {
        List<String> out = new ArrayList<>(List.of(
                "FILE SECTION.",
                "FD F REPORT IS RPT.",
                "WORKING-STORAGE SECTION.",
                "01 D PIC X(2).",
                "01 AMT PIC 9(3).",
                "REPORT SECTION.",
                "RD RPT " + controls + " PAGE LIMIT 60.",
                "01 DL TYPE DETAIL LINE PLUS 1.",
                "   05 COLUMN 1 PIC X(2) SOURCE D."));
        out.addAll(List.of(footing.split("\n")));
        return out;
    }

    /** 名前、原文、期待 (空なら通る。そうでなければ診断に含まれる文言)。 */
    static Stream<Arguments> catalog() {
        return Stream.of(
                // 2.1 見出し部・環境部
                Arguments.of(program("CUR2",
                        List.of("ENVIRONMENT DIVISION.", "CONFIGURATION SECTION.", "SPECIAL-NAMES.",
                                "    CURRENCY SIGN IS 'EUR' WITH PICTURE SYMBOL 'E'."),
                        ws("01 A PIC E9(4)."), List.of()), "extraneous input 'WITH'"),
                Arguments.of(program("REVR", FILE_CONTROL, file("FD F."),
                        List.of("OPEN INPUT F REVERSED", "CLOSE F")), "OPEN ... REVERSED is not supported"),
                Arguments.of(program("COMM", List.of("COMMUNICATION SECTION.", "CD C1 FOR INPUT."), List.of()),
                        "COMMUNICATION SECTION is not supported"),
                Arguments.of(program("RERUNT",
                        List.of("ENVIRONMENT DIVISION.", "INPUT-OUTPUT SECTION.", "FILE-CONTROL.",
                                "    SELECT F ASSIGN TO DDF.", "I-O-CONTROL.",
                                "    RERUN ON DDCK EVERY 100 RECORDS OF F."),
                        file("FD F."), List.of("OPEN INPUT F", "CLOSE F")), ACCEPTED),
                // 2.2 データ部
                Arguments.of(program("BIN20", ws("01 A PIC S9(20) COMP-5."), List.of("ADD 1 TO A")),
                        "binary items of 20 digits are not supported"),
                Arguments.of(program("PTRUP", ws("01 P USAGE POINTER."), List.of("SET P UP BY 4")),
                        "SET of a POINTER item supports only TO NULL or another POINTER"),
                Arguments.of(program("FPTR", ws("01 P USAGE FUNCTION-POINTER."), List.of()),
                        "mismatched input 'FUNCTION-POINTER'"),
                Arguments.of(program("PPTR", ws("01 P USAGE PROCEDURE-POINTER."), List.of()),
                        "mismatched input 'PROCEDURE-POINTER'"),
                Arguments.of(program("NATL", ws("01 N PIC N(10) USAGE NATIONAL.", "01 M PIC N(10) USAGE NATIONAL."),
                        List.of("MOVE N'AB' TO N", "MOVE N TO M")), ACCEPTED),
                Arguments.of(program("NATNUM", ws("01 N PIC 9(5) USAGE NATIONAL."), List.of("MOVE 12 TO N")),
                        "USAGE NATIONAL is supported only with PICTURE N"),
                Arguments.of(program("DBCS", ws("01 G PIC G(10) USAGE DISPLAY-1."), List.of()),
                        "USAGE DISPLAY-1 is not supported yet"),
                Arguments.of(program("RENM", ws("01 R.", "   05 A PIC X(2).", "   05 B PIC X(2).",
                        "66 AB RENAMES A THRU B."), List.of("MOVE 'XXXX' TO AB")), ACCEPTED),
                // 2.3 手続き部
                Arguments.of(program("INITV", ws("01 G.", "   05 A PIC 9(3).", "01 V PIC 9(3) VALUE 7."),
                        List.of("INITIALIZE G REPLACING NUMERIC DATA BY V")), ACCEPTED),
                Arguments.of(program("SRCH2", ws("01 T.", "   05 O OCCURS 3 INDEXED BY I.",
                                "      10 E OCCURS 3 ASCENDING KEY K INDEXED BY J.", "         15 K PIC 9."),
                        List.of("SET I TO 1", "SEARCH ALL E AT END CONTINUE", "    WHEN K(I, J) = 1 CONTINUE",
                                "END-SEARCH")), ACCEPTED),
                Arguments.of(program("FLTDIV", ws("01 F COMP-2."), List.of("COMPUTE F = F / 3")),
                        "COMPUTE with floating-point items supports only +, - and *"),
                Arguments.of(program("FLTADD", ws("01 F COMP-2."), List.of("ADD 1 TO F")),
                        "with floating-point items is not supported yet"),
                Arguments.of(program("FLTDSP", ws("01 F COMP-1 VALUE 1."), List.of("DISPLAY F")),
                        "DISPLAY of a floating-point item is not supported yet"),
                Arguments.of(program("BYVAL", ws("01 A PIC S9(9) COMP-5."), List.of("CALL 'SUB' USING BY VALUE A")),
                        "CALL ... BY VALUE is not supported yet"),
                Arguments.of(program("CANCL", List.of(), List.of("CANCEL 'SUB'")), ACCEPTED),
                Arguments.of(program("SORTRET", ws("01 A PIC S9(4) COMP."), List.of("MOVE SORT-RETURN TO A")),
                        "undefined data item: SORT-RETURN"),
                Arguments.of(program("XMLP", ws("01 D PIC X(80)."),
                        List.of("XML PARSE D PROCESSING PROCEDURE P1 END-XML")), "XMLPARSE"),
                Arguments.of(program("JSONG", ws("01 D PIC X(80).", "01 G.", "   05 A PIC X."),
                        List.of("JSON GENERATE D FROM G END-JSON")), "JSONGENERATE"),
                // 2.4 ファイル
                Arguments.of(program("RECU", FILE_CONTROL, file("FD F RECORDING MODE U."),
                        List.of("OPEN INPUT F", "CLOSE F")), "RECORDING MODE U is not supported yet"),
                Arguments.of(program("RECS", FILE_CONTROL, file("FD F RECORDING MODE S."),
                        List.of("OPEN INPUT F", "CLOSE F")), "RECORDING MODE S is not supported yet"),
                Arguments.of(program("BLKC", FILE_CONTROL, file("FD F BLOCK CONTAINS 10 RECORDS."),
                        List.of("OPEN INPUT F", "CLOSE F")), ACCEPTED),
                // 2.5 報告書作成
                Arguments.of(program("RWC", FILE_CONTROL,
                        report("CONTROLS ARE FINAL D", "01 TYPE CONTROL FOOTING D LINE PLUS 1.\n"
                                + "   05 COLUMN 5 PIC 9(5) SUM AMT."),
                        List.of("OPEN OUTPUT F", "INITIATE RPT", "GENERATE DL", "TERMINATE RPT", "CLOSE F")),
                        "a report with CONTROL breaks is not supported yet"),
                // 2.6 サブシステム
                Arguments.of(program("WHENEV", ws("    EXEC SQL INCLUDE SQLCA END-EXEC."),
                        List.of("EXEC SQL WHENEVER SQLERROR CONTINUE END-EXEC")),
                        "unsupported EXEC SQL statement: WHENEVER"),
                Arguments.of(program("PREP", ws("    EXEC SQL INCLUDE SQLCA END-EXEC.", "01 Q PIC X(80)."),
                        List.of("EXEC SQL PREPARE S1 FROM :Q END-EXEC")), "unsupported EXEC SQL statement: PREPARE"),
                Arguments.of(program("DLI", List.of(), List.of("EXEC DLI GU USING PCB(1) END-EXEC")),
                        "EXEC processor is not supported yet"),
                Arguments.of(program("CXFER", List.of(),
                        List.of("EXEC CICS LINK PROGRAM('P') CHANNEL('CH') END-EXEC")),
                        "unsupported EXEC CICS option: CHANNEL('CH')"),
                Arguments.of(program("CHABP", List.of(), List.of("EXEC CICS HANDLE ABEND PROGRAM('E') END-EXEC")),
                        "initial HANDLE ABEND support accepts LABEL, CANCEL, or RESET"),
                Arguments.of(program("CTRAN", ws("01 T PIC X(4)."), List.of("EXEC CICS RETURN TRANSID(T) END-EXEC")),
                        "unsupported EXEC CICS option: TRANSID(T)"),
                Arguments.of(program("CRQTS", ws("01 A PIC X(10)."),
                        List.of("EXEC CICS READQ TS QUEUE('Q') INTO(A) END-EXEC")), "READQ TS requires either ITEM or NEXT"),
                Arguments.of(program("CSTUT", List.of(), List.of("EXEC CICS START TRANSID('TX02') USERID('U1')",
                        "    TERMID('T001') END-EXEC")), "START USERID with TERMID is not supported"),
                Arguments.of(program("CRECV", ws("01 A PIC X(10).", "01 L PIC S9(4) COMP."),
                        List.of("EXEC CICS RECEIVE INTO(A) LENGTH(L) END-EXEC")), "unsupported or malformed EXEC CICS block"),
                // MQ は CALL 'MQOPEN' が翻訳では普通の動的 CALL として通り、実行で見つからない
                Arguments.of(program("MQ", ws("01 H PIC S9(9) BINARY."), List.of("CALL 'MQOPEN' USING H")), ACCEPTED),
                // 2.7 削除済み旧構文。断るが、旧構文であることを示す専用の文言は持たない
                Arguments.of(program("EXAM", ws("01 A PIC X(5)."), List.of("EXAMINE A TALLYING ALL 'X'")),
                        "no viable alternative at input 'EXAMINE"),
                Arguments.of(program("TRAN", ws("01 A PIC X(5)."), List.of("TRANSFORM A FROM 'A' TO 'B'")),
                        "no viable alternative at input 'TRANSFORM"),
                Arguments.of(program("NOTEST", List.of(), List.of("NOTE THIS IS A NOTE.")),
                        "no viable alternative at input 'NOTE"),
                Arguments.of(program("ONST", List.of(), List.of("ON 2 DISPLAY 'X'.")), "extraneous input 'ON'"),
                Arguments.of(program("CDAY", ws("01 A PIC X(5)."), List.of("MOVE CURRENT-DAY TO A")),
                        "undefined data item: CURRENT-DAY"));
    }

    private static CobolCompiler compiler() {
        CicsSystemCopyBookResolver cics = new CicsSystemCopyBookResolver();
        Db2SystemCopyBookResolver db2 = new Db2SystemCopyBookResolver();
        LanguageEnvironmentCopyBookResolver le = new LanguageEnvironmentCopyBookResolver();
        return CobolCompiler.with((name, library) -> cics.resolve(name, library)
                .or(() -> db2.resolve(name, library))
                .or(() -> le.resolve(name, library))
                .or(Optional::empty));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("catalog")
    void matchesTheCatalog(Source source, String expected) {
        CobolCompiler.Result result = compiler().compile(source.name() + ".cbl", text(source));
        String diagnostics = result.diagnostics().toString();
        if (expected.isEmpty()) {
            assertTrue(result.succeeded(), () -> source.name() + " should be accepted: " + diagnostics);
        } else {
            assertTrue(!result.succeeded() && diagnostics.contains(expected),
                    () -> source.name() + " should be refused with '" + expected + "': " + diagnostics);
        }
    }
}
