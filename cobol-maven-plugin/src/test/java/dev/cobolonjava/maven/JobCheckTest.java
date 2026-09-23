package dev.cobolonjava.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ジョブ記述を実行時と同じ読み取りで検める (設計 91 §4)。 */
class JobCheckTest {

    private static final String PROC = String.join("\n",
            "//COPYPROC PROC MEMBER=DEFAULT",
            "//COPY     EXEC PGM=IEBGENER",
            "//SYSUT1   DD   DSN=&MEMBER,DISP=SHR",
            "//SYSUT2   DD   SYSOUT=*",
            "");

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

    private void check() throws IOException, BuildFailure {
        JobCheck.run(SourceLayout.standard(project), output(), report);
    }

    @Test
    @DisplayName("PROCLIB の目録手続きを展開して JCL を検め、JCL と手続きを成果物へ写す")
    void jclIsCheckedAgainstTheProcedureLibrary() throws Exception {
        write("src/main/jcl/nightly/PAYROLL.jcl", String.join("\n",
                "//PAYROLL  JOB  (ACCT)",
                "//STEP1    EXEC COPYPROC,MEMBER=PAY.IN",
                ""));
        write("src/main/proclib/COPYPROC", PROC);

        check();

        assertTrue(Files.isRegularFile(
                output().resolve(JobCheck.JCL_RESOURCE).resolve("nightly/PAYROLL.jcl")));
        assertTrue(Files.isRegularFile(
                output().resolve(JobCheck.PROCLIB_RESOURCE).resolve("COPYPROC")));
    }

    @Test
    @DisplayName("PROCLIB に無い手続きはビルドで断る。実行まで持ち越さない")
    void aMissingProcedureFailsTheBuild() throws Exception {
        write("src/main/jcl/PAYROLL.jcl", String.join("\n",
                "//PAYROLL  JOB  (ACCT)",
                "//STEP1    EXEC NOSUCH",
                ""));

        assertThrows(BuildFailure.class, this::check);

        assertTrue(report.lines.stream().anyMatch(line -> line.startsWith("ERROR ")
                && line.contains("PAYROLL.jcl")), () -> report.lines.toString());
        assertFalse(Files.exists(output().resolve(JobCheck.JCL_RESOURCE).resolve("PAYROLL.jcl")));
    }

    @Test
    @DisplayName(".job は宣言的形式として読む。知らない書き方は誤りである")
    void declarativeJobsAreReadAsJobScripts() throws Exception {
        write("src/main/jcl/GOOD.job", String.join("\n",
                "JOB PAYROLL",
                "STEP CHECK PGM=PAYCHK",
                "  DD SYSOUT SYSOUT",
                ""));
        write("src/main/jcl/BAD.job", String.join("\n",
                "JOB PAYROLL",
                "STEP CHECK PGM=PAYCHK",
                "  NOSUCH KEYWORD",
                ""));

        BuildFailure failure = assertThrows(BuildFailure.class, this::check);

        assertEquals("1 job description(s) have errors", failure.getMessage());
        assertTrue(Files.isRegularFile(output().resolve(JobCheck.JCL_RESOURCE).resolve("GOOD.job")));
    }
}
