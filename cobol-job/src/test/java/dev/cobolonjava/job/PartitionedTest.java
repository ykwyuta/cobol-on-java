package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.abend.AbendCode;
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
 * 区分データセットとメンバ (要件 FR-113、暫定判断 P-048 の解消)。
 *
 * <p>ライブラリはディレクトリ、メンバはその下のファイルである。ここで確かめたいのは
 * <b>割当てと {@code OPEN} の役割が分かれている</b>ことである。ライブラリがあるかどうかは
 * 割当ての段で分かるが、メンバがあるかどうかは分からない。ホストも同じで、
 * 無いメンバは {@code S013} になる。
 *
 * <p>違いは重い。割当てに失敗したステップは<b>動かない</b> (JCL エラー) ので、以降の
 * ステップまで流される。開けなかったステップは<b>動いてから止まる</b>ので、そこまでに
 * 書いたものが残り、{@code IF (STEP.ABENDCC = S013)} で受けられる。
 */
@Tag("V1")
class PartitionedTest {

    @TempDir
    Path directory;

    private ByteArrayOutputStream sink;

    private JobRunner.Result run(String... cards) {
        sink = new ByteArrayOutputStream();
        dev.cobolonjava.job.jcl.Jcl.Result parsed = dev.cobolonjava.job.jcl.Jcl.read(
                String.join("\n", cards));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        return JobRunner.at(directory.resolve("work"),
                        PartitionedTest.class.getClassLoader(), sink)
                .withBase(directory)
                .run(parsed.job());
    }

    /**
     * メンバを 1 つ持つライブラリを作る。
     *
     * <p>サイドカーはメンバの隣に置く。ライブラリの下でも置き場の下でも、
     * <b>データセット 1 つに覚え書き 1 つ</b>という形は変わらない。
     */
    private void member(String library, String name, String content) {
        try {
            Files.createDirectories(directory.resolve(library));
            Path path = directory.resolve(library).resolve(name);
            Files.write(path, CodePages.DEFAULT.encode(content));
            Files.writeString(Path.of(path + ".meta"),
                    "recfm=F\nlrecl=20\ncodepage=IBM-1047\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String output() {
        return sink.toString(StandardCharsets.UTF_8);
    }

    // ---- メンバを読む ----

    @Test
    @DisplayName("DSN=ライブラリ(メンバ) でメンバを読む (FR-113)")
    void aMemberIsReadFromALibrary() {
        member("MY.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=MY.LIB(PAYROLL),DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG)");

        assertEquals(JobRunner.Status.EXECUTED, result.step("STEP1").status());
        assertEquals("AAAAAAAAAAAAAAAAAAAA", CodePages.DEFAULT.decode(bytesOf("OUT.DAT")));
    }

    @Test
    @DisplayName("無いメンバは S013。割当ては通っている (FR-113, FR-141)")
    void aMissingMemberIsS013() {
        member("MY.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=MY.LIB(NOSUCH),DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG)");

        // 割当てが失敗したのなら FAILED になり、プログラムは呼ばれてすらいない。
        // ここではライブラリがあるので割当ては通り、開く段で止まっている
        assertEquals(JobRunner.Status.ABENDED, result.step("STEP1").status());
        assertEquals(AbendCode.S013, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("メンバを言わなければ区分データセットは開けない (FR-113, FR-141)")
    void aLibraryWithoutAMemberIsS013() {
        member("MY.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=MY.LIB,DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG)");

        assertEquals(AbendCode.S013, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("S013 は ABENDCC で受けられる (FR-131, FR-141)")
    void theOpenFailureCanBeCaught() {
        member("MY.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//BAD      EXEC PGM=COPYDD",
                "//INDD     DD   DSN=MY.LIB(NOSUCH),DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
                "//         IF (BAD.ABENDCC = S013) THEN",
                "//NOTE     EXEC PGM=SETRC,PARM='0'",
                "//         ENDIF");

        // 止まるだけでは足りない。想定した後始末が動かねば意味がない
        assertEquals(JobRunner.Status.EXECUTED, result.step("NOTE").status());
    }

    // ---- メンバを書く ----

    @Test
    @DisplayName("無いメンバでも書くのならよい。そこで作る (FR-113)")
    void writingCreatesTheMember() {
        member("MY.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=MY.LIB(PAYROLL),DISP=SHR",
                "//OUTDD    DD   DSN=MY.LIB(COPIED),DISP=SHR");

        assertEquals(JobRunner.Status.EXECUTED, result.step("STEP1").status());
        assertEquals("AAAAAAAAAAAAAAAAAAAA",
                CodePages.DEFAULT.decode(bytesOf("MY.LIB/COPIED")));
    }

    @Test
    @DisplayName("DISP=NEW はライブラリごと作る (FR-113, FR-133)")
    void aNewLibraryIsCreated() {
        member("MY.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");

        assertEquals(0, run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=MY.LIB(PAYROLL),DISP=SHR",
                "//OUTDD    DD   DSN=NEW.LIB(FIRST),DISP=(NEW,CATLG)").returnCode());

        assertTrue(Files.isDirectory(directory.resolve("NEW.LIB")));
        assertEquals("AAAAAAAAAAAAAAAAAAAA",
                CodePages.DEFAULT.decode(bytesOf("NEW.LIB/FIRST")));
    }

    @Test
    @DisplayName("メンバを消してもライブラリは残る (FR-113, FR-133)")
    void deletingAMemberLeavesTheLibrary() {
        member("MY.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");
        member("MY.LIB", "OTHER", "BBBBBBBBBBBBBBBBBBBB");

        assertEquals(0, run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEFBR14",
                "//DROP     DD   DSN=MY.LIB(OTHER),DISP=(OLD,DELETE)").returnCode());

        assertFalse(Files.exists(directory.resolve("MY.LIB/OTHER")));
        assertTrue(Files.exists(directory.resolve("MY.LIB/PAYROLL")),
                "目録が覚えているのはライブラリであってメンバではない");
    }

    // ---- 割当ての段で分かること ----

    @Test
    @DisplayName("順編成のデータセットにメンバは言えない (FR-113)")
    void aMemberOfASequentialDataSetIsAJclError() {
        try {
            Files.writeString(directory.resolve("FLAT.DAT"), "x", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=FLAT.DAT(X),DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG)");

        // これは割当ての段で分かる。ライブラリでないものにメンバは無い
        assertEquals(JobRunner.Status.FAILED, result.step("STEP1").status());
        assertTrue(result.step("STEP1").failure().contains("NOT A PARTITIONED DATA SET"),
                result.step("STEP1").failure());
    }

    @Test
    @DisplayName("ライブラリが無ければ割当てで止まる。開く段まで届かない (FR-113, FR-133)")
    void aMissingLibraryIsAJclError() {
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=NOSUCH.LIB(MEMBER),DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG)");

        assertEquals(JobRunner.Status.FAILED, result.step("STEP1").status());
        assertTrue(result.step("STEP1").failure().contains("DATA SET NOT FOUND"),
                result.step("STEP1").failure());
        assertTrue(output().isEmpty(), "プログラムは呼ばれてすらいない");
    }

    private byte[] bytesOf(String name) {
        try {
            return Files.readAllBytes(directory.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
