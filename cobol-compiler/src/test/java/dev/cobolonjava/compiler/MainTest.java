package dev.cobolonjava.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.interop.CobolRuntime;
import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.DeployCatalogManifest;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.program.RangeCheckException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 起動口からソースを翻訳し、書き出したクラスファイルをそのまま動かす。 */
@Tag("V1")
class MainTest {

    private static final String SOURCE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. SUMMER.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01 WS-I     PIC 9(3) COMP VALUE 1.",
            "       01 WS-TOTAL PIC 9(5) COMP-3 VALUE 0.",
            "       PROCEDURE DIVISION.",
            "       MAIN-START.",
            "           PERFORM UNTIL WS-I > 5",
            "               ADD WS-I TO WS-TOTAL",
            "               ADD 1 TO WS-I",
            "           END-PERFORM",
            "           DISPLAY 'SUM=' WS-TOTAL.",
            "");

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(MainTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    @Test
    @DisplayName("ソースからクラスファイルを書き出し、そのまま動かせる (FR-180)")
    void aSourceFileBecomesARunnableClass(@TempDir Path work) throws IOException {
        Path source = work.resolve("SUMMER.cbl");
        Files.writeString(source, SOURCE, StandardCharsets.UTF_8);
        Path output = work.resolve("out");

        Main.main(new String[] {"-d", output.toString(), source.toString()});

        Path classFile = output.resolve("cobol/generated/SUMMER.class");
        assertTrue(Files.isRegularFile(classFile), "クラスファイルが書き出される");

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            Class<?> type = new GeneratedLoader()
                    .define("cobol.generated.SUMMER", Files.readAllBytes(classFile));
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            program.runFresh(ProgramContext.capturing(sink));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
        assertEquals("SUM=00015" + System.lineSeparator(), sink.toString(StandardCharsets.UTF_8));

        Path manifestFile = output.resolve(DeployCatalogManifest.RESOURCE_NAME);
        assertTrue(Files.isRegularFile(manifestFile), "配備カタログが書き出される");
        ByteArrayOutputStream catalogSink = new ByteArrayOutputStream();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {
                output.toUri().toURL()}, getClass().getClassLoader())) {
            DeployCatalogManifest manifest = DeployCatalogManifest.fromResource(loader);
            assertEquals("SUMMER", manifest.programs().get(0).programId().value());
            assertNotNull(manifest.programs().get(0).signature());
            try (CobolSession session = CobolRuntime.builder(manifest.toProgramCatalog())
                    .classLoader(loader).build().openSession(catalogSink)) {
                session.runMain("SUMMER");
            }
        }
        assertEquals("SUM=00015" + System.lineSeparator(),
                catalogSink.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Java の GO TO は選択値と PERFORM 範囲を従来のクラス出力と同じように扱う")
    void directJavaPreservesParagraphTransfers(@TempDir Path work) throws Exception {
        Path source = work.resolve("JUMPS.cbl");
        Files.writeString(source, """
                IDENTIFICATION DIVISION.
                PROGRAM-ID. JUMPS.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 CHOICE PIC 9 VALUE 0.
                01 SMALL-NUM PIC 9 VALUE 9.
                PROCEDURE DIVISION.
                START-HERE.
                    GO TO WRONG-PATH CHOSEN-PATH DEPENDING ON CHOICE.
                    DISPLAY 'FALLTHROUGH'.
                    MOVE 2 TO CHOICE.
                    GO TO WRONG-PATH CHOSEN-PATH DEPENDING ON CHOICE.
                WRONG-PATH.
                    DISPLAY 'UNEXPECTED'.
                    STOP RUN.
                CHOSEN-PATH.
                    PERFORM JUMP-START THRU JUMP-END.
                    DISPLAY 'RETURNED'.
                    PERFORM WITH TEST AFTER UNTIL CHOICE = 0
                        IF CHOICE = 2
                            GO TO SIZE-CHECK
                        ELSE
                            GO TO WRONG-PATH
                        END-IF
                    END-PERFORM.
                    DISPLAY 'UNREACHABLE LOOP'.
                SIZE-CHECK.
                    ADD 1 TO SMALL-NUM
                        ON SIZE ERROR GO TO LEAVE-CALLER
                        NOT ON SIZE ERROR GO TO WRONG-PATH
                    END-ADD.
                    DISPLAY 'UNREACHABLE SIZE CHECK'.
                LEAVE-CALLER.
                    PERFORM LEAVE-RANGE.
                    DISPLAY 'UNEXPECTED RETURN'.
                    GOBACK.
                JUMP-START.
                    GO TO OUTSIDE-RANGE.
                JUMP-END.
                    DISPLAY 'RANGE END'.
                OUTSIDE-RANGE.
                    DISPLAY 'OUTSIDE'.
                    GO TO JUMP-END.
                LEAVE-RANGE.
                    GO TO FINISH-HERE.
                FINISH-HERE.
                    DISPLAY 'DONE'.
                """);
        Path binary = work.resolve("binary");
        Path generated = work.resolve("generated");
        Path recompiled = work.resolve("recompiled");
        Main.main(new String[] {"--free", "-d", binary.toString(), source.toString()});
        Main.main(new String[] {"--free", "--emit-java", "-d", generated.toString(), source.toString()});
        Path java = generated.resolve("cobol/generated/JUMPS.java");
        String text = Files.readString(java);
        assertTrue(text.contains("int next = switch (paragraph)"));
        assertTrue(text.contains("case 2 -> { return PARAGRAPH_CHOSEN_PATH; }"));
        assertTrue(text.contains("return PARAGRAPH_OUTSIDE_RANGE;"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-classpath", System.getProperty("java.class.path"),
                "-encoding", "UTF-8", "-d", recompiled.toString(), java.toString()));

        assertEquals(List.of("FALLTHROUGH", "OUTSIDE", "RANGE END", "RETURNED", "DONE"),
                runProgram(binary, "JUMPS"));
        assertEquals(runProgram(binary, "JUMPS"), runProgram(recompiled, "JUMPS"));
    }

    @Test
    @DisplayName("Java の NEXT SENTENCE と EXIT は対応する範囲だけを抜ける")
    void directJavaPreservesStructuredExits(@TempDir Path work) throws Exception {
        Path source = work.resolve("EXITS.cbl");
        Files.writeString(source, """
                IDENTIFICATION DIVISION.
                PROGRAM-ID. EXITS.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 LOOP-I PIC 9.
                01 TOTAL PIC 99 VALUE 0.
                PROCEDURE DIVISION.
                MAIN-SEC SECTION.
                START-HERE.
                    PERFORM 2 TIMES
                        PERFORM UNTIL EXIT
                            ADD 1 TO TOTAL
                            EXIT PERFORM
                            DISPLAY 'UNEXPECTED INNER'
                        END-PERFORM
                        DISPLAY 'OUTER'
                    END-PERFORM.
                    PERFORM WITH TEST AFTER
                        VARYING LOOP-I FROM 1 BY 1 UNTIL LOOP-I >= 3
                        IF LOOP-I < 3 EXIT PERFORM CYCLE END-IF
                        DISPLAY 'VARYING' LOOP-I
                    END-PERFORM.
                    PERFORM VARYING LOOP-I FROM 1 BY 1 UNTIL LOOP-I > 2
                        DISPLAY 'BEFORE' LOOP-I
                        EXIT PERFORM CYCLE
                        DISPLAY 'UNEXPECTED CYCLE'
                    END-PERFORM.
                    PERFORM WITH TEST AFTER UNTIL TOTAL = 0
                        EXIT PERFORM
                    END-PERFORM.
                    PERFORM 2 TIMES
                        IF TOTAL = 2 NEXT SENTENCE END-IF
                        DISPLAY 'UNEXPECTED SENTENCE'
                    END-PERFORM
                    DISPLAY 'UNEXPECTED PERIOD'.
                    DISPLAY 'TOTAL' TOTAL.
                    PERFORM PARA-EXIT.
                    PERFORM SEC-FIRST THRU SEC-LAST.
                    DISPLAY 'BACK'.
                    GOBACK.
                PARA-EXIT.
                    DISPLAY 'PARAGRAPH'
                    EXIT PARAGRAPH.
                    DISPLAY 'UNEXPECTED PARAGRAPH'.
                SUB-SEC SECTION.
                SEC-FIRST.
                    DISPLAY 'SECTION'
                    EXIT SECTION.
                SEC-LAST.
                    DISPLAY 'UNEXPECTED SECTION'.
                """);
        Path binary = work.resolve("binary");
        Path generated = work.resolve("generated");
        Path recompiled = work.resolve("recompiled");
        Main.main(new String[] {"--free", "-d", binary.toString(), source.toString()});
        Main.main(new String[] {"--free", "--emit-java", "-d", generated.toString(), source.toString()});
        Path java = generated.resolve("cobol/generated/EXITS.java");
        String text = Files.readString(java);
        assertTrue(text.contains("break sentence"));
        assertTrue(text.contains("break performEnd"));
        assertTrue(text.contains("break performCycle"));
        assertTrue(text.contains("return -(PARAGRAPH_SEC_LAST + 2);"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-classpath", System.getProperty("java.class.path"),
                "-encoding", "UTF-8", "-d", recompiled.toString(), java.toString()));
        List<String> expected = runProgram(binary, "EXITS");
        assertEquals(List.of("OUTER", "OUTER", "VARYING3", "BEFORE1", "BEFORE2",
                "TOTAL02", "PARAGRAPH", "SECTION", "BACK"), expected);
        assertEquals(expected, runProgram(recompiled, "EXITS"));
    }

    @Test
    @DisplayName("Java の EVALUATE は級条件と複数主語を使い、最初に一致した WHEN だけを実行する")
    void directJavaPreservesEvaluateOrder(@TempDir Path work) throws Exception {
        Path source = work.resolve("CHOICES.cbl");
        Files.writeString(source, """
                IDENTIFICATION DIVISION.
                PROGRAM-ID. CHOICES.
                ENVIRONMENT DIVISION.
                CONFIGURATION SECTION.
                SPECIAL-NAMES.
                    CLASS HEXDIGIT IS '0' THRU '9' 'A' THRU 'F'.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 PICK PIC 99 VALUE 5.
                01 TOKEN-TEXT PIC XX VALUE 'AB'.
                01 SIGNED-NUM PIC S9 SIGN LEADING SEPARATE VALUE -1.
                PROCEDURE DIVISION.
                    EVALUATE PICK ALSO TOKEN-TEXT
                        WHEN 1 THRU 9 ALSO 'ZZ' DISPLAY 'UNEXPECTED'
                        WHEN 4 THRU 6 ALSO ANY DISPLAY 'FIRST MATCH'
                        WHEN 5 ALSO 'AB' DISPLAY 'UNEXPECTED'
                        WHEN OTHER DISPLAY 'UNEXPECTED'
                    END-EVALUATE.
                    EVALUATE TOKEN-TEXT IS NUMERIC
                        WHEN TRUE DISPLAY 'UNEXPECTED'
                        WHEN FALSE DISPLAY 'TEXT'
                    END-EVALUATE.
                    EVALUATE TRUE
                        WHEN SIGNED-NUM IS NUMERIC
                         AND TOKEN-TEXT IS ALPHABETIC-UPPER NEXT SENTENCE
                        WHEN OTHER DISPLAY 'UNEXPECTED'
                    END-EVALUATE
                    DISPLAY 'UNEXPECTED'.
                    MOVE '12' TO TOKEN-TEXT.
                    EVALUATE FALSE
                        WHEN TOKEN-TEXT IS NUMERIC DISPLAY 'UNEXPECTED'
                        WHEN TOKEN-TEXT IS ALPHABETIC DISPLAY 'DIGITS'
                    END-EVALUATE.
                    EVALUATE TOKEN-TEXT IS HEXDIGIT
                        WHEN TRUE DISPLAY 'HEX'
                        WHEN FALSE DISPLAY 'UNEXPECTED'
                    END-EVALUATE.
                    EVALUATE PICK
                        WHEN 8 DISPLAY 'UNEXPECTED'
                        WHEN OTHER DISPLAY 'OTHER'
                    END-EVALUATE.
                    STOP RUN.
                """);
        Path binary = work.resolve("binary");
        Path generated = work.resolve("generated");
        Path recompiled = work.resolve("recompiled");
        Main.main(new String[] {"--free", "-q", "NUMPROC(PFD)", "-d", binary.toString(), source.toString()});
        Main.main(new String[] {"--free", "--emit-java", "-q", "NUMPROC(PFD)", "-d",
                generated.toString(), source.toString()});
        Path java = generated.resolve("cobol/generated/CHOICES.java");
        String text = Files.readString(java);
        assertTrue(text.contains("} else if ("));
        assertTrue(text.contains("ClassTest.digits("));
        assertTrue(text.contains("ClassTest.numeric("));
        assertTrue(text.contains("ClassTest.alphabetic("));
        assertTrue(text.contains("ClassTest.member("));
        assertTrue(text.contains("ClassTest.SIGNS_PREFERRED"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-classpath", System.getProperty("java.class.path"),
                "-encoding", "UTF-8", "-d", recompiled.toString(), java.toString()));
        List<String> expected = runProgram(binary, "CHOICES");
        assertEquals(List.of("FIRST MATCH", "TEXT", "DIGITS", "HEX", "OTHER"), expected);
        assertEquals(expected, runProgram(recompiled, "CHOICES"));
    }

    @Test
    @DisplayName("Java の添字と部分参照は固定長の表と SSRANGE を扱う")
    void directJavaPreservesTableAndSliceAccess(@TempDir Path work) throws Exception {
        String declarations = """
                IDENTIFICATION DIVISION.
                PROGRAM-ID. TABLES.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 TABLE-DATA.
                   05 ROW-DATA OCCURS 2 TIMES INDEXED BY ROW-IX.
                      10 CELL-DATA OCCURS 3 TIMES INDEXED BY CELL-IX.
                         15 CELL-TEXT PIC X(4).
                         15 CELL-NUM PIC 99 COMP-3.
                01 ROW-ID PIC 9 VALUE 1.
                01 CELL-ID PIC 9 VALUE 2.
                01 START-ID PIC 9 VALUE 2.
                01 SLICE-SIZE PIC 9 VALUE 2.
                01 SAVED-IX USAGE INDEX.
                PROCEDURE DIVISION.
                """;
        Path source = work.resolve("TABLES.cbl");
        Path binary = work.resolve("binary");
        Path generated = work.resolve("generated");
        Path recompiled = work.resolve("recompiled");
        for (String body : List.of("""
                    MOVE 'ABCD' TO CELL-TEXT (ROW-ID + 1, 2).
                    MOVE 'XY' TO CELL-TEXT (2, CELL-ID) (START-ID:SLICE-SIZE).
                    MOVE 5 TO CELL-NUM (2, CELL-ID).
                    ADD 3 TO CELL-NUM (ROW-ID + 1, CELL-ID).
                    DISPLAY CELL-TEXT (2, 2).
                    DISPLAY CELL-TEXT (2, CELL-ID) (START-ID + 1:).
                    DISPLAY CELL-TEXT (2, 2) (START-ID:2).
                    DISPLAY CELL-NUM (2, CELL-ID).
                    SET ROW-IX TO 1.
                    SET ROW-IX UP BY 1.
                    SET CELL-IX TO 3.
                    SET CELL-IX DOWN BY 1.
                    SET SAVED-IX TO CELL-IX.
                    DISPLAY CELL-TEXT (ROW-IX, SAVED-IX).
                    MOVE ZERO TO CELL-NUM (2, 1) CELL-NUM (2, 3).
                    SET CELL-IX TO 1.
                    SEARCH CELL-DATA VARYING CELL-ID
                        AT END DISPLAY 'UNEXPECTED'
                        WHEN CELL-TEXT (ROW-IX, CELL-IX) = 'AXYD'
                            DISPLAY 'FOUND ' CELL-ID
                        WHEN CELL-NUM (ROW-IX, CELL-IX) = 8
                            DISPLAY 'UNEXPECTED'
                    END-SEARCH.
                    SET CELL-IX TO 3.
                    SEARCH CELL-DATA
                        AT END DISPLAY 'EXHAUSTED'
                        WHEN CELL-TEXT (ROW-IX, CELL-IX) = 'AXYD'
                            DISPLAY 'UNEXPECTED'
                    END-SEARCH.
                    SET CELL-ID TO CELL-IX.
                    DISPLAY 'INDEX ' CELL-ID.
                    SEARCH CELL-DATA
                        AT END NEXT SENTENCE
                        WHEN CELL-TEXT (ROW-IX, CELL-IX) = 'AXYD'
                            DISPLAY 'UNEXPECTED'
                    END-SEARCH
                    DISPLAY 'UNEXPECTED'.
                    DISPLAY 'AFTER SENTENCE'.
                """, """
                    SET ROW-IX TO 2.
                    SET ROW-IX UP BY 1.
                    MOVE 'ABCD' TO CELL-TEXT (ROW-IX, CELL-ID).
                """, """
                    MOVE 4 TO START-ID.
                    DISPLAY CELL-TEXT (2, CELL-ID) (START-ID:2).
                """)) {
            Files.writeString(source, declarations + body + "    STOP RUN.\n");
            Main.main(new String[] {"--free", "-q", "SSRANGE", "-d", binary.toString(),
                    source.toString()});
            Main.main(new String[] {"--free", "--emit-java", "-q", "SSRANGE", "-d",
                    generated.toString(), source.toString()});
            Path java = generated.resolve("cobol/generated/TABLES.java");
            String text = Files.readString(java);
            assertTrue(text.contains("int next = switch (paragraph)"));
            assertTrue(text.contains("Ops.checkSubscript("));
            assertTrue(text.contains("LENGTH_TABLE_DATA$ROW_DATA"));
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "--release", "21", "-classpath", System.getProperty("java.class.path"),
                    "-encoding", "UTF-8", "-d", recompiled.toString(), java.toString()));
            if (body.contains("ADD 3")) {
                assertTrue(text.contains("NUMERIC_IDX_CELL_IX"));
                assertTrue(text.contains("break search"));
                List<String> expected = List.of("AXYD", "YD", "XY", "08", "AXYD",
                        "FOUND 3", "EXHAUSTED", "INDEX 4", "AFTER SENTENCE");
                assertEquals(expected, runProgram(binary, "TABLES"));
                assertEquals(expected, runProgram(recompiled, "TABLES"));
            } else {
                RangeCheckException expected = assertThrows(RangeCheckException.class,
                        () -> runProgram(binary, "TABLES"));
                RangeCheckException actual = assertThrows(RangeCheckException.class,
                        () -> runProgram(recompiled, "TABLES"));
                assertEquals(expected.getMessage(), actual.getMessage());
            }
        }
    }

    @Test
    @DisplayName("Java の SEARCH ALL は昇順と降順の複合キーを使い、探索後の指標を保つ")
    void directJavaPreservesBinarySearch(@TempDir Path work) throws Exception {
        Path source = work.resolve("FINDKEY.cbl");
        Files.writeString(source, """
                IDENTIFICATION DIVISION.
                PROGRAM-ID. FINDKEY.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 TABLE-DATA.
                   05 ROW-DATA OCCURS 4 TIMES
                      ASCENDING KEY IS KEY-NUM
                      DESCENDING KEY IS KEY-TEXT INDEXED BY ROW-IX.
                      10 KEY-NUM PIC 9.
                      10 KEY-TEXT PIC X.
                01 LAST-INDEX PIC 9.
                PROCEDURE DIVISION.
                    MOVE 1 TO KEY-NUM (1) KEY-NUM (2).
                    MOVE 2 TO KEY-NUM (3) KEY-NUM (4).
                    MOVE 'Z' TO KEY-TEXT (1) KEY-TEXT (3).
                    MOVE 'A' TO KEY-TEXT (2) KEY-TEXT (4).
                    SEARCH ALL ROW-DATA
                        AT END DISPLAY 'UNEXPECTED'
                        WHEN KEY-NUM (ROW-IX) = 1 AND KEY-TEXT (ROW-IX) = 'Z'
                            DISPLAY 'FOUND FIRST'
                    END-SEARCH.
                    SET LAST-INDEX TO ROW-IX.
                    DISPLAY LAST-INDEX.
                    SEARCH ALL ROW-DATA
                        AT END DISPLAY 'UNEXPECTED'
                        WHEN KEY-NUM (ROW-IX) = 2 AND KEY-TEXT (ROW-IX) = 'A'
                            NEXT SENTENCE
                    END-SEARCH
                    DISPLAY 'UNEXPECTED'.
                    SET LAST-INDEX TO ROW-IX.
                    DISPLAY LAST-INDEX.
                    SEARCH ALL ROW-DATA
                        AT END DISPLAY 'MISSING'
                        WHEN KEY-NUM (ROW-IX) = 1 AND KEY-TEXT (ROW-IX) = 'M'
                            DISPLAY 'UNEXPECTED'
                    END-SEARCH.
                    SET LAST-INDEX TO ROW-IX.
                    DISPLAY LAST-INDEX.
                    STOP RUN.
                """);
        Path binary = work.resolve("binary");
        Path generated = work.resolve("generated");
        Path recompiled = work.resolve("recompiled");
        Main.main(new String[] {"--free", "-q", "SSRANGE", "-d", binary.toString(),
                source.toString()});
        Main.main(new String[] {"--free", "--emit-java", "-q", "SSRANGE", "-d",
                generated.toString(), source.toString()});
        Path java = generated.resolve("cobol/generated/FINDKEY.java");
        String text = Files.readString(java);
        assertTrue(text.contains("int next = switch (paragraph)"));
        assertTrue(text.contains("Ops.storeInteger(middle"));
        assertTrue(text.contains("-Integer.signum(Ops.compareAlphanumeric("));
        assertTrue(text.contains("break searchAll"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-classpath", System.getProperty("java.class.path"),
                "-encoding", "UTF-8", "-d", recompiled.toString(), java.toString()));
        List<String> expected = List.of("FOUND FIRST", "1", "4", "MISSING", "1");
        assertEquals(expected, runProgram(binary, "FINDKEY"));
        assertEquals(expected, runProgram(recompiled, "FINDKEY"));
    }

    @Test
    @DisplayName("Java 出力は可変長の表の探索範囲、後続項目の位置、群の送出長を保つ")
    void directJavaPreservesDependingOnTables(@TempDir Path work) throws Exception {
        Path source = work.resolve("DYNTABLE.cbl");
        Files.writeString(source, """
                IDENTIFICATION DIVISION.
                PROGRAM-ID. DYNTABLE.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 ACTIVE-COUNT PIC 9 VALUE 3.
                01 TABLE-DATA.
                   05 ROW-DATA OCCURS 0 TO 3 TIMES DEPENDING ON ACTIVE-COUNT
                      ASCENDING KEY IS ROW-KEY INDEXED BY ROW-IX.
                      10 ROW-KEY PIC X.
                   05 TAIL-TEXT PIC X.
                01 COPY-OUT PIC X(4).
                PROCEDURE DIVISION.
                    MOVE 'A' TO ROW-KEY (1).
                    MOVE 'B' TO ROW-KEY (2).
                    MOVE 'C' TO ROW-KEY (3).
                    MOVE 2 TO ACTIVE-COUNT.
                    SET ROW-IX TO 1.
                    SEARCH ROW-DATA
                        AT END DISPLAY 'LINEAR MISS'
                        WHEN ROW-KEY (ROW-IX) = 'C' DISPLAY 'UNEXPECTED'
                    END-SEARCH.
                    SEARCH ALL ROW-DATA
                        AT END DISPLAY 'BINARY MISS'
                        WHEN ROW-KEY (ROW-IX) = 'C' DISPLAY 'UNEXPECTED'
                    END-SEARCH.
                    SEARCH ALL ROW-DATA
                        AT END DISPLAY 'UNEXPECTED'
                        WHEN ROW-KEY (ROW-IX) = 'B' DISPLAY 'BINARY HIT'
                    END-SEARCH.
                    MOVE 'T' TO TAIL-TEXT.
                    DISPLAY TABLE-DATA.
                    MOVE TABLE-DATA TO COPY-OUT.
                    DISPLAY '[' COPY-OUT ']'.
                    MOVE 'ABCD' TO TABLE-DATA.
                    MOVE 3 TO ACTIVE-COUNT.
                    DISPLAY TABLE-DATA.
                    MOVE 0 TO ACTIVE-COUNT.
                    SEARCH ALL ROW-DATA
                        AT END DISPLAY 'EMPTY'
                        WHEN ROW-KEY (ROW-IX) = 'A' DISPLAY 'UNEXPECTED'
                    END-SEARCH.
                    MOVE 'Z' TO TAIL-TEXT.
                    DISPLAY TABLE-DATA.
                    STOP RUN.
                """);
        Path binary = work.resolve("binary");
        Path generated = work.resolve("generated");
        Path recompiled = work.resolve("recompiled");
        Main.main(new String[] {"--free", "-d", binary.toString(), source.toString()});
        Main.main(new String[] {"--free", "--emit-java", "-d", generated.toString(),
                source.toString()});
        Path java = generated.resolve("cobol/generated/DYNTABLE.java");
        String text = Files.readString(java);
        assertTrue(text.contains("int next = switch (paragraph)"));
        assertTrue(text.contains("Ops.toInt(Ops.readNumeric(NUMERIC_ACTIVE_COUNT,"));
        assertTrue(text.contains("OFFSET_TABLE_DATA$TAIL_TEXT - (3 - Ops.readInteger("));
        assertTrue(text.contains("LENGTH_TABLE_DATA - (3 - Ops.readInteger("));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-classpath", System.getProperty("java.class.path"),
                "-encoding", "UTF-8", "-d", recompiled.toString(), java.toString()));
        List<String> expected = List.of("LINEAR MISS", "BINARY MISS", "BINARY HIT",
                "ABT", "[ABT ]", "ABCD", "EMPTY", "Z");
        assertEquals(expected, runProgram(binary, "DYNTABLE"));
        assertEquals(expected, runProgram(recompiled, "DYNTABLE"));
    }

    @Test
    @DisplayName("Java 出力の ACCEPT と INITIALIZE はクラス出力と同じ入力と初期化規則を使う")
    void directJavaPreservesAcceptAndInitialize(@TempDir Path work) throws Exception {
        Path source = work.resolve("INPUTINIT.cbl");
        Files.writeString(source, """
                IDENTIFICATION DIVISION.
                PROGRAM-ID. INPUTINIT.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 INPUT-TEXT PIC X(82).
                01 INPUT-NUM PIC 9(3) COMP-3.
                01 TODAY-NUM PIC 9(8).
                01 TIME-TEXT PIC X(6) VALUE '------'.
                01 WORK-REC.
                   05 WORK-TEXT PIC XX VALUE 'OK'.
                   05 FILLER PIC X VALUE 'F'.
                   05 WORK-NUM PIC 99.
                PROCEDURE DIVISION.
                    ACCEPT INPUT-TEXT.
                    DISPLAY '[' INPUT-TEXT ']'.
                    ACCEPT INPUT-NUM.
                    DISPLAY INPUT-NUM.
                    ACCEPT TODAY-NUM FROM DATE YYYYMMDD.
                    DISPLAY TODAY-NUM.
                    ACCEPT TIME-TEXT (2:4) FROM TIME.
                    DISPLAY TIME-TEXT.
                    MOVE 'XX!99' TO WORK-REC.
                    INITIALIZE WORK-REC ALL TO VALUE.
                    DISPLAY WORK-REC.
                    INITIALIZE WORK-REC REPLACING ALPHANUMERIC BY 'HI'.
                    DISPLAY WORK-REC.
                    INITIALIZE WORK-REC WITH FILLER.
                    DISPLAY '[' WORK-REC ']'.
                    STOP RUN.
                """);
        Path binary = work.resolve("binary");
        Path generated = work.resolve("generated");
        Path recompiled = work.resolve("recompiled");
        Main.main(new String[] {"--free", "-d", binary.toString(), source.toString()});
        Main.main(new String[] {"--free", "--emit-java", "-d", generated.toString(),
                source.toString()});
        Path java = generated.resolve("cobol/generated/INPUTINIT.java");
        String text = Files.readString(java);
        assertTrue(text.contains("int next = switch (paragraph)"));
        assertTrue(text.contains("Ops.acceptLine(context, LENGTH_INPUT_TEXT)"));
        assertTrue(text.contains("Ops.register(context, \"DATE_YYYYMMDD\")"));
        assertTrue(text.contains("Ops.moveAlphanumeric(INITIALIZE_WORK_REC$"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-classpath", System.getProperty("java.class.path"),
                "-encoding", "UTF-8", "-d", recompiled.toString(), java.toString()));
        Clock clock = Clock.fixed(Instant.parse("2026-09-04T13:45:07.890Z"), ZoneOffset.UTC);
        UnaryOperator<ProgramContext> configure = context -> {
            var lines = List.of("AB", "CD", "042").iterator();
            return context.withClock(clock).withInput(() -> lines.hasNext() ? lines.next() : null);
        };
        List<String> expected = List.of("[AB" + " ".repeat(78) + "CD]", "042", "20260904",
                "-1345-", "OK!99", "HI!99", "[   00]");
        assertEquals(expected, runProgram(binary, "INPUTINIT", configure));
        assertEquals(expected, runProgram(recompiled, "INPUTINIT", configure));
    }

    @Test
    @DisplayName("Java の編集転記は PICTURE の挿入文字、符号、桁位置を保つ")
    void directJavaPreservesEditedMoves(@TempDir Path work) throws Exception {
        Path source = work.resolve("EDITMOVE.cbl");
        Files.writeString(source, """
                IDENTIFICATION DIVISION.
                PROGRAM-ID. EDITMOVE.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 AMOUNT PIC S99V99 VALUE -12.34.
                01 EDITED-AMOUNT PIC -99.99.
                01 RESTORED-AMOUNT PIC S99V99 COMP-3.
                01 BLANK-AMOUNT PIC 999 BLANK WHEN ZERO.
                01 EDITED-TEXT PIC XX/XX.
                01 COPY-TEXT PIC X(5).
                01 SCALED-NUMBER PIC S9PP VALUE -200.
                PROCEDURE DIVISION.
                    MOVE AMOUNT TO EDITED-AMOUNT.
                    DISPLAY EDITED-AMOUNT.
                    MOVE EDITED-AMOUNT TO RESTORED-AMOUNT.
                    IF RESTORED-AMOUNT = -12.34 DISPLAY 'RESTORED' END-IF.
                    MOVE ZERO TO BLANK-AMOUNT.
                    DISPLAY '[' BLANK-AMOUNT ']'.
                    MOVE 0123 TO EDITED-TEXT.
                    MOVE EDITED-TEXT TO COPY-TEXT.
                    DISPLAY COPY-TEXT.
                    MOVE ALL 'AB' TO EDITED-TEXT.
                    DISPLAY EDITED-TEXT.
                    MOVE SCALED-NUMBER TO EDITED-TEXT.
                    DISPLAY '[' EDITED-TEXT ']'.
                    STOP RUN.
                """);
        Path binary = work.resolve("binary");
        Path generated = work.resolve("generated");
        Path recompiled = work.resolve("recompiled");
        Main.main(new String[] {"--free", "-d", binary.toString(), source.toString()});
        Main.main(new String[] {"--free", "--emit-java", "-d", generated.toString(),
                source.toString()});
        Path java = generated.resolve("cobol/generated/EDITMOVE.java");
        String text = Files.readString(java);
        assertTrue(text.contains("int next = switch (paragraph)"));
        assertTrue(text.contains("Ops.moveNumericEdited("));
        assertTrue(text.contains("Ops.deEdit("));
        assertTrue(text.contains("Ops.moveAlphanumericEdited("));
        assertTrue(text.contains("Ops.withScalingZeros(Ops.readUnsignedDigits("));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-classpath", System.getProperty("java.class.path"),
                "-encoding", "UTF-8", "-d", recompiled.toString(), java.toString()));
        List<String> expected = List.of("-12.34", "RESTORED", "[   ]", "01/23", "AB/AB", "[20/0 ]");
        assertEquals(expected, runProgram(binary, "EDITMOVE"));
        assertEquals(expected, runProgram(recompiled, "EDITMOVE"));
    }

    @Test
    @DisplayName("Java 出力は国字の転記、編集、部分参照、比較を保つ")
    void directJavaPreservesNationalMoves(@TempDir Path work) throws Exception {
        Path source = work.resolve("NATMOVE.cbl");
        Files.writeString(source, """
                IDENTIFICATION DIVISION.
                PROGRAM-ID. NATMOVE.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 NAT-TEXT PIC N(5).
                01 NAT-EDITED PIC NBN/0N.
                01 NAT-AMOUNT PIC ZZ9.99 USAGE NATIONAL.
                01 RESTORED-AMOUNT PIC 999V99 COMP-3.
                01 SOURCE-NUMBER PIC S99 VALUE -12.
                01 START-POS PIC 9 VALUE 2.
                01 COPY-SIZE PIC 9 VALUE 2.
                PROCEDURE DIVISION.
                    MOVE ALL 'AB' TO NAT-TEXT.
                    MOVE SOURCE-NUMBER TO NAT-TEXT (START-POS:COPY-SIZE).
                    DISPLAY NAT-TEXT.
                    DISPLAY NAT-TEXT (START-POS:).
                    IF NAT-TEXT (START-POS:2) = '12' DISPLAY 'MATCH' END-IF.
                    MOVE N'XYZ' TO NAT-EDITED.
                    DISPLAY NAT-EDITED.
                    MOVE 10.34 TO NAT-AMOUNT.
                    IF NAT-AMOUNT = N' 10.34' DISPLAY 'EDITED' END-IF.
                    MOVE NAT-AMOUNT TO RESTORED-AMOUNT.
                    IF RESTORED-AMOUNT = 10.34 DISPLAY 'RESTORED' END-IF.
                    MOVE SPACES TO NAT-TEXT.
                    IF NAT-TEXT = SPACES DISPLAY 'EMPTY' END-IF.
                    STOP RUN.
                """);
        Path binary = work.resolve("binary");
        Path generated = work.resolve("generated");
        Path recompiled = work.resolve("recompiled");
        Main.main(new String[] {"--free", "-q", "SSRANGE", "-d", binary.toString(),
                source.toString()});
        Main.main(new String[] {"--free", "--emit-java", "-q", "SSRANGE", "-d",
                generated.toString(), source.toString()});
        Path java = generated.resolve("cobol/generated/NATMOVE.java");
        String text = Files.readString(java);
        assertTrue(text.contains("int next = switch (paragraph)"));
        assertTrue(text.contains("National.move("));
        assertTrue(text.contains("National.compare("));
        assertTrue(text.contains("National.toDisplay("));
        assertTrue(text.contains(".asNational()"));
        assertTrue(text.contains("Ops.deEditNational("));
        assertTrue(text.contains("LENGTH_NAT_TEXT / 2"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-classpath", System.getProperty("java.class.path"),
                "-encoding", "UTF-8", "-d", recompiled.toString(), java.toString()));
        List<String> expected = List.of("A12BA", "12BA", "MATCH", "X Y/0Z", "EDITED", "RESTORED", "EMPTY");
        assertEquals(expected, runProgram(binary, "NATMOVE"));
        assertEquals(expected, runProgram(recompiled, "NATMOVE"));
    }

    @Test
    @DisplayName("Java の STRING は区切り、可変長の送出、ポインタ、あふれ分岐を保つ")
    void directJavaPreservesString(@TempDir Path work) throws Exception {
        Path source = work.resolve("JOINPART.cbl");
        Files.writeString(source, """
                IDENTIFICATION DIVISION.
                PROGRAM-ID. JOINPART.
                DATA DIVISION.
                WORKING-STORAGE SECTION.
                01 ITEM-COUNT PIC 9 VALUE 3.
                01 SOURCE-RECORD.
                   05 SOURCE-CHAR PIC X OCCURS 0 TO 3 TIMES DEPENDING ON ITEM-COUNT.
                01 DELIMITED-TEXT PIC X(4) VALUE 'Q ZZ'.
                01 DEST-TEXT PIC X(7) VALUE '-------'.
                01 WRITE-POS PIC 9 COMP-3 VALUE 2.
                PROCEDURE DIVISION.
                    MOVE 'A' TO SOURCE-CHAR (1).
                    MOVE 'B' TO SOURCE-CHAR (2).
                    MOVE 'C' TO SOURCE-CHAR (3).
                    MOVE 2 TO ITEM-COUNT.
                    STRING DELIMITED-TEXT DELIMITED BY SPACE
                           SOURCE-RECORD '!' DELIMITED BY SIZE
                        INTO DEST-TEXT WITH POINTER WRITE-POS
                        ON OVERFLOW DISPLAY 'UNEXPECTED'
                        NOT ON OVERFLOW DISPLAY 'FIT:' WRITE-POS
                    END-STRING.
                    DISPLAY DEST-TEXT.
                    STRING 'WXYZ' DELIMITED BY SIZE
                        INTO DEST-TEXT WITH POINTER WRITE-POS
                        ON OVERFLOW DISPLAY 'PARTIAL:' WRITE-POS
                        NOT ON OVERFLOW DISPLAY 'UNEXPECTED'
                    END-STRING.
                    DISPLAY DEST-TEXT.
                    STRING '!' DELIMITED BY SIZE INTO DEST-TEXT WITH POINTER WRITE-POS
                        ON OVERFLOW GO TO AFTER-INVALID
                        NOT ON OVERFLOW DISPLAY 'UNEXPECTED'
                    END-STRING
                    DISPLAY 'UNEXPECTED'.
                AFTER-INVALID.
                    DISPLAY 'INVALID:' WRITE-POS.
                    STRING '*' DELIMITED BY SIZE INTO DEST-TEXT.
                    DISPLAY DEST-TEXT.
                    STOP RUN.
                """);
        Path binary = work.resolve("binary");
        Path generated = work.resolve("generated");
        Path recompiled = work.resolve("recompiled");
        Main.main(new String[] {"--free", "-d", binary.toString(), source.toString()});
        Main.main(new String[] {"--free", "--emit-java", "-d", generated.toString(),
                source.toString()});
        Path java = generated.resolve("cobol/generated/JOINPART.java");
        String text = Files.readString(java);
        assertTrue(text.contains("int next = switch (paragraph)"));
        assertTrue(text.contains("StringVerb.Source.bySize("));
        assertTrue(text.contains("StringVerb.Source.delimitedBy("));
        assertTrue(text.contains("Ops.storeInteger(stringResult1.pointer(),"));
        assertTrue(text.contains("if (stringResult1.overflow())"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-classpath", System.getProperty("java.class.path"),
                "-encoding", "UTF-8", "-d", recompiled.toString(), java.toString()));
        List<String> expected = List.of("FIT:6", "-QAB!--", "PARTIAL:8", "-QAB!WX",
                "INVALID:8", "*QAB!WX");
        assertEquals(expected, runProgram(binary, "JOINPART"));
        assertEquals(expected, runProgram(recompiled, "JOINPART"));
    }

    private static List<String> runProgram(Path root, String name) throws Exception {
        return runProgram(root, name, UnaryOperator.identity());
    }

    private static List<String> runProgram(Path root, String name,
                                           UnaryOperator<ProgramContext> configure) throws Exception {
        Class<?> type = new GeneratedLoader().define("cobol.generated." + name,
                Files.readAllBytes(root.resolve("cobol/generated/" + name + ".class")));
        CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        program.runFresh(configure.apply(ProgramContext.capturing(out)));
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    @DisplayName("コピー句のディレクトリを指定できる (FR-090, FR-180)")
    void aCopybookDirectoryCanBeGiven(@TempDir Path work) throws IOException {
        Files.writeString(work.resolve("REC.cpy"), String.join("\n",
                "       01 WS-A PIC X(3) VALUE 'ABC'.",
                ""), StandardCharsets.UTF_8);
        Path source = work.resolve("USER.cbl");
        Files.writeString(source, String.join("\n", List.of(
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. USER.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       COPY REC.",
                "       PROCEDURE DIVISION.",
                "           DISPLAY WS-A.",
                "")), StandardCharsets.UTF_8);
        Path output = work.resolve("out");

        Main.main(new String[] {"-d", output.toString(), "-I", work.toString(),
                source.toString()});

        assertTrue(Files.isRegularFile(output.resolve("cobol/generated/USER.class")));
    }

    @Test
    @DisplayName("自由形式のソースも翻訳できる (FR-002, FR-180)")
    void aFreeFormatSourceCompilesToo(@TempDir Path work) throws IOException {
        Path source = work.resolve("FREE.cbl");
        Files.writeString(source, String.join("\n", List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. FREEONE.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-A PIC X(2) VALUE 'OK'.",
                "PROCEDURE DIVISION.",
                "    DISPLAY WS-A.  *> 行内注釈",
                "")), StandardCharsets.UTF_8);
        Path output = work.resolve("out");

        Main.main(new String[] {"-d", output.toString(), "--free", source.toString()});

        assertTrue(Files.isRegularFile(output.resolve("cobol/generated/FREEONE.class")));
    }

    private static String card(String body, boolean continued) {
        return continued ? String.format("%-71s*", body) : body;
    }

    private static final String BMS = String.join("\n",
            card("SCRSET   DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,STORAGE=AUTO,", true),
            card("               TIOAPFX=YES,EXTATT=MAPONLY", false),
            card("SCRMP    DFHMDI SIZE=(24,80)", false),
            card("CUSTNO   DFHMDF POS=(5,17),LENGTH=10,ATTRB=(NORM,UNPROT,IC)", false),
            card("         DFHMSD TYPE=FINAL", false),
            card("         END", false)) + "\n";

    @Test
    @DisplayName("BMSの記号マップとCICS・Db2の写し句を、置き場を書くだけで引ける (FR-151, FR-162, FR-180)")
    void resolvesBmsAndSystemCopybooks(@TempDir Path work) throws IOException {
        Files.writeString(work.resolve("SCRSET.bms"), BMS, StandardCharsets.ISO_8859_1);
        Path source = work.resolve("SCREEN.cbl");
        Files.writeString(source, String.join("\n", List.of(
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. SCREEN.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       COPY SCRSET.",
                "       COPY DFHAID.",
                "       EXEC SQL INCLUDE SQLCA END-EXEC.",
                "       01  WS-ID PIC S9(4) COMP VALUE 0.",
                "       01  WS-KEY PIC X.",
                "       PROCEDURE DIVISION.",
                "           MOVE 'X' TO CUSTNOO",
                "           MOVE DFHENTER TO WS-KEY",
                "           EXEC SQL DELETE FROM TODO WHERE TODO_ID = :WS-ID END-EXEC",
                "           IF SQLCODE = 0 AND WS-KEY = DFHENTER",
                "               DISPLAY 'OK'",
                "           END-IF",
                "           GOBACK.",
                "")), StandardCharsets.UTF_8);
        Path output = work.resolve("out");

        Main.main(new String[] {"-d", output.toString(), "-I", work.toString(),
                source.toString()});

        assertTrue(Files.isRegularFile(output.resolve("cobol/generated/SCREEN.class")),
                "BMS 原文・DFHAID・SQLCA が引けていれば翻訳できる");
    }

    @Test
    @DisplayName("-Iは何度でも書け、先に書いた置き場が勝つ (FR-090, FR-180)")
    void searchesCopybookDirectoriesInOrder(@TempDir Path work) throws IOException {
        Path first = Files.createDirectory(work.resolve("first"));
        Path second = Files.createDirectory(work.resolve("second"));
        Files.writeString(first.resolve("REC.cpy"),
                "       01 WS-A PIC X(5) VALUE 'FIRST'.\n", StandardCharsets.UTF_8);
        Files.writeString(second.resolve("REC.cpy"),
                "       01 WS-A PIC X(6) VALUE 'SECOND'.\n", StandardCharsets.UTF_8);
        Path source = work.resolve("ORDERED.cbl");
        Files.writeString(source, String.join("\n", List.of(
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. ORDERED.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       COPY REC.",
                "       PROCEDURE DIVISION.",
                "           DISPLAY WS-A.",
                "")), StandardCharsets.UTF_8);
        Path output = work.resolve("out");

        Main.main(new String[] {"-d", output.toString(), "-I", first.toString(),
                "-I", second.toString(), source.toString()});

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            Class<?> type = new GeneratedLoader().define("cobol.generated.ORDERED",
                    Files.readAllBytes(output.resolve("cobol/generated/ORDERED.class")));
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            program.runFresh(ProgramContext.capturing(sink));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
        assertEquals("FIRST" + System.lineSeparator(), sink.toString(StandardCharsets.UTF_8));
    }
}
