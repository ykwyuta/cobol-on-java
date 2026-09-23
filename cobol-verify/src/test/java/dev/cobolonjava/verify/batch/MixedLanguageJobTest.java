package dev.cobolonjava.verify.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolBuild;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.hlasm.HlasmCompiler;
import dev.cobolonjava.job.JobRunner;
import dev.cobolonjava.job.jcl.Jcl;
import dev.cobolonjava.pli.PliBuild;
import java.io.ByteArrayOutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * COBOL・PL/I・HLASM のプログラムを 1 つの名前空間に置き、JCL の {@code EXEC PGM=} と COBOL の
 * {@code CALL} が言語を問わず名前で引けることを確かめる (暫定判断 P-182 の解消)。
 *
 * <p>ホストでは 3 つの言語のロードモジュールが同じロードライブラリに入る。以前は PL/I を
 * {@code pli.generated}、HLASM を {@code hlasm.generated} に置いていたので、ジョブのステップから
 * PL/I のプログラムを呼べなかった。
 */
class MixedLanguageJobTest {

    @TempDir
    Path work;

    @Test
    @DisplayName("1 つのジョブで COBOL と PL/I のステップが続けて動き、COBOL から HLASM を CALL できる")
    void oneJobRunsProgramsOfEveryLanguage() throws Exception {
        Path classes = work.resolve("classes");
        Path cobol = Files.writeString(work.resolve("CALLER.cbl"), String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. CALLER.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01 WS-COUNT PIC S9(5) COMP-3 VALUE 41.",
                "       PROCEDURE DIVISION.",
                "           CALL 'BUMP' USING WS-COUNT",
                "           DISPLAY 'COBOL ' WS-COUNT",
                "           GOBACK.",
                ""));
        Path pli = Files.writeString(work.resolve("GREETER.pli"), """
                GREETER: PROCEDURE OPTIONS(MAIN);
                  PUT SKIP LIST('PL/I');
                END GREETER;
                """);
        CobolBuild.Result cobolResult = CobolBuild.run(new CobolBuild.Request(List.of(cobol),
                classes, List.of(), false, CompilerOptions.NONE), (source, diagnostic) -> { });
        assertTrue(cobolResult.succeeded());
        PliBuild.Result pliResult = PliBuild.run(new PliBuild.Request(List.of(pli), classes,
                List.of()), diagnostic -> { });
        assertTrue(pliResult.succeeded());
        HlasmCompiler.Result bump = HlasmCompiler.standard().compile("BUMP.asm", String.join("\n",
                "BUMP     CSECT",
                "         USING BUMP,15",
                "         L     2,0(0,1)",
                "         AP    0(3,2),ONE",
                "         SR    15,15",
                "         BR    14",
                "ONE      DC    PL1'1'",
                "         END"));
        assertTrue(bump.succeeded(), () -> bump.diagnostics().toString());
        Path bumpClass = classes.resolve(bump.className().replace('.', '/') + ".class");
        Files.write(bumpClass, bump.classFile());

        Jcl.Result job = Jcl.read(String.join("\n",
                "//MIXED    JOB  (ACCT)",
                "//STEP1    EXEC PGM=CALLER",
                "//STEP2    EXEC PGM=GREETER"));
        assertTrue(job.succeeded(), job.diagnostics()::toString);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {
                classes.toUri().toURL()}, MixedLanguageJobTest.class.getClassLoader())) {
            JobRunner.Result result = JobRunner.at(work.resolve("work"), loader, out)
                    .run(job.job());

            assertEquals(0, result.returnCode(), result.toString());
        }
        // HLASM の AP が 41 に 1 を足した。符号付きの項目の DISPLAY は、最後の桁に符号を重ねた
        // ゾーン形式で出る (+2 は 'B')
        assertEquals(List.of("COBOL 0004B", "PL/I"),
                out.toString(StandardCharsets.UTF_8).lines().toList());
    }
}
