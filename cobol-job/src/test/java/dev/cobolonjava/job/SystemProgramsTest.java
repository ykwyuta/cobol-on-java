package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 別のモジュールが差し込んだシステムのプログラムを、ステップとして動かす (設計 78 §7.1)。 */
@Tag("V1")
class SystemProgramsTest {

    @TempDir
    Path directory;

    @Test
    @DisplayName("差し込んだプログラムはジョブの読み込み器を受け取り、ステップとして動く")
    void aProvidedSystemProgramRunsAsAStep() {
        JobScript.Result script = JobScript.read(String.join("\n", "JOB SYSPGM", "STEP ONE PGM=TESTSYS1"));
        assertTrue(script.succeeded(), () -> script.diagnostics().toString());

        JobRunner.Result result = JobRunner.at(directory.resolve("work"), getClass().getClassLoader(),
                new ByteArrayOutputStream()).withBase(directory).run(script.job());

        assertEquals(7, result.step("ONE").returnCode(), result.toString());
    }
}
