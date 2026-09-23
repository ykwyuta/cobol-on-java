package dev.cobolonjava.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.abend.AbendCode;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.file.MemberStatistics;
import dev.cobolonjava.runtime.file.PartitionedDataSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ディレクトリの項目 — 別名と統計と入る数 (要件 FR-113、暫定判断 P-059 の解消)。
 *
 * <p>ここまでのディレクトリは<b>名前の並びだけ</b>だった。項目そのものは持っていない。
 * 3 つはひと続きの話である。
 *
 * <ul>
 *   <li>別名は<b>もう 1 つの項目</b>であって、もう 1 つのメンバではない</li>
 *   <li>統計は項目に<b>付いてくる</b>ものであって、メンバのバイト列の中には無い</li>
 *   <li>項目の大きさは統計の有無で変わり、それが<b>1 ブロックに入る数</b>を決める</li>
 * </ul>
 *
 * <p>だから統計を持たずにディレクトリブロックの数だけを決めることはできない。決めれば、
 * 統計を入れた日に<b>止まる場所が動く</b>。
 */
@Tag("V1")
class DirectoryEntryTest {

    @TempDir
    Path directory;

    private ByteArrayOutputStream sink;

    private JobRunner.Result run(String... cards) {
        sink = new ByteArrayOutputStream();
        dev.cobolonjava.job.jcl.Jcl.Result parsed = dev.cobolonjava.job.jcl.Jcl.read(
                String.join("\n", cards));
        assertTrue(parsed.succeeded(), () -> parsed.diagnostics().toString());
        return JobRunner.at(directory.resolve("work"),
                        DirectoryEntryTest.class.getClassLoader(), sink)
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

    /** ライブラリのディレクトリの大きさを覚え書きへ書く。作ったジョブが残したものである。 */
    private void directoryBlocks(String library, int blocks) {
        try {
            Files.createDirectories(directory.resolve(library));
            Files.writeString(Path.of(directory.resolve(library) + ".meta"),
                    "dirblks=" + blocks + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** ISPF の統計を置く。ホストでは編集した側が残すものである。 */
    private void statistics(String library, String name) {
        new MemberStatistics(1, 3, java.time.LocalDate.of(2024, 5, 1),
                java.time.LocalDateTime.of(2024, 6, 12, 9, 41), 120, 100, 8, "PAYDEV")
                .write(directory.resolve(library).resolve(name));
    }

    private String read(String library, String name) {
        try {
            return CodePages.DEFAULT.decode(
                    Files.readAllBytes(directory.resolve(library).resolve(name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 切れた別名も項目である。{@code Files.exists} は切れたリンクに偽を返す。 */
    private boolean entry(String library, String name) {
        return Files.exists(directory.resolve(library).resolve(name), LinkOption.NOFOLLOW_LINKS);
    }

    private boolean alias(String library, String name) {
        return PartitionedDataSet.alias(directory.resolve(library), name);
    }

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

    // ---- 別名を作る ----

    @Test
    @DisplayName("RENAME ... ALIAS は項目を足す。元のメンバは残る (FR-113)")
    void anAliasIsAnotherEntry() {
        member("PAY.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");

        assertEquals(0, tso("  RENAME 'PAY.LIB(PAYCALC)' (PAYOLD) ALIAS")
                .step("STEP1").returnCode());

        assertTrue(entry("PAY.LIB", "PAYCALC"), "元のメンバは残る");
        assertTrue(alias("PAY.LIB", "PAYOLD"));
        assertEquals("PAYCALC", PartitionedDataSet.aliasOf(directory.resolve("PAY.LIB"),
                "PAYOLD"));
    }

    @Test
    @DisplayName("別名は一覧にメンバとして出る (FR-053, FR-113)")
    void anAliasIsListedLikeAMember() {
        member("PAY.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");

        tso("  RENAME 'PAY.LIB(PAYCALC)' (PAYOLD) ALIAS",
            "  LISTDS 'PAY.LIB' MEMBERS");

        String log = output();
        assertTrue(log.contains("PAYCALC"), log);
        assertTrue(log.contains("PAYOLD"), log);
    }

    /**
     * 別名から読めることが要点である。
     *
     * <p>読む側は別名だと知らない。ホストでも、別名の項目はメンバと同じ位置を指しているので
     * 開いた側からは区別が付かない。
     */
    @Test
    @DisplayName("別名から読めば元のメンバの中身が出る (FR-113)")
    void anAliasReadsThroughToTheMember() {
        member("PAY.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");
        tso("  RENAME 'PAY.LIB(PAYCALC)' (PAYOLD) ALIAS");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=PAY.LIB(PAYOLD),DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG)");

        assertEquals(JobRunner.Status.EXECUTED, result.step("STEP1").status());
        assertEquals("AAAAAAAAAAAAAAAAAAAA", read(".", "OUT.DAT"));
    }

    @Test
    @DisplayName("メンバの名前を変えても別名は付いてこない (FR-113)")
    void renamingTheMemberLeavesTheAliasDangling() {
        member("PAY.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");
        tso("  RENAME 'PAY.LIB(PAYCALC)' (PAYOLD) ALIAS");
        assertEquals(0, tso("  RENAME 'PAY.LIB(PAYCALC)' (PAYNEW)").step("STEP1").returnCode());

        // 項目は残っている。指す先だけが無い
        assertTrue(entry("PAY.LIB", "PAYOLD"));
        assertFalse(Files.exists(directory.resolve("PAY.LIB/PAYOLD")));

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=PAY.LIB(PAYOLD),DISP=SHR",
                "//OUTDD    DD   DSN=OUT.DAT,DISP=(NEW,CATLG)");

        assertEquals(AbendCode.S013, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("覚え書きは名前と一緒に動く (FR-110, FR-113)")
    void theSidecarsFollowTheRename() {
        member("PAY.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");
        statistics("PAY.LIB", "PAYCALC");

        tso("  RENAME 'PAY.LIB(PAYCALC)' (PAYNEW)");

        assertTrue(Files.exists(directory.resolve("PAY.LIB/PAYNEW.meta")));
        assertNotNull(MemberStatistics.read(directory.resolve("PAY.LIB/PAYNEW")));
        assertFalse(Files.exists(directory.resolve("PAY.LIB/PAYCALC.stats")),
                "置いていくと、次に同じ名前で作った人のものになる");
    }

    @Test
    @DisplayName("別名を消してもメンバは残る (FR-113)")
    void deletingAnAliasKeepsTheMember() {
        member("PAY.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");
        tso("  RENAME 'PAY.LIB(PAYCALC)' (PAYOLD) ALIAS");

        assertEquals(0, tso("  DELETE 'PAY.LIB(PAYOLD)'").step("STEP1").returnCode());

        assertFalse(entry("PAY.LIB", "PAYOLD"));
        assertEquals("AAAAAAAAAAAAAAAAAAAA", read("PAY.LIB", "PAYCALC"));
    }

    @Test
    @DisplayName("メンバを消しても別名の項目は残る (FR-113)")
    void deletingTheMemberKeepsTheAliasEntry() {
        member("PAY.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");
        tso("  RENAME 'PAY.LIB(PAYCALC)' (PAYOLD) ALIAS");

        assertEquals(0, tso("  DELETE 'PAY.LIB(PAYCALC)'").step("STEP1").returnCode());

        assertTrue(entry("PAY.LIB", "PAYOLD"), "ディレクトリの項目は消えない");
        assertNull(PartitionedDataSet.aliasOf(directory.resolve("PAY.LIB"), "PAYCALC"));
    }

    @Test
    @DisplayName("同じ名前へは変えられない (FR-113)")
    void renamingOntoAnExistingNameIsRefused() {
        member("PAY.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");
        member("PAY.LIB", "TAXES", "BBBBBBBBBBBBBBBBBBBB");

        assertEquals(12, tso("  RENAME 'PAY.LIB(PAYCALC)' (TAXES)").step("STEP1").returnCode());
        assertEquals("AAAAAAAAAAAAAAAAAAAA", read("PAY.LIB", "PAYCALC"));
        assertEquals("BBBBBBBBBBBBBBBBBBBB", read("PAY.LIB", "TAXES"));
    }

    @Test
    @DisplayName("無いメンバは変えられない (FR-113)")
    void renamingAMissingMemberIsTwelve() {
        member("PAY.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");

        assertEquals(12, tso("  RENAME 'PAY.LIB(NOSUCH)' (OTHER)").step("STEP1").returnCode());
        assertTrue(output().contains("NOT FOUND"), output());
    }

    @Test
    @DisplayName("メンバを書かなければライブラリそのものの名前を変える。メンバは付いてくる (P-058)")
    void renamingALibraryMovesItsMembers() {
        member("PAY.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");

        assertEquals(0, tso("  RENAME 'PAY.LIB' 'NEW.LIB'").step("STEP1").returnCode(), output());
        assertEquals("AAAAAAAAAAAAAAAAAAAA", read("NEW.LIB", "PAYCALC"));
        // 新しい名前が既にあれば変えない
        member("OLD.LIB", "X", "BBBBBBBBBBBBBBBBBBBB");
        assertEquals(12, tso("  RENAME 'OLD.LIB' 'NEW.LIB'").step("STEP1").returnCode());
    }

    // ---- 別名を写す ----

    @Test
    @DisplayName("丸ごと写せば別名は別名のまま写る (FR-113, FR-137)")
    void anAliasIsCopiedAsAnAlias() {
        member("A.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");
        tso("  RENAME 'A.LIB(PAYCALC)' (PAYOLD) ALIAS");

        assertEquals(0, copy("  COPY INDD=IN,OUTDD=OUT").step("STEP1").returnCode());

        assertTrue(alias("B.LIB", "PAYOLD"), "中身を写すと同じバイト列が 2 つになる");
        assertEquals("PAYCALC", PartitionedDataSet.aliasOf(directory.resolve("B.LIB"), "PAYOLD"));
    }

    @Test
    @DisplayName("別名だけを選べば中身が写り、普通のメンバになる (FR-113, FR-137)")
    void selectingOnlyTheAliasCopiesTheData() {
        member("A.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");
        tso("  RENAME 'A.LIB(PAYCALC)' (PAYOLD) ALIAS");

        assertEquals(0, copy("  COPY INDD=IN,OUTDD=OUT",
                "  SELECT MEMBER=(PAYOLD)").step("STEP1").returnCode());

        // 指す先が写っていないのだから、別名にしようがない
        assertFalse(alias("B.LIB", "PAYOLD"));
        assertEquals("AAAAAAAAAAAAAAAAAAAA", read("B.LIB", "PAYOLD"));
    }

    @Test
    @DisplayName("COPYGRP はメンバと別名をひと組で写す (FR-113, FR-137)")
    void copygrpBringsTheAliasesAlong() {
        member("A.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");
        member("A.LIB", "TAXES", "BBBBBBBBBBBBBBBBBBBB");
        tso("  RENAME 'A.LIB(PAYCALC)' (PAYOLD) ALIAS");

        assertEquals(0, copy("  COPYGRP INDD=IN,OUTDD=OUT",
                "  SELECT MEMBER=(PAYCALC)").step("STEP1").returnCode());

        assertTrue(alias("B.LIB", "PAYOLD"));
        assertFalse(entry("B.LIB", "TAXES"), "選んでいないメンバは写らない");
    }

    @Test
    @DisplayName("SELECT でメンバだけを選ぶと別名は置いていかれる (FR-113, FR-137)")
    void selectWithoutCopygrpLeavesTheAlias() {
        member("A.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");
        tso("  RENAME 'A.LIB(PAYCALC)' (PAYOLD) ALIAS");

        copy("  COPY INDD=IN,OUTDD=OUT", "  SELECT MEMBER=(PAYCALC)");

        assertFalse(entry("B.LIB", "PAYOLD"), "写した先で古い名前から引けなくなる");
    }

    // ---- 統計 ----

    @Test
    @DisplayName("統計は写しで持ち越される。写しは編集ではない (FR-113, FR-137)")
    void statisticsSurviveACopy() {
        member("A.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");
        statistics("A.LIB", "PAYCALC");

        copy("  COPY INDD=IN,OUTDD=OUT");

        MemberStatistics copied = MemberStatistics.read(directory.resolve("B.LIB/PAYCALC"));
        assertNotNull(copied);
        assertEquals("01.03", copied.level());
        assertEquals(120, copied.currentLines());
        assertEquals("PAYDEV", copied.userId());
    }

    /**
     * プログラムが書いたメンバに統計は付かない。
     *
     * <p>ホストでも同じである。ISPF の一覧で統計の欄が空のメンバが混じるのはこのためであり、
     * ここで作ってしまうと<b>実機には無いはずの統計</b>が生えることになる。
     */
    @Test
    @DisplayName("プログラムが書いたメンバに統計は付かない (FR-113)")
    void aMemberWrittenByAProgramHasNoStatistics() {
        member("A.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");

        run("//J        JOB  (ACCT)",
            "//STEP1    EXEC PGM=COPYDD",
            "//INDD     DD   DSN=A.LIB(PAYCALC),DISP=SHR",
            "//OUTDD    DD   DSN=A.LIB(FRESH),DISP=SHR");

        assertNull(MemberStatistics.read(directory.resolve("A.LIB/FRESH")));
    }

    @Test
    @DisplayName("統計を持たない元から写せば、写し先の古い統計も消える (FR-113)")
    void copyingWithoutStatisticsClearsTheOldOnes() {
        member("A.LIB", "PAYCALC", "AAAAAAAAAAAAAAAAAAAA");
        member("B.LIB", "PAYCALC", "OLDOLDOLDOLDOLDOLDOL");
        statistics("B.LIB", "PAYCALC");

        run("//J        JOB  (ACCT)",
            "//STEP1    EXEC PGM=IEBCOPY",
            "//SYSPRINT DD   SYSOUT=*",
            "//IN       DD   DSN=A.LIB,DISP=SHR",
            "//OUT      DD   DSN=B.LIB,DISP=SHR",
            "//SYSIN    DD   *",
            "  COPY INDD=((IN,R)),OUTDD=OUT");

        assertEquals("AAAAAAAAAAAAAAAAAAAA", read("B.LIB", "PAYCALC"));
        assertNull(MemberStatistics.read(directory.resolve("B.LIB/PAYCALC")),
                "中身は新しいのに統計は前のまま、では取り違えになる");
    }

    // ---- ディレクトリを使い切る ----

    /**
     * 1 ブロック 256 バイト、項目 12 バイト、先頭 2 バイトは使った長さ。
     * したがって統計を持たない項目は 21 個入る。
     */
    @Test
    @DisplayName("ディレクトリブロック 1 つに入るのは 21 項目 (FR-113, FR-141)")
    void oneBlockHoldsTwentyOneEntries() {
        directoryBlocks("A.LIB", 1);
        for (int i = 0; i < 21; i++) {
            member("A.LIB", "M" + (char) ('A' + i), "AAAAAAAAAAAAAAAAAAAA");
        }

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=A.LIB(MA),DISP=SHR",
                "//OUTDD    DD   DSN=A.LIB(MORE),DISP=SHR");

        assertEquals(AbendCode.S013, result.step("STEP1").abendCode());
        assertFalse(entry("A.LIB", "MORE"), "入りきらなかったメンバは残らない");
    }

    @Test
    @DisplayName("20 項目なら 22 個目が入る (FR-113, FR-141)")
    void oneMoreFitsWhileThereIsRoom() {
        directoryBlocks("A.LIB", 1);
        for (int i = 0; i < 20; i++) {
            member("A.LIB", "M" + (char) ('A' + i), "AAAAAAAAAAAAAAAAAAAA");
        }

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=A.LIB(MA),DISP=SHR",
                "//OUTDD    DD   DSN=A.LIB(MORE),DISP=SHR");

        assertEquals(JobRunner.Status.EXECUTED, result.step("STEP1").status());
    }

    /**
     * 統計が付くと項目は 42 バイトになり、1 ブロックに 6 個しか入らない。
     *
     * <p>これが「統計を持たずに数だけ決められない」ということである。同じ 6 個の
     * ライブラリが、統計の有無で<b>入るとも入らないとも</b>言える。
     */
    @Test
    @DisplayName("統計が付くと 1 ブロックには 6 項目しか入らない (FR-113, FR-141)")
    void statisticsShrinkTheDirectory() {
        directoryBlocks("A.LIB", 1);
        for (int i = 0; i < 6; i++) {
            member("A.LIB", "M" + (char) ('A' + i), "AAAAAAAAAAAAAAAAAAAA");
            statistics("A.LIB", "M" + (char) ('A' + i));
        }

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=A.LIB(MA),DISP=SHR",
                "//OUTDD    DD   DSN=A.LIB(MORE),DISP=SHR");

        assertEquals(AbendCode.S013, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("すでにある名前を書き直すのなら、いっぱいでも通る (FR-113, FR-141)")
    void replacingAMemberNeedsNoNewEntry() {
        directoryBlocks("A.LIB", 1);
        for (int i = 0; i < 21; i++) {
            member("A.LIB", "M" + (char) ('A' + i), "AAAAAAAAAAAAAAAAAAAA");
        }

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=A.LIB(MA),DISP=SHR",
                "//OUTDD    DD   DSN=A.LIB(MB),DISP=SHR");

        assertEquals(JobRunner.Status.EXECUTED, result.step("STEP1").status());
        assertEquals("AAAAAAAAAAAAAAAAAAAA", read("A.LIB", "MB"));
    }

    @Test
    @DisplayName("道具も同じ数で止まる。書き手で入る数は変わらない (FR-113, FR-137)")
    void theUtilityStopsAtTheSameCount() {
        directoryBlocks("B.LIB", 1);
        for (int i = 0; i < 21; i++) {
            member("B.LIB", "M" + (char) ('A' + i), "AAAAAAAAAAAAAAAAAAAA");
        }
        member("A.LIB", "NEWONE", "BBBBBBBBBBBBBBBBBBBB");

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEBCOPY",
                "//SYSPRINT DD   SYSOUT=*",
                "//IN       DD   DSN=A.LIB,DISP=SHR",
                "//OUT      DD   DSN=B.LIB,DISP=SHR",
                "//SYSIN    DD   *",
                "  COPY INDD=IN,OUTDD=OUT");

        assertEquals(AbendCode.S013, result.step("STEP1").abendCode());
    }

    /**
     * 大きさを書いたのは作ったジョブだけである。
     *
     * <p>あとからメンバを足すジョブは {@code DISP=SHR} で {@code SPACE=} を書かない。
     * 覚え書きへ残しておかなければ、そのジョブは限りなしで動く。
     */
    @Test
    @DisplayName("ディレクトリの大きさはジョブをまたいで残る (FR-113, FR-141)")
    void theDirectorySizeOutlivesTheJobThatMadeIt() {
        assertEquals(0, run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=IEFBR14",
                "//MAKE     DD   DSN=NEW.LIB,DISP=(NEW,CATLG),SPACE=(TRK,(1,,1))")
                .returnCode());
        for (int i = 0; i < 21; i++) {
            member("NEW.LIB", "M" + (char) ('A' + i), "AAAAAAAAAAAAAAAAAAAA");
        }

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=NEW.LIB(MA),DISP=SHR",
                "//OUTDD    DD   DSN=NEW.LIB(MORE),DISP=SHR");

        assertEquals(AbendCode.S013, result.step("STEP1").abendCode());
    }

    @Test
    @DisplayName("大きさを知らないライブラリは限りなしである (FR-113, FR-141)")
    void aLibraryWithoutASizeIsUnlimited() {
        for (int i = 0; i < 21; i++) {
            member("A.LIB", "M" + (char) ('A' + i), "AAAAAAAAAAAAAAAAAAAA");
        }

        JobRunner.Result result = run(
                "//J        JOB  (ACCT)",
                "//STEP1    EXEC PGM=COPYDD",
                "//INDD     DD   DSN=A.LIB(MA),DISP=SHR",
                "//OUTDD    DD   DSN=A.LIB(MORE),DISP=SHR");

        // 移行の途中で手で置いたライブラリがこれである。
        // 知らないものを勝手に決めて止めるよりは、通すほうがよい
        assertEquals(JobRunner.Status.EXECUTED, result.step("STEP1").status());
    }

    @Test
    @DisplayName("別名もディレクトリの項目を 1 つ使う (FR-113, FR-141)")
    void anAliasTakesADirectoryEntry() {
        directoryBlocks("A.LIB", 1);
        for (int i = 0; i < 21; i++) {
            member("A.LIB", "M" + (char) ('A' + i), "AAAAAAAAAAAAAAAAAAAA");
        }

        assertEquals(AbendCode.S013,
                tso("  RENAME 'A.LIB(MA)' (MZ) ALIAS").step("STEP1").abendCode());
    }
}
