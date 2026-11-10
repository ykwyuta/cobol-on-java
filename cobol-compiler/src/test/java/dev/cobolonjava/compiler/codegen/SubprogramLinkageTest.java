package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 副プログラムとの連絡のうち、z/OS の決めごとに当たるところ。
 *
 * <p>どれも ZCVS (Zeus の検査スイート) の ZP の区分を流して見つかった。CCVS85 は
 * {@code RETURNING} も {@code LOCAL-STORAGE} も副入口も測らないので、全部通っていても
 * 出てこなかった。ZCVS は同梱できないので、<b>振る舞いの差が出る形</b>で自前の検査にして
 * おく。期待値の根拠は Enterprise COBOL の言語解説書であり、実機とは突き合わせていない。
 */
@Tag("V1")
class SubprogramLinkageTest {

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(SubprogramLinkageTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    /** 複数のプログラムを同じローダへ読み込み、最初のものを動かして DISPLAY の出力を返す。 */
    private static List<String> run(List<List<String>> programs) {
        GeneratedLoader loader = new GeneratedLoader();
        List<CobolProgram> loaded = new ArrayList<>();
        for (List<String> lines : programs) {
            StringBuilder source = new StringBuilder();
            for (String line : lines) {
                source.append("       ").append(line).append('\n');
            }
            CobolCompiler.Result result =
                    CobolCompiler.standard().compile("PGM.cbl", source.toString());
            assertTrue(result.succeeded(),
                    () -> "unexpected diagnostics: " + result.diagnostics());
            for (CobolCompiler.Compiled program : result.programs()) {
                try {
                    Class<?> type = loader.define(program.className(), program.classFile());
                    loaded.add((CobolProgram) type.getDeclaredConstructor().newInstance());
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("cannot load the generated program", e);
                }
            }
        }
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        loaded.get(0).runFresh(ProgramContext.capturing(sink));
        return sink.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    @DisplayName("CALL ... RETURNING は受取項目に値を入れ、RETURN-CODE を変えない")
    void returningSetsTheReceiverAndKeepsReturnCode() {
        List<String> output = run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-A PIC S9(9) COMP-5 VALUE 12.",
                        "01 WS-ANS PIC S9(9) COMP-5 VALUE 0.",
                        "01 WS-SHOW PIC 9(5).",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    MOVE 7 TO RETURN-CODE",
                        "    CALL 'SUBRET' USING WS-A RETURNING WS-ANS",
                        "    MOVE WS-ANS TO WS-SHOW DISPLAY WS-SHOW",
                        "    MOVE RETURN-CODE TO WS-SHOW DISPLAY WS-SHOW",
                        // RETURNING を書かない CALL は、呼ばれた側の RETURN-CODE が見える
                        "    CALL 'SUBRC'",
                        "    MOVE RETURN-CODE TO WS-SHOW DISPLAY WS-SHOW",
                        "    STOP RUN."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBRET.",
                        "DATA DIVISION.",
                        "LINKAGE SECTION.",
                        "01 LK-A PIC S9(9) COMP-5.",
                        "01 LK-RESULT PIC S9(9) COMP-5.",
                        "PROCEDURE DIVISION USING LK-A RETURNING LK-RESULT.",
                        "    COMPUTE LK-RESULT = LK-A * 100 + 34",
                        "    MOVE 99 TO RETURN-CODE",
                        "    GOBACK."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBRC.",
                        "PROCEDURE DIVISION.",
                        "    MOVE 55 TO RETURN-CODE",
                        "    GOBACK.")));

        assertEquals(List.of("01234", "00007", "00055"), output);
    }

    @Test
    @DisplayName("BY VALUE の整数の定数は fullword で渡り、OMITTED の項目は番地が NULL になる")
    void byValueLiteralAndOmitted() {
        List<String> output = run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-VAL PIC S9(9) COMP-5 VALUE 5.",
                        "01 WS-OPT PIC X(4) VALUE 'DATA'.",
                        "01 WS-STAT PIC X.",
                        "01 WS-SUM PIC 9(5).",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'SUBVAL' USING BY VALUE WS-VAL BY REFERENCE WS-OPT",
                        "        WS-STAT WS-SUM",
                        "    DISPLAY WS-STAT WS-SUM",
                        "    MOVE WS-VAL TO WS-SUM DISPLAY WS-SUM",
                        "    CALL 'SUBVAL' USING BY VALUE 300 OMITTED",
                        "        BY REFERENCE WS-STAT WS-SUM",
                        "    DISPLAY WS-STAT WS-SUM",
                        "    STOP RUN."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBVAL.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-PTR USAGE POINTER.",
                        "LINKAGE SECTION.",
                        "01 LK-VAL PIC S9(9) COMP-5.",
                        "01 LK-OPT PIC X(4).",
                        "01 LK-STAT PIC X.",
                        "01 LK-SUM PIC 9(5).",
                        "PROCEDURE DIVISION USING BY VALUE LK-VAL",
                        "        BY REFERENCE LK-OPT LK-STAT LK-SUM.",
                        "    COMPUTE LK-SUM = LK-VAL + 1",
                        // 受けた値を書き換えても、呼ぶ側には届かない
                        "    MOVE -1 TO LK-VAL",
                        "    SET WS-PTR TO ADDRESS OF LK-OPT",
                        "    IF WS-PTR = NULL MOVE 'O' TO LK-STAT",
                        "    ELSE MOVE 'P' TO LK-STAT.",
                        "    GOBACK.")));

        assertEquals(List.of("P00006", "00005", "O00301"), output);
    }

    /** 作業場所の通し番号と、局所記憶域の値をつなげて返す副プログラム。 */
    private static List<String> counting(String programId) {
        return List.of("IDENTIFICATION DIVISION.",
                programId,
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-COUNT PIC 99 VALUE 1.",
                "LOCAL-STORAGE SECTION.",
                "01 LS-NUM PIC 99 VALUE 12.",
                "LINKAGE SECTION.",
                "01 LK-OUT PIC X(4).",
                "PROCEDURE DIVISION USING LK-OUT.",
                "    STRING WS-COUNT LS-NUM DELIMITED BY SIZE INTO LK-OUT",
                "    ADD 1 TO WS-COUNT",
                "    MOVE 99 TO LS-NUM",
                "    GOBACK.");
    }

    private static List<String> callingTwice(String name) {
        return List.of("IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MAIN.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-OUT PIC X(4).",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    CALL '" + name + "' USING WS-OUT DISPLAY WS-OUT",
                "    CALL '" + name + "' USING WS-OUT DISPLAY WS-OUT",
                "    STOP RUN.");
    }

    @Test
    @DisplayName("LOCAL-STORAGE は呼び出しのたびに VALUE へ戻り、WORKING-STORAGE は残る")
    void localStorageIsFreshOnEveryCall() {
        // WORKING-STORAGE と同じ扱いにしていると、2 度目は「0299」になる
        assertEquals(List.of("0112", "0212"),
                run(List.of(callingTwice("SUBLS"), counting("PROGRAM-ID. SUBLS."))));
    }

    @Test
    @DisplayName("INITIAL のプログラムは、呼ばれるたびに WORKING-STORAGE も初期状態から始まる")
    void initialProgramsStartFreshOnEveryCall() {
        assertEquals(List.of("0112", "0112"),
                run(List.of(callingTwice("SUBINI"), counting("PROGRAM-ID. SUBINI IS INITIAL."))));
    }

    @Test
    @DisplayName("再帰した呼び出しは、それぞれ自分の LOCAL-STORAGE を持つ")
    void recursionKeepsOneLocalStoragePerInvocation() {
        List<String> output = run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-N PIC 9(4) COMP VALUE 5.",
                        "01 WS-F PIC 9(9) COMP VALUE 0.",
                        "01 WS-SHOW PIC 9(5).",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'FACT' USING WS-N WS-F",
                        "    MOVE WS-F TO WS-SHOW DISPLAY WS-SHOW",
                        "    STOP RUN."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. FACT IS RECURSIVE.",
                        "DATA DIVISION.",
                        "LOCAL-STORAGE SECTION.",
                        "01 LS-N PIC 9(4) COMP.",
                        "01 LS-SUB PIC 9(9) COMP VALUE 0.",
                        "LINKAGE SECTION.",
                        "01 LK-N PIC 9(4) COMP.",
                        "01 LK-F PIC 9(9) COMP.",
                        "PROCEDURE DIVISION USING LK-N LK-F.",
                        "    IF LK-N < 2 MOVE 1 TO LK-F GOBACK.",
                        // 自分の LS-N を内側の呼び出しへ渡す。内側が自分の LOCAL-STORAGE を
                        // 作り直しても、外側の LS-N と LS-SUB は壊れてはならない
                        "    COMPUTE LS-N = LK-N - 1",
                        "    CALL 'FACT' USING LS-N LS-SUB",
                        "    COMPUTE LK-F = LK-N * LS-SUB",
                        "    GOBACK.")));

        assertEquals(List.of("00120"), output);
    }

    @Test
    @DisplayName("副入口は自分の USING を持ち、本体と同じ WORKING-STORAGE を見る")
    void alternateEntriesShareWorkingStorage() {
        List<String> output = run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-A PIC 9(3) VALUE 5.",
                        "01 WS-R PIC 9(3) VALUE 0.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'SUBENT' USING WS-A WS-R DISPLAY WS-R",
                        // 副入口の USING は並びが違う。受取項目が 1 つ目である
                        "    CALL 'SUBALT' USING WS-R DISPLAY WS-R",
                        "    CALL 'SUBENT' USING WS-A WS-R DISPLAY WS-R",
                        // 本体を取り消せば、副入口から入っても作業場所は初期状態である
                        "    CANCEL 'SUBENT'",
                        "    CALL 'SUBALT' USING WS-R DISPLAY WS-R",
                        "    STOP RUN."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBENT.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-CALLS PIC 9(3) VALUE 0.",
                        "LINKAGE SECTION.",
                        "01 LK-A PIC 9(3).",
                        "01 LK-R PIC 9(3).",
                        "PROCEDURE DIVISION USING LK-A LK-R.",
                        "    ADD 1 TO WS-CALLS",
                        "    COMPUTE LK-R = LK-A * 10 + WS-CALLS",
                        "    GOBACK.",
                        "    ENTRY 'SUBALT' USING LK-R.",
                        "    ADD 1 TO WS-CALLS",
                        "    COMPUTE LK-R = 900 + WS-CALLS",
                        "    GOBACK.")));

        assertEquals(List.of("051", "902", "053", "901"), output);
    }

    @Test
    @DisplayName("ENTRY を含む段落を PERFORM すると、ENTRY のうしろまで動いて戻る")
    void performRunsThroughAnEntryStatement() {
        // 段落を ENTRY で切って入口にしている。切った後ろ半分を範囲に含めないと、
        // PERFORM が ENTRY の手前で戻ってしまう
        List<String> output = run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    PERFORM STEP-A",
                        "    DISPLAY 'DONE'",
                        "    STOP RUN.",
                        "STEP-A.",
                        "    DISPLAY 'BEFORE'.",
                        "    ENTRY 'MAINALT'.",
                        "    DISPLAY 'AFTER'.",
                        "STEP-B.",
                        "    DISPLAY 'NOT REACHED'.")));

        assertEquals(List.of("BEFORE", "AFTER", "DONE"), output);
    }

    @Test
    @DisplayName("手続きポインタに入口を置いて呼べる。大きさは 8 byte、関数ポインタは 4 byte")
    void procedurePointersCallTheirEntry() {
        List<String> output = run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-PP USAGE PROCEDURE-POINTER VALUE NULL.",
                        "01 WS-FP USAGE FUNCTION-POINTER VALUE NULL.",
                        "01 WS-NAME PIC X(8) VALUE 'SUBPP'.",
                        "01 WS-RET PIC 9(4) COMP VALUE 0.",
                        "01 WS-SHOW PIC 9(3).",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    MOVE LENGTH OF WS-PP TO WS-SHOW DISPLAY WS-SHOW",
                        "    MOVE LENGTH OF WS-FP TO WS-SHOW DISPLAY WS-SHOW",
                        "    IF WS-PP = NULL DISPLAY 'NULL'.",
                        "    SET WS-PP TO ENTRY WS-NAME",
                        "    IF WS-PP NOT = NULL DISPLAY 'SET'.",
                        "    CALL WS-PP RETURNING WS-RET",
                        "    MOVE WS-RET TO WS-SHOW DISPLAY WS-SHOW",
                        "    SET WS-FP TO WS-PP",
                        "    CALL WS-FP RETURNING WS-RET",
                        "    MOVE WS-RET TO WS-SHOW DISPLAY WS-SHOW",
                        "    STOP RUN."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBPP.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-SEQ PIC 9(4) COMP VALUE 0.",
                        "LINKAGE SECTION.",
                        "01 LK-RET PIC 9(4) COMP.",
                        "PROCEDURE DIVISION RETURNING LK-RET.",
                        "    ADD 10 TO WS-SEQ",
                        "    MOVE WS-SEQ TO LK-RET",
                        "    GOBACK.")));

        assertEquals(List.of("008", "004", "NULL", "SET", "010", "020"), output);
    }
}
