package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 置き場と目録は別のものである (要件 FR-131, FR-133、暫定判断 P-045 の解消)。
 *
 * <p>ここで確かめるのは<b>4 つの残し方が違うことをする</b>ことである。以前はどれも
 * 「ファイルをそのまま置く」だけで、{@code KEEP} と {@code CATLG} が同じ意味だった。
 * ホストでは {@code KEEP} で残したものは目録に載らず、次のジョブが名前だけで指しても
 * 見つからない。その違いが出るかを見る。
 */
@Tag("V1")
class CatalogTest {

    @TempDir
    Path directory;

    private ByteArrayOutputStream sink;

    private JobRunner.Result run(String... cards) {
        sink = new ByteArrayOutputStream();
        dev.cobolonjava.job.jcl.Jcl.Result parsed = dev.cobolonjava.job.jcl.Jcl.read(
                String.join("\n", cards));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        return JobRunner.at(directory.resolve("work"),
                        CatalogTest.class.getClassLoader(), sink)
                .withBase(directory)
                .run(parsed.job());
    }

    private void write(String name, String content, int length) {
        try {
            Files.write(directory.resolve(name), CodePages.DEFAULT.encode(content));
            Files.write(directory.resolve(name + ".meta"),
                    ("recfm=F\nlrecl=" + length + "\ncodepage=IBM-1047\n")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean onVolume(String name) {
        return Files.exists(directory.resolve(name));
    }

    /** {@code IN.DAT} を作って {@code OUT.DAT} へ写す 1 ステップ。処置は呼ぶ側が決める。 */
    private JobRunner.Result copyInto(String disp) {
        return run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=IN.DAT,DISP=SHR",
                "//SYSUT2   DD   DSN=OUT.DAT,DISP=" + disp);
    }

    /** {@code OUT.DAT} を名前だけで読もうとする別のジョブ。 */
    private JobRunner.Result readBack(String extra) {
        return run(
                "//K        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=OUT.DAT,DISP=SHR" + extra,
                "//SYSUT2   DD   SYSOUT=*");
    }

    // ---- 残し方の違い ----

    @Test
    @DisplayName("KEEP で残したものは目録に載らない (FR-133、暫定判断 P-045)")
    void keepLeavesItOffTheCatalog() {
        write("IN.DAT", "AAAAA", 5);
        assertEquals(0, copyInto("(NEW,KEEP)").returnCode());

        // バイト列は置き場にある。無いのは目録の項目である
        assertTrue(onVolume("OUT.DAT"));

        JobRunner.Result next = readBack("");
        assertEquals(JobRunner.Status.FAILED, next.step("STEP1").status());
        assertTrue(next.step("STEP1").failure().contains("DATA SET NOT FOUND"),
                next.step("STEP1").failure());
    }

    @Test
    @DisplayName("CATLG で残したものは名前だけで届く (FR-133)")
    void catlgPutsItOnTheCatalog() {
        write("IN.DAT", "AAAAA", 5);
        assertEquals(0, copyInto("(NEW,CATLG)").returnCode());

        assertEquals(JobRunner.Status.EXECUTED, readBack("").step("STEP1").status());
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("AAAAA"));
    }

    @Test
    @DisplayName("UNCATLG は目録から外すだけで、置き場には残る (FR-133)")
    void uncatlgTakesItOffButLeavesTheBytes() {
        write("IN.DAT", "AAAAA", 5);
        assertEquals(0, copyInto("(NEW,CATLG)").returnCode());

        assertEquals(0, run(
                "//K        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEFBR14",
                "//DROP     DD   DSN=OUT.DAT,DISP=(OLD,UNCATLG)").returnCode());

        assertTrue(onVolume("OUT.DAT"), "外したのは目録の項目だけである");
        assertEquals(JobRunner.Status.FAILED, readBack("").step("STEP1").status());
    }

    @Test
    @DisplayName("VOL=SER= を書けば目録に載っていなくても届く (FR-133、暫定判断 P-045)")
    void aVolumeSerialReachesAnUncatalogedDataSet() {
        write("IN.DAT", "AAAAA", 5);
        assertEquals(0, copyInto("(NEW,KEEP)").returnCode());

        // 目録を通さず置き場を直に見る。ホストで VOL=SER を書かねばならないのと同じである
        assertEquals(JobRunner.Status.EXECUTED,
                readBack(",VOL=SER=WORK01").step("STEP1").status());
    }

    @Test
    @DisplayName("DELETE は置き場からも目録からも消す (FR-133)")
    void deleteRemovesBoth() {
        write("IN.DAT", "AAAAA", 5);
        assertEquals(0, copyInto("(NEW,CATLG)").returnCode());
        assertEquals(0, run(
                "//K        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEFBR14",
                "//DROP     DD   DSN=OUT.DAT,DISP=(OLD,DELETE)").returnCode());

        assertFalse(onVolume("OUT.DAT"));
        // 目録から外れているので、作り直せる。項目が残っていれば「あるのに無い」になる
        assertEquals(0, copyInto("(NEW,CATLG)").returnCode());
    }

    // ---- ジョブの中と、ジョブをまたいだとき ----

    @Test
    @DisplayName("同じジョブの後続ステップは、目録を通さずに引ける (FR-131)")
    void theSameJobSeesWhatItAllocated() {
        write("IN.DAT", "AAAAA", 5);

        // KEEP なので目録には載らない。それでも同じジョブの中では見える
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=IN.DAT,DISP=SHR",
                "//SYSUT2   DD   DSN=OUT.DAT,DISP=(NEW,KEEP)",
                "//STEP2    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=OUT.DAT,DISP=SHR",
                "//SYSUT2   DD   SYSOUT=*");

        assertEquals(0, result.returnCode());
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("AAAAA"));
    }

    @Test
    @DisplayName("置き場にあれば、目録に覚えのない名前は載っているものとして扱う (FR-131)")
    void anUnknownNameOnTheVolumeIsTreatedAsCataloged() {
        // 人が置いたデータセットである。目録に書かせるのでは道具として使えない
        write("HAND.DAT", "AAAAA", 5);

        assertEquals(0, run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=HAND.DAT,DISP=SHR",
                "//SYSUT2   DD   SYSOUT=*").returnCode());
    }

    @Test
    @DisplayName("NEW は目録ではなく置き場を見る (FR-133)")
    void aNewDataSetCannotTakeAnOccupiedPlace() {
        write("IN.DAT", "AAAAA", 5);
        assertEquals(0, copyInto("(NEW,KEEP)").returnCode());

        // 目録には載っていないが、場所は塞がっている
        JobRunner.Result again = copyInto("(NEW,KEEP)");
        assertEquals(JobRunner.Status.FAILED, again.step("STEP1").status());
        assertTrue(again.step("STEP1").failure().contains("DUPLICATE NAME"),
                again.step("STEP1").failure());
    }
}
