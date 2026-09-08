package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 環境部とファイル記述項の書き方の幅 (要件 FR-100, FR-113, FR-193)。
 *
 * <p>ここで確かめるのは<b>断らないこと</b>である。装置の割り付けや記憶の共有を書いた
 * 指定は、翻訳の結果には効かない。効かないものを「読めない」と言って断ると、
 * <b>中身は書けているのにプログラムが 1 本も通らない</b>。
 *
 * <p>NIST の検査スイートで実際に詰まったところを並べてある。どれも規格どおりの
 * 書き方であり、こちらが読めていなかっただけである。
 */
@Tag("V1")
class EnvironmentClausesTest {

    private static final String FILE = "MAIN.cbl";

    /** 環境部とデータ部を差し替えられる 1 本のプログラムにする。 */
    private static String program(List<String> environment, List<String> fileSection) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.")) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : environment) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : List.of("DATA DIVISION.", "FILE SECTION.")) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : fileSection) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : List.of(
                "WORKING-STORAGE SECTION.",
                "01 WS-EOF PIC XX VALUE '00'.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    DISPLAY 'OK'.")) {
            FixedFormatSource.append(sb, line);
        }
        return sb.toString();
    }

    private static CobolCompiler.Result compile(String source) {
        return CobolCompiler.standard().compile(FILE, source);
    }

    private static void accepted(String source) {
        CobolCompiler.Result result = compile(source);
        assertTrue(result.succeeded(),
                () -> "unexpected diagnostics: " + result.diagnostics());
    }

    private static final List<String> ONE_RECORD = List.of(
            "FD  IN-FILE.",
            "01  IN-REC PIC X(80).");

    @Test
    @DisplayName("SELECT の句は順を問わない。ASSIGN があとに来てもよい (FR-100)")
    void theClausesOfSelectComeInAnyOrder() {
        // 規格が並びを決めているのは SELECT と名前だけである。CCVS85 は ACCESS を
        // 先に書き、ORGANIZATION IS を省き、ASSIGN を最後に置く
        accepted(program(List.of(
                "    SELECT IN-FILE",
                "        ACCESS MODE IS SEQUENTIAL",
                "        SEQUENTIAL",
                "        ASSIGN TO INDD."), ONE_RECORD));
    }

    @Test
    @DisplayName("FILE STATUS の FILE は省いてよい (FR-100)")
    void theWordFileMayBeLeftOutOfFileStatus() {
        accepted(program(List.of(
                "    SELECT IN-FILE ASSIGN TO INDD",
                "        STATUS WS-EOF."), ONE_RECORD));
    }

    @Test
    @DisplayName("ASSIGN が無ければ、どこへ結び付けるか分からないと断る (FR-100)")
    void aSelectWithoutAssignIsRefused() {
        // 文法では位置を縛らないので、必ず 1 つあることはここで見る。
        // 黙って通すと DD 名の無いファイルができる
        CobolCompiler.Result result = compile(program(List.of(
                "    SELECT IN-FILE",
                "        ORGANIZATION IS SEQUENTIAL."), ONE_RECORD));

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("no ASSIGN clause"),
                () -> result.diagnostics().toString());
    }

    @Test
    @DisplayName("裸の RELATIVE は編成、名前が続けば相対キーである (FR-100)")
    void aBareRelativeIsTheOrganization() {
        accepted(program(List.of(
                "    SELECT IN-FILE ASSIGN TO INDD",
                "        RELATIVE",
                "        ACCESS MODE IS SEQUENTIAL."), ONE_RECORD));
    }

    @Test
    @DisplayName("装置の割り付けを書いた句は読んで捨てる (FR-100)")
    void theClausesAboutDevicesAreReadAndDropped() {
        // 緩衝をいくつ取るか、埋め草に何を使うか、レコードをどこで切るか。
        // どれも翻訳の結果には効かない
        accepted(program(List.of(
                "    SELECT IN-FILE ASSIGN TO INDD",
                "        RESERVE 3 AREAS",
                "        PADDING CHARACTER IS SPACE",
                "        RECORD DELIMITER IS STANDARD-1."), ONE_RECORD));
    }

    @Test
    @DisplayName("I-O-CONTROL は 1 つの終止符に指定を何本でも入れられる (FR-100)")
    void oneStopMayCloseSeveralIoControlEntries() {
        // CCVS85 は SAME を 2 行並べて最後だけ終止符を打つ。指定ごとに終止符を
        // 要求すると、正しいプログラムを断ってしまう
        accepted(program(List.of(
                "    SELECT IN-FILE ASSIGN TO INDD.",
                "    SELECT OUT-FILE ASSIGN TO OUTDD.",
                "I-O-CONTROL.",
                "    SAME RECORD AREA FOR IN-FILE OUT-FILE",
                "    SAME AREA FOR IN-FILE OUT-FILE.",
                "    MULTIPLE FILE TAPE CONTAINS IN-FILE POSITION 1",
                "                               OUT-FILE POSITION 2."),
                List.of(
                        "FD  IN-FILE.",
                        "01  IN-REC PIC X(80).",
                        "FD  OUT-FILE.",
                        "01  OUT-REC PIC X(80).")));
    }

    @Test
    @DisplayName("FD の VALUE OF と IS GLOBAL は読んで捨てる (FR-100)")
    void theLabelClausesOfAnFdAreReadAndDropped() {
        // VALUE OF は「ラベルに何を書くか」であり、規格でも廃要素である
        accepted(program(List.of("    SELECT IN-FILE ASSIGN TO INDD."), List.of(
                "FD  IN-FILE",
                "    IS GLOBAL",
                "    LABEL RECORDS ARE STANDARD",
                "    VALUE OF DSNAME IS 'CCVSFIL1'",
                "    BLOCK CONTAINS 10 RECORDS.",
                "01  IN-REC PIC X(80).")));
    }

    @Test
    @DisplayName("LINAGE は行数と、脚注・上余白・下余白まで読む (FR-113)")
    void linageCarriesItsFootingAndMargins() {
        accepted(program(List.of("    SELECT IN-FILE ASSIGN TO INDD."), List.of(
                "FD  IN-FILE",
                "    LINAGE IS 50 LINES",
                "        WITH FOOTING AT 45",
                "        LINES AT TOP 10",
                "        LINES AT BOTTOM 6.",
                "01  IN-REC PIC X(80).")));
    }

    @Test
    @DisplayName("USE FOR DEBUGGING の節は注釈と同じに扱う (FR-193)")
    void aDebuggingSectionIsTreatedAsComment() {
        // WITH DEBUGGING MODE が書かれていなければ、デバッグの節は注釈である。
        // これは手加減ではなく規格の決まりであり、7 桁目の D を落とすのと同じ扱いである。
        // <b>本体を組み立てない</b>のが肝で、組み立てると DEBUG-ITEM を
        // 「宣言されていない」と言うことになる
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-N PIC 9 VALUE 0.",
                "PROCEDURE DIVISION.",
                "DECLARATIVES.",
                "DEBUG-SECTION SECTION.",
                "    USE FOR DEBUGGING ON ALL PROCEDURES.",
                "DEBUG-PARA.",
                "    DISPLAY DEBUG-ITEM.",
                "END DECLARATIVES.",
                "MAIN-START.",
                "    DISPLAY 'OK'.")) {
            FixedFormatSource.append(sb, line);
        }

        accepted(sb.toString());
    }
}
