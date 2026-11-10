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
 * 85 規格のあとに足された書き方のうち、Enterprise COBOL の資産がふつうに使うもの。
 *
 * <p>どれも ZCVS (Zeus の検査スイート) の ZX・ZS・ZD の区分を流して見つかった。
 * CCVS85 は 85 規格の検査なので、これらを 1 つも測らない。期待値の根拠は 2002 規格と
 * Enterprise COBOL の言語解説書であり、実機とは突き合わせていない。
 */
@Tag("V1")
class EnterpriseExtensionsTest {

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(EnterpriseExtensionsTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static CobolCompiler.Result compile(String... lines) {
        StringBuilder source = new StringBuilder();
        for (String line : lines) {
            source.append("       ").append(line).append('\n');
        }
        return CobolCompiler.standard().compile("PGM.cbl", source.toString());
    }

    /** 手続き部の文だけを書いて動かし、DISPLAY の出力を返す。 */
    private static List<String> run(List<String> data, String... procedure) {
        List<String> lines = new ArrayList<>(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. PGM.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-I PIC 9(3) VALUE 0.",
                "01 WS-ACC PIC 9(5) VALUE 0."));
        lines.addAll(data);
        lines.add("PROCEDURE DIVISION.");
        lines.addAll(List.of(procedure));
        return runSource(lines.toArray(String[]::new));
    }

    private static List<String> runSource(String... lines) {
        CobolCompiler.Result result = compile(lines);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            ((CobolProgram) type.getDeclaredConstructor().newInstance())
                    .runFresh(ProgramContext.capturing(sink));
            return sink.toString(StandardCharsets.UTF_8).lines().toList();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot load the generated program", e);
        }
    }

    // ---- 制御の移し方 ----

    @Test
    @DisplayName("EXIT PERFORM はいちばん内側の繰り返しだけを抜ける")
    void exitPerformLeavesTheInnermostLoop() {
        assertEquals(List.of("00003"), run(List.of(),
                "MAIN-START.",
                "    PERFORM 3 TIMES",
                "        PERFORM 5 TIMES",
                "            ADD 1 TO WS-ACC",
                "            EXIT PERFORM",
                "            ADD 100 TO WS-ACC",
                "        END-PERFORM",
                "    END-PERFORM",
                "    DISPLAY WS-ACC",
                "    STOP RUN."));
    }

    @Test
    @DisplayName("EXIT PERFORM CYCLE は残りを飛ばし、VARYING を進めて次の回の判定へ行く")
    void exitPerformCycleAdvancesTheLoop() {
        // CONTINUE と同じ扱いなら 00050、EXIT PERFORM と同じ扱いなら 00013 になる
        assertEquals(List.of("00020", "005"), run(List.of(),
                "MAIN-START.",
                "    PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 4",
                "        ADD WS-I TO WS-ACC",
                "        IF WS-I >= 2",
                "            EXIT PERFORM CYCLE",
                "        END-IF",
                "        ADD 10 TO WS-ACC",
                "    END-PERFORM",
                "    DISPLAY WS-ACC DISPLAY WS-I",
                "    STOP RUN."));
    }

    @Test
    @DisplayName("PERFORM UNTIL EXIT は EXIT PERFORM で抜けるまで繰り返す")
    void performUntilExitLoopsUntilExitPerform() {
        assertEquals(List.of("00004"), run(List.of(),
                "MAIN-START.",
                "    PERFORM UNTIL EXIT",
                "        ADD 1 TO WS-ACC",
                "        IF WS-ACC > 3 EXIT PERFORM END-IF",
                "    END-PERFORM",
                "    DISPLAY WS-ACC",
                "    STOP RUN."));
    }

    @Test
    @DisplayName("EXIT PARAGRAPH は段落の、EXIT SECTION は章の終わりへ進み、PERFORM から戻る")
    void exitParagraphAndExitSection() {
        assertEquals(List.of("P1", "S1", "BACK"), run(List.of(),
                "MAIN-SEC SECTION.",
                "MAIN-START.",
                "    PERFORM PAR-A",
                "    PERFORM SUB-SEC",
                "    DISPLAY 'BACK'",
                "    STOP RUN.",
                "PAR-A.",
                "    DISPLAY 'P1'",
                "    EXIT PARAGRAPH.",
                "    DISPLAY 'P2'.",
                "SUB-SEC SECTION.",
                "SUB-1.",
                "    DISPLAY 'S1'",
                "    EXIT SECTION.",
                "SUB-2.",
                "    DISPLAY 'S2'."));
    }

    @Test
    @DisplayName("EXIT PERFORM を繰り返しの外に書いたら断る")
    void exitPerformOutsideALoopIsRefused() {
        CobolCompiler.Result result = compile(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. PGM.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    EXIT PERFORM.");
        assertFalse(result.succeeded());
    }

    @Test
    @DisplayName("条件名で終わる条件のあとの文に比較があっても、条件はそこで終わっている")
    void aConditionEndsWhereAStatementBegins() {
        // 次の文の「=」まで先読みして、条件名を省略した比較の相手として読んでいた
        assertEquals(List.of("BOTH"), run(
                List.of("01 WS-CODE PIC 9 VALUE 1.",
                        "   88 IS-ONE VALUE 1."),
                "MAIN-START.",
                "    IF WS-CODE = 1 AND IS-ONE",
                "        MOVE 5 TO WS-I",
                "        IF WS-I = 5 DISPLAY 'BOTH' END-IF",
                "    END-IF",
                "    STOP RUN."));
    }

    // ---- 条件名と INITIALIZE ----

    @Test
    @DisplayName("SET 条件名 TO FALSE は WHEN SET TO FALSE の値を入れる")
    void setToFalseMovesTheFalseValue() {
        assertEquals(List.of("1", "0"), run(
                List.of("01 WS-CODE PIC 9 VALUE 9.",
                        "   88 IS-ACTIVE VALUE 1 THRU 3 WHEN SET TO FALSE IS 0."),
                "MAIN-START.",
                "    SET IS-ACTIVE TO TRUE DISPLAY WS-CODE",
                "    SET IS-ACTIVE TO FALSE DISPLAY WS-CODE",
                "    STOP RUN."));
    }

    @Test
    @DisplayName("WHEN SET TO FALSE を持たない条件名を FALSE にはできない")
    void setToFalseRequiresAFalseValue() {
        CobolCompiler.Result result = compile(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. PGM.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-CODE PIC 9.",
                "   88 IS-ACTIVE VALUE 1.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    SET IS-ACTIVE TO FALSE.");
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("WHEN SET TO FALSE"));
    }

    private static final List<String> INITIALIZED = List.of(
            "01 REC.",
            "   05 TXT PIC XX VALUE 'AB'.",
            "   05 UNSET-TXT PIC XX.",
            "   05 NUM PIC 99 VALUE 37.",
            "   05 UNSET-NUM PIC 99.",
            "   05 FILLER PIC X VALUE 'F'.",
            "01 RAW REDEFINES REC PIC X(9).",
            "01 JUST-TXT PIC X(4) JUSTIFIED RIGHT VALUE 'Q'.");

    @Test
    @DisplayName("INITIALIZE ... TO VALUE は VALUE 句を持つ項目だけを戻し、残りは変えない")
    void initializeToValue() {
        assertEquals(List.of("ABZZ3799Z", "AB  3700Z", "ABZZ9999Z", "ABXY3742F"),
                run(INITIALIZED,
                        "MAIN-START.",
                        "    MOVE 'ZZZZ9999Z' TO RAW",
                        "    INITIALIZE REC ALL TO VALUE DISPLAY RAW",
                        "    MOVE 'ZZZZ9999Z' TO RAW",
                        "    INITIALIZE REC ALL TO VALUE THEN TO DEFAULT",
                "    DISPLAY RAW",
                        "    MOVE 'ZZZZ9999Z' TO RAW",
                        "    INITIALIZE REC ALPHANUMERIC TO VALUE DISPLAY RAW",
                        "    MOVE 'ZZZZ9999Z' TO RAW",
                        "    INITIALIZE REC WITH FILLER ALL TO VALUE",
                        "        THEN REPLACING ALPHANUMERIC BY 'XY' NUMERIC BY 42",
                        "        THEN TO DEFAULT",
                        "    DISPLAY RAW",
                        "    STOP RUN."));
    }

    @Test
    @DisplayName("TO VALUE は初期値のバイトを戻す。JUSTIFIED は VALUE 句の初期値に効かない")
    void initializeToValueDoesNotJustify() {
        // 転記し直すと「   Q」になる
        assertEquals(List.of("Q   ", "   Z"), run(INITIALIZED,
                "MAIN-START.",
                "    MOVE 'Z' TO JUST-TXT",
                "    INITIALIZE JUST-TXT ALL TO VALUE DISPLAY JUST-TXT",
                "    INITIALIZE JUST-TXT REPLACING ALPHANUMERIC BY 'Z'",
                "    DISPLAY JUST-TXT",
                "    STOP RUN."));
    }

    // ---- 組み込み関数と特殊レジスタ ----

    @Test
    @DisplayName("TRIM は前後の空白を削り、LEADING / TRAILING で片側だけにできる")
    void trimRemovesSpaces() {
        assertEquals(List.of("[AB    ]", "[AB    ]", "[  AB  ]", "002", "000", "004"), run(
                List.of("01 WS-OUT PIC X(6)."),
                "MAIN-START.",
                "    MOVE FUNCTION TRIM('  AB  ') TO WS-OUT",
                "    DISPLAY '[' WS-OUT ']'",
                "    MOVE FUNCTION TRIM('  AB  ' LEADING) TO WS-OUT",
                "    DISPLAY '[' WS-OUT ']'",
                "    MOVE FUNCTION TRIM('  AB  ' TRAILING) TO WS-OUT",
                "    DISPLAY '[' WS-OUT ']'",
                "    MOVE FUNCTION LENGTH(FUNCTION TRIM('  AB  ')) TO WS-I",
                "    DISPLAY WS-I",
                "    MOVE FUNCTION LENGTH(FUNCTION TRIM('    ')) TO WS-I",
                "    DISPLAY WS-I",
                "    MOVE FUNCTION LENGTH(",
                "        FUNCTION TRIM('  AB  ' TRAILING)) TO WS-I",
                "    DISPLAY WS-I",
                "    STOP RUN."));
    }

    @Test
    @DisplayName("ABS と TEST-NUMVAL。誤っている文字の位置、足りなければ長さ + 1")
    void absAndTestNumval() {
        assertEquals(List.of("01250", "000", "003", "005", "004", "000"), run(
                List.of("01 WS-NUM PIC 9(3)V99."),
                "MAIN-START.",
                "    COMPUTE WS-NUM = FUNCTION ABS(-12.5) DISPLAY WS-NUM",
                "    COMPUTE WS-I = FUNCTION TEST-NUMVAL(' -12.50 ')",
                "    DISPLAY WS-I",
                "    COMPUTE WS-I = FUNCTION TEST-NUMVAL('12X.50')",
                "    DISPLAY WS-I",
                "    COMPUTE WS-I = FUNCTION TEST-NUMVAL('    ')",
                "    DISPLAY WS-I",
                "    COMPUTE WS-I = FUNCTION TEST-NUMVAL('1.2.3')",
                "    DISPLAY WS-I",
                "    COMPUTE WS-I = FUNCTION TEST-NUMVAL-C('$1,234.56CR')",
                "    DISPLAY WS-I",
                "    STOP RUN."));
    }

    @Test
    @DisplayName("DECIMAL-POINT IS COMMA の下では、NUMVAL の引数の小数点もコンマである")
    void numvalHonoursDecimalComma() {
        // ピリオドを小数点として読むと 12345 と 123456 になり、5 桁へ切られる
        assertEquals(List.of("12345", "23456", "000"), runSource(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. PGM.",
                "ENVIRONMENT DIVISION.",
                "CONFIGURATION SECTION.",
                "SPECIAL-NAMES. DECIMAL-POINT IS COMMA.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-NUM PIC 9(3)V99.",
                "01 WS-I PIC 9(3).",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    COMPUTE WS-NUM = FUNCTION NUMVAL('123,45')",
                "    DISPLAY WS-NUM",
                "    COMPUTE WS-NUM = FUNCTION NUMVAL-C('1.234,56')",
                "    DISPLAY WS-NUM",
                "    COMPUTE WS-I = FUNCTION TEST-NUMVAL('123,45')",
                "    DISPLAY WS-I",
                "    STOP RUN."));
    }

    @Test
    @DisplayName("TALLY は使える特殊レジスタであり、LENGTH OF は添字の付いた要素にも書ける")
    void tallyAndLengthOfAnElement() {
        assertEquals(List.of("012", "006"), run(
                List.of("01 WS-TAB.",
                        "   05 WS-ROW OCCURS 3 TIMES.",
                        "      10 WS-A PIC X(4).",
                        "      10 WS-B PIC 99."),
                "MAIN-START.",
                "    MOVE 12 TO TALLY",
                "    MOVE TALLY TO WS-I DISPLAY WS-I",
                "    MOVE LENGTH OF WS-ROW(2) TO WS-I DISPLAY WS-I",
                "    STOP RUN."));
    }

    @Test
    @DisplayName("TITLE / EJECT / SKIPn はリストの体裁を決めるだけで、どこに書いても読み飛ばす")
    void listingDirectivesAreSkipped() {
        assertEquals(List.of("OK"), runSource(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. PGM.",
                "    TITLE 'LISTING TITLE'.",
                "    EJECT",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "    SKIP1",
                "01 WS-A PIC X.",
                "    SKIP2.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    SKIP3",
                "    DISPLAY 'OK'",
                "    STOP RUN."));
    }
}
