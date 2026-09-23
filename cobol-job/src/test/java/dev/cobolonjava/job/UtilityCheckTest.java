package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * ユーティリティも翻訳された資産と同じ検査を通る (暫定判断 P-053 の解消)。
 *
 * <p>ユーティリティはデータセットを開かずに<b>生バイトをそのまま読む</b>。それを言い訳に
 * 検査を省くと、無いメンバを空として写し、半端なバイトが残ったデータセットを写して、
 * 割り当てた領域を越えて書く — そのどれもが<b>正常終了する</b>。実資産のジョブは
 * {@code IEBGENER} と {@code SORT} を骨組みにしているので、そこが素通しでは
 * ジョブ全体の合い方が変わってしまう。
 *
 * <p>立つコードは翻訳された資産と同じである。無いメンバは {@code S013}、形が壊れているのは
 * {@code S001}、二次割当の無い領域を使い切ったのは {@code SD37} である。
 */
@Tag("V1")
class UtilityCheckTest {

    @TempDir
    Path directory;

    private ByteArrayOutputStream sink;

    private JobRunner.Result run(String... cards) {
        sink = new ByteArrayOutputStream();
        dev.cobolonjava.job.jcl.Jcl.Result parsed = dev.cobolonjava.job.jcl.Jcl.read(
                String.join("\n", cards));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        return JobRunner.at(directory.resolve("work"),
                        UtilityCheckTest.class.getClassLoader(), sink)
                .withBase(directory)
                .run(parsed.job());
    }

    private String output() {
        return sink.toString(StandardCharsets.UTF_8);
    }

    /** 固定長のデータセットを置く。 */
    private void write(String name, String content, int length) {
        try {
            Path path = directory.resolve(name);
            Files.createDirectories(path.getParent());
            Files.write(path, CodePages.DEFAULT.encode(content));
            Files.writeString(Path.of(path + ".meta"),
                    "recfm=F\nlrecl=" + length + "\ncodepage=IBM-1047\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String read(String name) {
        try {
            return CodePages.DEFAULT.decode(Files.readAllBytes(directory.resolve(name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- 無いメンバ (要件 FR-113) ----

    @Test
    @DisplayName("IEBGENER に無いメンバを読ませれば S013 (FR-113)")
    void copyingAMissingMemberIsS013() {
        write("MY.LIB/PAYROLL", "AAAAABBBBB", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=MY.LIB(NOSUCH),DISP=SHR",
                "//SYSUT2   DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
                "//SYSPRINT DD   SYSOUT=*");

        // 「入力が無い」(RC=12) ではない。ライブラリはあるのだから割当ては通っている
        assertEquals(JobRunner.Status.ABENDED, result.step("STEP1").status());
        assertEquals(AbendCode.S013, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("IEBGENER にライブラリそのものを読ませれば S013 (FR-113)")
    void copyingALibraryItselfIsS013() {
        write("MY.LIB/PAYROLL", "AAAAABBBBB", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=MY.LIB,DISP=SHR",
                "//SYSUT2   DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
                "//SYSPRINT DD   SYSOUT=*");

        assertEquals(AbendCode.S013, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("ICETOOL に無いメンバを読ませれば S013 (FR-113, FR-137)")
    void icetoolAlsoChecksTheMember() {
        write("MY.LIB/PAYROLL", "AAAAABBBBB", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=ICETOOL",
                "//INDD     DD   DSN=MY.LIB(NOSUCH),DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
                "//TOOLMSG  DD   SYSOUT=*",
                "//TOOLIN   DD   *",
                "  COPY FROM(INDD) TO(OUTDD)",
                "/*");

        assertEquals(AbendCode.S013, result.step("STEP1").abendCode());
    }

    // ---- 壊れた形 (要件 FR-141) ----

    @Test
    @DisplayName("IEBGENER に半端なバイトの残るデータセットを写させれば S001 (FR-141)")
    void copyingADamagedDataSetIsS001() {
        // レコード長 5 に対して 8 バイトある。3 バイトはどのレコードにも属さない
        write("IN.DAT", "AAAAABBB", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=IN.DAT,DISP=SHR",
                "//SYSUT2   DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
                "//SYSPRINT DD   SYSOUT=*");

        // 黙って写して正常終了するのがいちばん困る
        assertEquals(JobRunner.Status.ABENDED, result.step("STEP1").status());
        assertEquals(AbendCode.S001, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("SORT に半端なバイトの残るデータセットを読ませれば S001 (FR-141)")
    void sortingADamagedDataSetIsS001() {
        write("IN.DAT", "AAAAABBB", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=SORT",
                "//SORTIN   DD   DSN=IN.DAT,DISP=SHR",
                "//SORTOUT  DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
                "//SYSOUT   DD   SYSOUT=*",
                "//SYSIN    DD   *",
                "  SORT FIELDS=(1,5,CH,A)",
                "/*");

        assertEquals(AbendCode.S001, result.step("STEP1").abendCode());
    }

    // ---- 領域の限り (要件 FR-141) ----

    @Test
    @DisplayName("区分データセットのメンバが二次割当も使い切れば SE37 (P-052)")
    void aMemberOutOfExtentsIsSe37() {
        write("IN.DAT", "A".repeat(85), 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=IN.DAT,DISP=SHR",
                "//SYSUT2   DD   DSN=OUT.LIB(MEM1),DISP=(NEW,CATLG),",
                "//              SPACE=(5,(1,1,1))",
                "//SYSPRINT DD   SYSOUT=*");

        // 5 バイト x 16 エクステント = 80 バイトへ 85 バイト
        assertEquals(AbendCode.SE37, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("IEBGENER が割り当てた領域に収まらなければ SD37 (FR-141、P-052)")
    void copyingPastTheAllocationIsSd37() {
        write("IN.DAT", "AAAAABBBBBCCCCC", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=IN.DAT,DISP=SHR",
                "//SYSUT2   DD   DSN=OUT.DAT,DISP=(NEW,CATLG),",
                "//              SPACE=(5,(2))",
                "//SYSPRINT DD   SYSOUT=*");

        // 10 バイトしか取っていないところへ 15 バイト写そうとしている
        assertEquals(AbendCode.SD37, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("二次割当があれば伸ばせるので写せる (FR-141)")
    void aSecondaryAllocationStillFits() {
        write("IN.DAT", "AAAAABBBBBCCCCC", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=IN.DAT,DISP=SHR",
                "//SYSUT2   DD   DSN=OUT.DAT,DISP=(NEW,CATLG),",
                "//              SPACE=(5,(2,1))",
                "//SYSPRINT DD   SYSOUT=*");

        assertEquals(0, result.returnCode());
        assertEquals("AAAAABBBBBCCCCC", read("OUT.DAT"));
    }

    // ---- 通るものは通る ----

    @Test
    @DisplayName("形の整ったメンバはそのまま写せる (FR-113, FR-137)")
    void asoundMemberIsStillCopied() {
        write("MY.LIB/PAYROLL", "AAAAABBBBB", 5);

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBGENER",
                "//SYSUT1   DD   DSN=MY.LIB(PAYROLL),DISP=SHR",
                "//SYSUT2   DD   DSN=OUT.DAT,DISP=(NEW,CATLG)",
                "//SYSPRINT DD   SYSOUT=*");

        assertEquals(0, result.returnCode());
        assertEquals("AAAAABBBBB", read("OUT.DAT"));
        assertTrue(output().contains("IEB147I 2 RECORDS COPIED"), output());
    }
}
