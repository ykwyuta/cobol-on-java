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
 * ライブラリを扱う道具 (要件 FR-113, FR-137、暫定判断 P-056 の解消)。
 *
 * <p>{@code IEBCOPY} と {@code LISTDS} は<b>ディレクトリを引く</b>初めての道具である。
 * いままでの道具は DD がデータセット 1 つを指していたので、何を触るのかが割当てで
 * 決まっていた。ここでは何を触るのかがライブラリの中身で決まる。
 *
 * <p>したがって<b>並び</b>が結果に出る。ライブラリを丸ごと写せば、写した先のバイト列が
 * 並びで変わる。
 */
@Tag("V1")
class LibraryUtilityTest {

    @TempDir
    Path directory;

    private ByteArrayOutputStream sink;

    private JobRunner.Result run(String... cards) {
        sink = new ByteArrayOutputStream();
        dev.cobolonjava.job.jcl.Jcl.Result parsed = dev.cobolonjava.job.jcl.Jcl.read(
                String.join("\n", cards));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        return JobRunner.at(directory.resolve("work"),
                        LibraryUtilityTest.class.getClassLoader(), sink)
                .withBase(directory)
                .run(parsed.job());
    }

    private String output() {
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    /** メンバを 1 つ置く。覚え書きはメンバの隣である。 */
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

    private String read(String library, String name) {
        try {
            return CodePages.DEFAULT.decode(
                    Files.readAllBytes(directory.resolve(library).resolve(name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean exists(String library, String name) {
        return Files.exists(directory.resolve(library).resolve(name));
    }

    /** {@code IEBCOPY} の骨組み。制御文だけを差し替える。 */
    private JobRunner.Result copy(String... control) {
        String[] cards = new String[] {
            "//J        JOB  (ACCT)",
            "//STEP1    EXEC PGM=IEBCOPY",
            "//SYSPRINT DD   SYSOUT=*",
            "//IN       DD   DSN=A.LIB,DISP=SHR",
            "//OUT      DD   DSN=B.LIB,DISP=(NEW,CATLG),SPACE=(TRK,(1,,2))",
            "//SYSIN    DD   *",
        };
        String[] all = new String[cards.length + control.length];
        System.arraycopy(cards, 0, all, 0, cards.length);
        System.arraycopy(control, 0, all, cards.length, control.length);
        return run(all);
    }

    // ---- 写す ----

    @Test
    @DisplayName("ライブラリを丸ごと写す (FR-113, FR-137)")
    void aLibraryIsCopiedWhole() {
        member("A.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");
        member("A.LIB", "TAXES", "BBBBBBBBBBBBBBBBBBBB");

        JobRunner.Result result = copy("  COPY INDD=IN,OUTDD=OUT");

        assertEquals(0, result.step("STEP1").returnCode());
        assertEquals("AAAAAAAAAAAAAAAAAAAA", read("B.LIB", "PAYROLL"));
        assertEquals("BBBBBBBBBBBBBBBBBBBB", read("B.LIB", "TAXES"));
    }

    /**
     * 並びはディレクトリの並びである。
     *
     * <p>EBCDIC では英字が数字より前なので {@code PAYA} が {@code PAY1} より先に写る。
     * Java の順で並べれば逆になり、<b>写した先のバイト列が実機と変わる</b>。
     */
    @Test
    @DisplayName("写す順はディレクトリの並びである。英字が数字より前 (FR-053, FR-113)")
    void membersAreCopiedInDirectoryOrder() {
        member("A.LIB", "PAY1", "AAAAAAAAAAAAAAAAAAAA");
        member("A.LIB", "PAYA", "BBBBBBBBBBBBBBBBBBBB");

        copy("  COPY INDD=IN,OUTDD=OUT");

        String log = output();
        assertTrue(log.indexOf("PAYA HAS BEEN") < log.indexOf("PAY1 HAS BEEN"), log);
    }

    /**
     * すでにあるメンバは置き換えない。
     *
     * <p>黙って上書きすると、月次で積み増していくライブラリが毎月まっさらになる。
     */
    @Test
    @DisplayName("すでにあるメンバは写さず、復帰コード 4 (FR-113, FR-137)")
    void anExistingMemberIsNotReplaced() {
        member("A.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");
        member("B.LIB", "PAYROLL", "OLDOLDOLDOLDOLDOLDO");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBCOPY",
                "//SYSPRINT DD   SYSOUT=*",
                "//IN       DD   DSN=A.LIB,DISP=SHR",
                "//OUT      DD   DSN=B.LIB,DISP=SHR",
                "//SYSIN    DD   *",
                "  COPY INDD=IN,OUTDD=OUT");

        assertEquals(4, result.step("STEP1").returnCode());
        assertEquals("OLDOLDOLDOLDOLDOLDO", read("B.LIB", "PAYROLL"));
        assertTrue(output().contains("IEB167I"), output());
    }

    @Test
    @DisplayName("INDD に R を書けば置き換える (FR-113, FR-137)")
    void theReplaceOptionOverwrites() {
        member("A.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");
        member("B.LIB", "PAYROLL", "OLDOLDOLDOLDOLDOLDO");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBCOPY",
                "//SYSPRINT DD   SYSOUT=*",
                "//IN       DD   DSN=A.LIB,DISP=SHR",
                "//OUT      DD   DSN=B.LIB,DISP=SHR",
                "//SYSIN    DD   *",
                "  COPY INDD=((IN,R)),OUTDD=OUT");

        assertEquals(0, result.step("STEP1").returnCode());
        assertEquals("AAAAAAAAAAAAAAAAAAAA", read("B.LIB", "PAYROLL"));
    }

    @Test
    @DisplayName("SELECT で選んだメンバだけを写す (FR-113, FR-137)")
    void selectCopiesOnlyTheNamedMembers() {
        member("A.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");
        member("A.LIB", "TAXES", "BBBBBBBBBBBBBBBBBBBB");

        JobRunner.Result result = copy(
                "  COPY INDD=IN,OUTDD=OUT",
                "  SELECT MEMBER=(PAYROLL)");

        assertEquals(0, result.step("STEP1").returnCode());
        assertTrue(exists("B.LIB", "PAYROLL"));
        assertFalse(exists("B.LIB", "TAXES"));
    }

    @Test
    @DisplayName("SELECT は名前を変えて写せる (FR-113, FR-137)")
    void selectCanRenameAMember() {
        member("A.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");

        JobRunner.Result result = copy(
                "  COPY INDD=IN,OUTDD=OUT",
                "  SELECT MEMBER=((PAYROLL,NEWPAY))");

        assertEquals(0, result.step("STEP1").returnCode());
        assertEquals("AAAAAAAAAAAAAAAAAAAA", read("B.LIB", "NEWPAY"));
        assertFalse(exists("B.LIB", "PAYROLL"));
    }

    @Test
    @DisplayName("EXCLUDE は 1 つだけ残して写す (FR-113, FR-137)")
    void excludeLeavesAMemberBehind() {
        member("A.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");
        member("A.LIB", "TAXES", "BBBBBBBBBBBBBBBBBBBB");

        JobRunner.Result result = copy(
                "  COPY INDD=IN,OUTDD=OUT",
                "  EXCLUDE MEMBER=(TAXES)");

        assertEquals(0, result.step("STEP1").returnCode());
        assertTrue(exists("B.LIB", "PAYROLL"));
        assertFalse(exists("B.LIB", "TAXES"));
    }

    /**
     * 選んだメンバが無ければ知らせる。
     *
     * <p>黙って 0 で終われば、名前を打ち間違えたジョブが<b>何も写さずに成功する</b>。
     */
    @Test
    @DisplayName("選んだメンバが無ければ復帰コード 8 (FR-113, FR-137)")
    void aSelectedMemberThatIsNotThereIsEight() {
        member("A.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");

        JobRunner.Result result = copy(
                "  COPY INDD=IN,OUTDD=OUT",
                "  SELECT MEMBER=(NOSUCH)");

        assertEquals(8, result.step("STEP1").returnCode());
        assertTrue(output().contains("IEB166I"), output());
    }

    @Test
    @DisplayName("順編成のデータセットは写し元にできない (FR-113, FR-137)")
    void copyingFromASequentialDataSetIsTwelve() {
        member("A.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");
        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//MAKE     EXEC PGM=IEFBR14",
                "//D1       DD   DSN=FLAT.DAT,DISP=(NEW,CATLG)",
                "//STEP1    EXEC PGM=IEBCOPY",
                "//SYSPRINT DD   SYSOUT=*",
                "//IN       DD   DSN=FLAT.DAT,DISP=SHR",
                "//OUT      DD   DSN=A.LIB,DISP=SHR",
                "//SYSIN    DD   *",
                "  COPY INDD=IN,OUTDD=OUT");

        assertEquals(12, result.step("STEP1").returnCode());
        assertTrue(output().contains("IEB1084I"), output());
    }

    /**
     * 写し元と写し先が同じなら圧縮である。
     *
     * <p>写しとして扱えば、メンバを自分自身へ写して<b>中身が消える</b>。
     */
    @Test
    @DisplayName("同じライブラリへの COPY は圧縮であり、中身は残る (FR-113, FR-137)")
    void copyingALibraryOntoItselfIsACompress() {
        member("A.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBCOPY",
                "//SYSPRINT DD   SYSOUT=*",
                "//IN       DD   DSN=A.LIB,DISP=SHR",
                "//SYSIN    DD   *",
                "  COPY INDD=IN,OUTDD=IN");

        assertEquals(0, result.step("STEP1").returnCode());
        assertEquals("AAAAAAAAAAAAAAAAAAAA", read("A.LIB", "PAYROLL"));
    }

    /**
     * 割り当てた領域はライブラリに付いている。
     *
     * <p>メンバ 1 つずつではなく<b>合計</b>で見る。翻訳された資産と同じ検査を通す
     * (暫定判断 P-053)。
     */
    @Test
    @DisplayName("写し先の領域を越えれば SD37 (FR-141、P-053、P-052)")
    void theOutputLibraryHonoursItsSpace() {
        member("A.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");
        member("A.LIB", "TAXES", "BBBBBBBBBBBBBBBBBBBB");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBCOPY",
                "//SYSPRINT DD   SYSOUT=*",
                "//IN       DD   DSN=A.LIB,DISP=SHR",
                "//OUT      DD   DSN=B.LIB,DISP=(NEW,CATLG),SPACE=(10,(3,,2))",
                "//SYSIN    DD   *",
                "  COPY INDD=IN,OUTDD=OUT");

        assertEquals(JobRunner.Status.ABENDED, result.step("STEP1").status());
        assertEquals(AbendCode.SD37, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("覚え書きはメンバと一緒に写る (FR-110, FR-113)")
    void attributesTravelWithTheMember() {
        member("A.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");

        copy("  COPY INDD=IN,OUTDD=OUT");

        try {
            assertTrue(Files.readString(
                    Path.of(directory.resolve("B.LIB").resolve("PAYROLL") + ".meta"))
                    .contains("lrecl=20"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code COPYMOD} は写しではない。
     *
     * <p>ロードモジュールを組み直すものである。写しとして扱えば、<b>動かないモジュールが
     * 写って正常終了する</b>。
     */
    @Test
    @DisplayName("COPYMOD は写しとして扱わず誤りにする (FR-137、P-047)")
    void copymodIsReportedRatherThanCopied() {
        member("A.LIB", "PAYROLL", "AAAAAAAAAAAAAAAAAAAA");

        JobRunner.Result result = copy("  COPYMOD INDD=IN,OUTDD=OUT");

        assertEquals(12, result.step("STEP1").returnCode());
        assertFalse(exists("B.LIB", "PAYROLL"));
    }

    // ---- 一覧を出す ----

    private JobRunner.Result tso(String... control) {
        String[] cards = new String[] {
            "//J        JOB  (ACCT)",
            "//STEP1    EXEC PGM=IKJEFT01",
            "//SYSTSPRT DD   SYSOUT=*",
            "//SYSTSIN  DD   *",
        };
        String[] all = new String[cards.length + control.length];
        System.arraycopy(cards, 0, all, 0, cards.length);
        System.arraycopy(control, 0, all, cards.length, control.length);
        return run(all);
    }

    @Test
    @DisplayName("LISTDS MEMBERS はディレクトリの並びで出す (FR-053, FR-113)")
    void listdsShowsMembersInDirectoryOrder() {
        member("A.LIB", "PAY1", "AAAAAAAAAAAAAAAAAAAA");
        member("A.LIB", "PAYA", "BBBBBBBBBBBBBBBBBBBB");

        JobRunner.Result result = tso("  LISTDS 'A.LIB' MEMBERS");

        assertEquals(0, result.step("STEP1").returnCode());
        String log = output();
        assertTrue(log.contains("--MEMBERS--"), log);
        assertTrue(log.indexOf("PAYA") < log.indexOf("PAY1"), log);
    }

    /**
     * 知らないコマンドは黙って飛ばさない。
     *
     * <p>飛ばして 0 で終われば、<b>何もしていないステップが成功したことになる</b>。
     */
    @Test
    @DisplayName("知らない TSO コマンドは復帰コード 12 (FR-137)")
    void anUnknownTsoCommandIsTwelve() {
        JobRunner.Result result = tso("  DSN SYSTEM(DB2P)");

        assertEquals(12, result.step("STEP1").returnCode());
        assertTrue(output().contains("IKJ56500I"), output());
    }

    @Test
    @DisplayName("目録に無い名前の LISTDS は復帰コード 12 (FR-131, FR-137)")
    void listdsOfAMissingDataSetIsTwelve() {
        JobRunner.Result result = tso("  LISTDS 'NO.SUCH' MEMBERS");

        assertEquals(12, result.step("STEP1").returnCode());
        assertTrue(output().contains("IKJ58503I"), output());
    }

    // ---- メンバ名 ----

    /**
     * 長すぎるメンバ名はジョブを読む段で弾く (暫定判断 P-056 の解消)。
     *
     * <p>通せば、実機なら JCL 誤りで動かないジョブがここでは動く。しかも 8 文字を越えた
     * 名前のファイルができるので、あとから実機へ戻せなくなる。
     */
    @Test
    @DisplayName("8 文字を越えるメンバ名は JCL 誤り (FR-113)")
    void aMemberNameLongerThanEightIsAJclError() {
        dev.cobolonjava.job.jcl.Jcl.Result parsed = dev.cobolonjava.job.jcl.Jcl.read(String.join(
                "\n",
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEFBR14",
                "//D1       DD   DSN=MY.LIB(TOOLONGNAME),DISP=SHR"));

        assertFalse(parsed.succeeded());
        assertTrue(parsed.diagnostics().toString().contains("invalid member name"),
                parsed.diagnostics().toString());
    }

    @Test
    @DisplayName("使えない文字のメンバ名も JCL 誤り (FR-113)")
    void aMemberNameWithABadCharacterIsAJclError() {
        dev.cobolonjava.job.jcl.Jcl.Result parsed = dev.cobolonjava.job.jcl.Jcl.read(String.join(
                "\n",
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEFBR14",
                "//D1       DD   DSN=MY.LIB(PAY-ROLL),DISP=SHR"));

        assertFalse(parsed.succeeded());
    }
}
