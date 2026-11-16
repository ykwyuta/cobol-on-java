package dev.cobolonjava.maven;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.job.Job;
import dev.cobolonjava.job.JobDescription;
import dev.cobolonjava.job.JobRunner;
import dev.cobolonjava.runtime.interop.DeployCatalogManifest;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.source.ProgramOutput;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 標準の置き場からプログラムを作る (設計 91 §3)。 */
class ProgramBuildTest {

    private static final String GREET = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. GREET.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       COPY GREETW.",
            "       COPY TSTSET.",
            "       PROCEDURE DIVISION.",
            "           DISPLAY WS-GREETING.",
            "           GOBACK.",
            "");

    private static final String BROKEN = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. BROKEN.",
            "       PROCEDURE DIVISION.",
            "           MOVE UNDEFINED-ITEM TO OTHER-ITEM.",
            "");

    private static final String MAPSET = String.join("\n",
            "TSTSET   DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,TIOAPFX=YES",
            "TSTMAP   DFHMDI SIZE=(24,80)",
            "NAME     DFHMDF POS=(3,2),LENGTH=8,ATTRB=(UNPROT,NORM,IC)",
            "         DFHMSD TYPE=FINAL",
            "");

    private static final String PLI = """
            HELLO: PROCEDURE OPTIONS(MAIN);
              %INCLUDE BODY;
            END HELLO;
            """;

    @TempDir
    Path project;

    private final Recorder report = new Recorder();

    private Path output() {
        return project.resolve("target/classes");
    }

    private void write(String relative, String text) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private void build() throws IOException, BuildFailure {
        ProgramBuild.run(SourceLayout.standard(project), output(), false, CompilerOptions.NONE,
                report);
    }

    private DeployCatalogManifest catalog() throws IOException {
        return DeployCatalogManifest.fromJson(Files.readString(
                output().resolve(DeployCatalogManifest.RESOURCE_NAME), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("写し句と BMS の記号マップを標準の置き場から引いて COBOL を翻訳し、mapset を classpath の根へ写す")
    void cobolIsBuiltFromTheStandardLayout() throws Exception {
        write("src/main/cobol/GREET.cbl", GREET);
        write("src/main/copybook/GREETW.cpy",
                "       01 WS-GREETING PIC X(5) VALUE 'HELLO'.\n");
        write("src/main/bms/TSTSET.bms", MAPSET);

        build();

        assertTrue(Files.isRegularFile(output().resolve("cobol/generated/GREET.class")),
                () -> report.lines.toString());
        assertEquals(List.of("GREET"), catalog().programs().stream()
                .map(program -> program.programId().value()).toList());
        assertTrue(Files.isRegularFile(output().resolve("TSTSET.bms")));
    }

    @Test
    @DisplayName("下位ディレクトリも大文字の拡張子も拾い、写し句 (.cpy) は原文として拾わない")
    void subdirectoriesAndUpperCaseSuffixesArePickedUp() throws Exception {
        write("src/main/cobol/batch/GREET.CBL", GREET);
        write("src/main/cobol/GREETW.cpy",
                "       01 WS-GREETING PIC X(5) VALUE 'HELLO'.\n");
        write("src/main/copybook/GREETW.cpy",
                "       01 WS-GREETING PIC X(5) VALUE 'HELLO'.\n");
        write("src/main/bms/TSTSET.bms", MAPSET);

        build();

        assertEquals(List.of("GREET"), catalog().programs().stream()
                .map(program -> program.programId().value()).toList());
    }

    @Test
    @DisplayName("1 本が翻訳できなくても残りは翻訳し、診断を出してからビルドを止める")
    void aFailedSourceStopsTheBuildAfterTheRestIsBuilt() throws Exception {
        write("src/main/cobol/BROKEN.cbl", BROKEN);
        write("src/main/cobol/GREET.cbl", GREET);
        write("src/main/copybook/GREETW.cpy",
                "       01 WS-GREETING PIC X(5) VALUE 'HELLO'.\n");
        write("src/main/bms/TSTSET.bms", MAPSET);

        BuildFailure failure = assertThrows(BuildFailure.class, this::build);

        assertEquals("1 source(s) failed to build", failure.getMessage());
        assertTrue(report.lines.stream().anyMatch(line -> line.startsWith("ERROR ")
                && line.contains("BROKEN")), () -> report.lines.toString());
        assertEquals(List.of("GREET"), catalog().programs().stream()
                .map(program -> program.programId().value()).toList());
    }

    @Test
    @DisplayName("COPY されない mapset の誤りもビルドで出す")
    void anInvalidMapsetFailsTheBuild() throws Exception {
        write("src/main/bms/BADSET.bms", "BADSET   DFHMSD TYPE=&SYSPARM,NOSUCH=1\n");

        assertThrows(BuildFailure.class, this::build);

        assertTrue(report.lines.stream().anyMatch(line -> line.startsWith("ERROR ")
                && line.contains("BADSET.bms")), () -> report.lines.toString());
        assertFalse(Files.exists(output().resolve("BADSET.bms")));
    }

    @Test
    @DisplayName("PL/I を %INCLUDE の置き場を引いて翻訳し、COBOL と同じ名前空間のカタログを書く")
    void pliIsBuiltFromTheStandardLayout() throws Exception {
        write("src/main/pli/HELLO.pli", PLI);
        write("src/main/pli-include/BODY.inc", "PUT SKIP LIST('HELLO');\n");

        build();

        assertTrue(Files.isRegularFile(output().resolve("cobol/generated/HELLO.class")),
                () -> report.lines.toString());
        DeployCatalogManifest catalog = catalog();
        assertEquals("cobol.generated", catalog.allowedPackage());
        assertEquals(List.of("HELLO"), catalog.programs().stream()
                .map(program -> program.programId().value()).toList());
    }

    @Test
    @DisplayName("COBOL と PL/I を 1 つのモジュールに置くと、配備カタログを 1 つにまとめる (P-182 の解消)")
    void cobolAndPliInOneModuleShareOneCatalog() throws Exception {
        write("src/main/cobol/GREET.cbl", GREET);
        write("src/main/copybook/GREETW.cpy",
                "       01 WS-GREETING PIC X(5) VALUE 'HELLO'.\n");
        write("src/main/bms/TSTSET.bms", MAPSET);
        write("src/main/pli/HELLO.pli", PLI);
        write("src/main/pli-include/BODY.inc", "PUT SKIP LIST('HELLO');\n");

        build();

        DeployCatalogManifest catalog = catalog();
        assertEquals(List.of("GREET", "HELLO"), catalog.programs().stream()
                .map(program -> program.programId().value()).toList());
        assertEquals(List.of("cobol.generated.GREET", "cobol.generated.HELLO"),
                catalog.programs().stream().map(program -> program.className()).toList());
    }

    @Test
    @DisplayName("COBOL と PL/I に同じ名前のプログラムがあれば断る。ホストの 1 つのロードライブラリと同じ")
    void aNameUsedByBothLanguagesIsRefused() throws Exception {
        write("src/main/cobol/HELLO.cbl", GREET.replace("PROGRAM-ID. GREET.", "PROGRAM-ID. HELLO."));
        write("src/main/copybook/GREETW.cpy",
                "       01 WS-GREETING PIC X(5) VALUE 'HELLO'.\n");
        write("src/main/bms/TSTSET.bms", MAPSET);
        write("src/main/pli/HELLO.pli", PLI);
        write("src/main/pli-include/BODY.inc", "PUT SKIP LIST('HELLO');\n");

        assertThrows(BuildFailure.class, this::build);

        assertTrue(report.lines.stream().anyMatch(line -> line.startsWith("ERROR ")
                && line.contains("HELLO")), () -> report.lines.toString());
    }

    @Test
    @DisplayName("HLASM を組み立てて同じ名前空間に置く。配備カタログには載せない")
    void hlasmIsAssembledIntoTheSameNameSpace() throws Exception {
        write("src/main/asm/BUMP.asm", String.join("\n",
                "BUMP     CSECT",
                "         USING BUMP,15",
                "         SR    15,15",
                "         BR    14",
                "         END",
                ""));

        build();

        assertTrue(Files.isRegularFile(output().resolve("cobol/generated/BUMP.class")),
                () -> report.lines.toString());
        assertFalse(Files.exists(output().resolve(DeployCatalogManifest.RESOURCE_NAME)));
        assertTrue(report.lines.contains("INFO HLASM: 1 module(s), not in the deploy catalog"));
    }

    @Test
    @DisplayName("HLASM の COPY メンバを asmlib から読み、プログラムには数えない")
    void hlasmCopyUsesSeparateLibrary() throws Exception {
        write("src/main/asmlib/DEF.asm", String.join("\n",
                "         MACRO",
                "         DOIT",
                "         SR    15,15",
                "         BR    14",
                "         MEND"));
        write("src/main/asm/BUMP.asm", String.join("\n",
                "BUMP     CSECT",
                "         COPY  DEF",
                "         DOIT",
                "         END"));

        build();

        assertTrue(Files.isRegularFile(output().resolve("cobol/generated/BUMP.class")),
                () -> report.lines.toString());
        assertTrue(report.lines.contains("INFO HLASM: 1 module(s), not in the deploy catalog"));
    }

    @Test
    @DisplayName("4 言語から生成した Java を再コンパイルして同じジョブを実行する")
    void generatedJavaRunsTheSameJob() throws Exception {
        write("src/main/cobol/GREET.cbl", String.join("\n",
                "       CBL TRUNC(BIN)",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. GREET.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01 FIRST-REC.",
                "          05 ITEM-TEXT PIC X(5).",
                "       01 SECOND-REC REDEFINES FIRST-REC.",
                "          05 ITEM-TEXT PIC X(5).",
                "       01 WS-TABLE.",
                "          05 ROW-TEXT PIC X(5) OCCURS 2 TIMES.",
                "       01 WS-SUB PIC 9 VALUE 2.",
                "       PROCEDURE DIVISION.",
                "       DRIVER SECTION.",
                "           MOVE 'COBOL' TO ITEM-TEXT OF FIRST-REC.",
                "           PERFORM SPEAK.",
                "           GOBACK.",
                "       SPEAK SECTION.",
                "           ENTRY 'GREETALT'.",
                "           MOVE ITEM-TEXT OF SECOND-REC TO ROW-TEXT(WS-SUB).",
                "           DISPLAY ROW-TEXT(WS-SUB)(2:3).",
                "       END PROGRAM GREET.",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. SECOND.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01 WS-FLAG PIC X VALUE 'N'.",
                "       01 WS-COUNT PIC 9(4) COMP.",
                "       01 WS-PACKED PIC S9(3)V99 COMP-3.",
                "       01 WS-SIGNED PIC S9(3) SIGN LEADING SEPARATE.",
                "       01 WS-INDEX PIC 9.",
                "       01 WS-SMALL PIC 9 VALUE 7.",
                "       01 WS-RESULT PIC 99V99.",
                "       01 WS-ZERO PIC 9 VALUE 0.",
                "       01 WS-DIVIDEND PIC 99V9 VALUE 20.",
                "       01 WS-REM PIC 9V9.",
                "       01 WS-Q-EDIT PIC ZZ9.",
                "       01 WS-R-EDIT PIC ZZ9.",
                "       01 WS-ERRORS PIC 9 VALUE 0.",
                "       01 PAIR-A.",
                "          05 N1 PIC 999 VALUE 900.",
                "          05 N2 PIC 999 VALUE 900.",
                "          05 N3 PIC 999 VALUE 1.",
                "          05 ITEM-LETTER PIC X VALUE 'X'.",
                "       01 PAIR-B.",
                "          05 N1 PIC 999 VALUE 100.",
                "          05 N2 PIC 999 VALUE 100.",
                "          05 N3 PIC 999 VALUE 100.",
                "          05 ITEM-LETTER PIC X VALUE 'Y'.",
                "       PROCEDURE DIVISION.",
                "       START-HERE.",
                "           PERFORM WITH TEST AFTER UNTIL WS-FLAG = 'Y'",
                "               MOVE 'Y' TO WS-FLAG",
                "           END-PERFORM.",
                "           IF WS-FLAG = 'Y' OR WS-FLAG = 'T'",
                "               MOVE 2 TO WS-COUNT",
                "               PERFORM SAY-LINE WS-COUNT TIMES",
                "           ELSE",
                "               DISPLAY 'UNEXPECTED'",
                "           END-IF.",
                "           MOVE -12.75 TO WS-PACKED.",
                "           MOVE WS-PACKED TO WS-SIGNED.",
                "           MOVE 12345 TO WS-COUNT.",
                "           IF WS-PACKED = -12.75 AND WS-SIGNED = -12",
                "              AND WS-COUNT = 12345",
                "               DISPLAY 'NUMERIC OK'",
                "           END-IF.",
                "           DISPLAY 'SIGNED' WS-SIGNED ' PACKED' WS-PACKED.",
                "           PERFORM VARYING WS-INDEX FROM 3 BY 1",
                "               UNTIL WS-INDEX > 2",
                "               DISPLAY 'UNEXPECTED BEFORE'",
                "           END-PERFORM.",
                "           PERFORM WITH TEST AFTER",
                "               VARYING WS-INDEX FROM 1 BY 1 UNTIL WS-INDEX >= 2",
                "               DISPLAY 'INDEX' WS-INDEX",
                "           END-PERFORM.",
                "           ADD WS-SMALL 3 GIVING WS-SMALL WS-COUNT",
                "               ON SIZE ERROR DISPLAY 'ADD SIZE'",
                "           END-ADD.",
                "           IF WS-SMALL = 7 AND WS-COUNT = 10",
                "               DISPLAY 'ADD TARGETS'",
                "           END-IF.",
                "           SUBTRACT 2 FROM WS-COUNT.",
                "           MULTIPLY 3 BY WS-COUNT.",
                "           DIVIDE 7 INTO WS-COUNT",
                "               GIVING WS-SMALL WS-RESULT ROUNDED.",
                "           IF WS-SMALL = 3 AND WS-RESULT = 3.43",
                "               DISPLAY 'DIVIDE SCALE'",
                "           END-IF.",
                "           DIVIDE WS-ZERO INTO WS-COUNT",
                "               ON SIZE ERROR DISPLAY 'DIVIDE SIZE'",
                "           END-DIVIDE.",
                "           COMPUTE WS-COUNT WS-RESULT ROUNDED =",
                "               (WS-COUNT + 1) / 6.",
                "           IF WS-COUNT + 1 = 5 AND WS-RESULT = 4.17",
                "               DISPLAY 'COMPUTE ONCE'",
                "           END-IF.",
                "           COMPUTE WS-COUNT = 10 / WS-ZERO",
                "               ON SIZE ERROR DISPLAY 'COMPUTE SIZE'",
                "           END-COMPUTE.",
                "           IF WS-COUNT = 4",
                "               DISPLAY 'COMPUTE TARGET'",
                "           END-IF.",
                "           DIVIDE WS-DIVIDEND BY 7 GIVING WS-DIVIDEND",
                "               ROUNDED REMAINDER WS-REM.",
                "           IF WS-DIVIDEND = 2.9 AND WS-REM = 0.4",
                "               DISPLAY 'REMAINDER ROUNDED'",
                "           END-IF.",
                "           MOVE 8 TO WS-SMALL.",
                "           DIVIDE 100 BY 1 GIVING WS-SMALL REMAINDER WS-REM",
                "               ON SIZE ERROR DISPLAY 'REMAINDER Q ERROR'",
                "           END-DIVIDE.",
                "           IF WS-SMALL = 8 AND WS-REM = 0.4",
                "               DISPLAY 'REMAINDER Q RETAINED'",
                "           END-IF.",
                "           DIVIDE 100 BY 30 GIVING WS-SMALL REMAINDER WS-REM",
                "               ON SIZE ERROR DISPLAY 'REMAINDER R ERROR'",
                "           END-DIVIDE.",
                "           IF WS-SMALL = 3 AND WS-REM = 0.4",
                "               DISPLAY 'REMAINDER R RETAINED'",
                "           END-IF.",
                "           DIVIDE 100 BY WS-ZERO",
                "               GIVING WS-SMALL REMAINDER WS-REM",
                "               ON SIZE ERROR DISPLAY 'REMAINDER ZERO'",
                "           END-DIVIDE.",
                "           IF WS-SMALL = 3 AND WS-REM = 0.4",
                "               DISPLAY 'REMAINDER ZERO RETAINED'",
                "           END-IF.",
                "           DIVIDE 174 BY 16 GIVING WS-Q-EDIT",
                "               REMAINDER WS-R-EDIT.",
                "           DISPLAY 'EDITED' WS-Q-EDIT WS-R-EDIT.",
                "           ADD CORRESPONDING PAIR-A TO PAIR-B",
                "               ON SIZE ERROR ADD 1 TO WS-ERRORS",
                "           END-ADD.",
                "           IF N1 OF PAIR-B = 100 AND N2 OF PAIR-B = 100",
                "              AND N3 OF PAIR-B = 101 AND WS-ERRORS = 1",
                "               DISPLAY 'CORRESPONDING ADD'",
                "           END-IF.",
                "           SUBTRACT CORRESPONDING PAIR-A FROM PAIR-B.",
                "           IF N3 OF PAIR-B = 100",
                "               DISPLAY 'CORRESPONDING SUBTRACT'",
                "           END-IF.",
                "           MOVE CORRESPONDING PAIR-A TO PAIR-B.",
                "           IF N1 OF PAIR-B = 900 AND ITEM-LETTER OF PAIR-B = 'X'",
                "               DISPLAY 'CORRESPONDING MOVE'",
                "           END-IF.",
                "           GOBACK.",
                "       SAY-LINE.",
                "           DISPLAY 'SECOND' WS-FLAG",
                "           MOVE ZERO TO WS-COUNT.",
                "       END PROGRAM SECOND."));
        String pli = PLI.stripTrailing();
        String include = "PUT SKIP LIST('PL/I');  \r\n/* \"\"\" \\u000A\t"
                + "a".repeat(9_000) + " */\n";
        write("src/main/pli/HELLO.pli", pli);
        write("src/main/pli-include/BODY.inc", include);
        write("src/main/asm/BUMP.asm", String.join("\n",
                "BUMP     CSECT",
                "         SR    15,15",
                "         BR    14",
                "LOCAL    DC    A(BUMP)",
                "OUTSIDE  DC    V(SECOND)",
                "PAD      DS    CL60000",
                // 再配置の表を一つの静的初期化子に出すと JVM のコード長制限を超える。
                "         DC    A(BUMP)\n".repeat(4_500) + "         END"));
        write("src/main/proclib/OUTPUT", String.join("\n",
                "//OUTPUT   PROC",
                "//COPY     EXEC PGM=IEBGENER",
                "//SYSIN    DD DUMMY",
                "//SYSUT1   DD *",
                "INLINE TEXT",
                "/*",
                "//SYSUT2   DD SYSOUT=*"));
        String description = "src/main/jcl/MIXED.jcl";
        write(description, String.join("\n",
                "//MIXED    JOB",
                "//COB      EXEC PGM=GREET",
                "//         IF (COB.RC = 0) THEN",
                "//ALT      EXEC PGM=GREETALT",
                "//         ENDIF",
                "//COB2     EXEC PGM=SECOND",
                "//PL1      EXEC PGM=HELLO",
                "//ASM      EXEC PGM=BUMP",
                "//TEXT     EXEC OUTPUT"));

        build();
        SourceLayout layout = SourceLayout.standard(project);
        Path sources = project.resolve("target/generated-sources/legacy");
        ProgramBuild.run(layout, sources, false, CompilerOptions.NONE, ProgramOutput.JAVA, report);
        JobCheck.run(layout, sources, true, report);

        String cobolJava = Files.readString(sources.resolve("cobol/generated/GREET.java"));
        assertTrue(cobolJava.contains("paragraph$SPEAK("));
        assertTrue(cobolJava.contains("paragraph$ENTRY$GREETALT("));
        assertTrue(cobolJava.contains("performRange(PARAGRAPH_SPEAK,"));
        assertTrue(cobolJava.contains("Storage storage, ProgramContext context, DataView[] arguments"));
        assertTrue(cobolJava.contains(", OFFSET_FIRST_REC$ITEM_TEXT, LENGTH_FIRST_REC$ITEM_TEXT,"));
        assertTrue(cobolJava.contains("Ops.read(storage, OFFSET_SECOND_REC$ITEM_TEXT,"));
        assertTrue(cobolJava.contains("OFFSET_WS_TABLE$ROW_TEXT +"));
        String directJava = Files.readString(sources.resolve("cobol/generated/SECOND.java"));
        assertTrue(directJava.contains("int next = switch (paragraph)"));
        assertTrue(directJava.contains("if ((Ops.compareAlphanumeric("));
        assertTrue(directJava.contains("do {"));
        assertTrue(directJava.contains("int limit1 = Ops.toInt(Ops.readNumeric(NUMERIC_WS_COUNT"));
        assertTrue(directJava.contains("for (int iteration1 = 0; iteration1 < limit1; iteration1++)"));
        assertTrue(directJava.contains(".withTruncMode(TruncMode.BIN)"));
        assertTrue(directJava.contains("Ops.compareNumeric("));
        assertTrue(directJava.contains("Ops.displayForm(Ops.readNumeric(NUMERIC_WS_PACKED"));
        assertTrue(directJava.contains("for (Ops.store("));
        assertTrue(directJava.contains("Ops.storeChecked("));
        assertTrue(directJava.contains("Ops.divide("));
        assertTrue(directJava.contains("catch (DecimalDivideException ignored)"));
        assertTrue(directJava.contains("Ops.remainder("));
        assertTrue(directJava.contains("Ops.storeEdited(quotient"));
        assertTrue(directJava.contains("NUMERIC_PAIR_B$N3"));
        assertTrue(directJava.contains("performRange(PARAGRAPH_SAY_LINE, PARAGRAPH_SAY_LINE,"));

        List<String> javac = new ArrayList<>(List.of(
                "--release", "21", "-encoding", "UTF-8", "-classpath",
                System.getProperty("java.class.path"), "-d", project.resolve("java-classes").toString()));
        try (var files = Files.walk(sources)) {
            List<Path> generated = files.filter(Files::isRegularFile).toList();
            assertFalse(generated.stream().anyMatch(file -> file.toString().endsWith(".class")));
            List<String> javaFiles = generated.stream().map(Path::toString)
                    .filter(file -> file.endsWith(".java")).toList();
            assertEquals(6, javaFiles.size());
            javac.addAll(javaFiles);
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                javac.toArray(String[]::new)));
        DeployCatalogManifest sourceCatalog = DeployCatalogManifest.fromJson(
                Files.readString(sources.resolve(DeployCatalogManifest.RESOURCE_NAME)));
        assertEquals(catalog().programs(), sourceCatalog.programs());

        JobDescription.Result originalJob = JobDescription.read(project.resolve(description),
                layout.proclib());
        assertTrue(originalJob.succeeded(), () -> originalJob.diagnostics().toString());
        ByteArrayOutputStream original = new ByteArrayOutputStream();
        ByteArrayOutputStream converted = new ByteArrayOutputStream();
        try (URLClassLoader binary = loader(output());
             URLClassLoader recompiled = loader(project.resolve("java-classes"))) {
            var pliSource = recompiled.loadClass("cobol.generated.HELLO")
                    .getDeclaredField("PLI_SOURCE");
            assertTrue(pliSource.trySetAccessible());
            // 差し込んだ後ろに、元の行番号の印 (PliPreprocessor.LINE_MARK) が付く
            assertEquals(pli.replace("%INCLUDE BODY;", include + "/*#LINE 2*/"),
                    pliSource.get(null));
            assertArrayEquals(loadProgram(binary, "BUMP").initialStorage(),
                    loadProgram(recompiled, "BUMP").initialStorage());
            CobolProgram binarySecond = loadProgram(binary, "SECOND");
            CobolProgram javaSecond = loadProgram(recompiled, "SECOND");
            assertArrayEquals(binarySecond.initialStorage(), javaSecond.initialStorage());
            assertEquals(binarySecond.storageMap(), javaSecond.storageMap());
            assertEquals(binarySecond.programSignature(), javaSecond.programSignature());
            assertEquals(binarySecond.procedureManifest(), javaSecond.procedureManifest());
            Job generatedJob = (Job) recompiled.loadClass("cobol.generated.job.Job_MIXED")
                    .getMethod("job").invoke(null);
            JobRunner.Result expected = JobRunner.at(project.resolve("work-binary"), binary, original)
                    .run(originalJob.job());
            JobRunner.Result actual = JobRunner.at(project.resolve("work-java"), recompiled, converted)
                    .run(generatedJob);
            assertEquals(0, expected.returnCode(), () -> expected.steps().toString());
            assertEquals(expected.returnCode(), actual.returnCode());
            assertEquals(expected.steps(), actual.steps());
            assertEquals(original.toString(StandardCharsets.UTF_8),
                    converted.toString(StandardCharsets.UTF_8));
            assertTrue(converted.toString(StandardCharsets.UTF_8).contains("OBO"));
            assertEquals(2, converted.toString(StandardCharsets.UTF_8).lines()
                    .filter("SECONDY"::equals).count());
            assertTrue(converted.toString(StandardCharsets.UTF_8).contains("NUMERIC OK"));
            assertEquals(List.of("INDEX1", "INDEX2"), converted.toString(StandardCharsets.UTF_8)
                    .lines().filter(line -> line.startsWith("INDEX")).toList());
            assertEquals(List.of("ADD SIZE", "ADD TARGETS", "DIVIDE SCALE", "DIVIDE SIZE",
                            "COMPUTE ONCE", "COMPUTE SIZE", "COMPUTE TARGET"),
                    converted.toString(StandardCharsets.UTF_8).lines()
                            .filter(line -> line.startsWith("ADD") || line.startsWith("DIVIDE")
                                    || line.startsWith("COMPUTE")).toList());
            assertEquals(List.of("REMAINDER ROUNDED", "REMAINDER Q ERROR", "REMAINDER Q RETAINED",
                            "REMAINDER R ERROR", "REMAINDER R RETAINED", "REMAINDER ZERO",
                            "REMAINDER ZERO RETAINED", "EDITED 10 14",
                            "CORRESPONDING ADD", "CORRESPONDING SUBTRACT", "CORRESPONDING MOVE"),
                    converted.toString(StandardCharsets.UTF_8).lines()
                            .filter(line -> line.startsWith("REMAINDER") || line.startsWith("EDITED")
                                    || line.startsWith("CORRESPONDING")).toList());
            assertTrue(converted.toString(StandardCharsets.UTF_8).contains("INLINE TEXT"));
        }
    }

    private static URLClassLoader loader(Path classes) throws IOException {
        return new URLClassLoader(new URL[] {classes.toUri().toURL()},
                ProgramBuildTest.class.getClassLoader());
    }

    private static CobolProgram loadProgram(ClassLoader loader, String name)
            throws ReflectiveOperationException {
        return (CobolProgram) loader.loadClass("cobol.generated." + name).getConstructor().newInstance();
    }

    @Test
    @DisplayName("原文が無ければ何も書かない")
    void nothingIsWrittenWithoutSources() throws Exception {
        build();

        assertFalse(Files.exists(output()));
    }
}
