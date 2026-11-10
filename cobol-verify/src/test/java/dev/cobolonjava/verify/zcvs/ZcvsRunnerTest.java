package dev.cobolonjava.verify.zcvs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ZCVS を流す道具そのものの検査。
 *
 * <p>ZCVS は同梱しない (GPL-3.0-or-later)。ここで流すのは<b>報告の形だけを真似た</b>
 * 自前の小さな置き場である。道具が作った失敗を処理系の失敗と読み違えないために、
 * 道具にも検査を仕込む (覚え書き 6)。
 */
@Tag("V1")
class ZcvsRunnerTest {

    /** 判定を 1 つ通し、1 つ落とし (または落とさず)、集計を書くプログラム。 */
    private static String program(String name, boolean failing, boolean finishing) {
        String failed = failing ? "0001" : "0000";
        String total = failing ? "0002" : "0001";
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + name + ".",
                "       ENVIRONMENT DIVISION.",
                "       INPUT-OUTPUT SECTION.",
                "       FILE-CONTROL.",
                "           SELECT REPORT-FILE ASSIGN TO REPORT.",
                "       DATA DIVISION.",
                "       FILE SECTION.",
                "       FD  REPORT-FILE RECORDING MODE F.",
                "       01  REPORT-RECORD PIC X(120).",
                "       WORKING-STORAGE SECTION.",
                "           COPY TINYRPT.",
                "       PROCEDURE DIVISION.",
                "       MAIN-000.",
                "           OPEN OUTPUT REPORT-FILE.",
                "           MOVE '  PASS      FIRST-CHECK' TO REPORT-RECORD.",
                "           WRITE REPORT-RECORD.",
                failing ? "           WRITE REPORT-RECORD FROM L-FAIL." : "",
                finishing ? "" : "           CLOSE REPORT-FILE. STOP RUN.",
                "           MOVE SPACES TO REPORT-RECORD.",
                "           STRING '  0001 OF " + total + "'",
                "             ' TESTS WERE EXECUTED SUCCESSFULLY'",
                "             DELIMITED BY SIZE INTO REPORT-RECORD.",
                "           WRITE REPORT-RECORD.",
                "           MOVE '  " + failed + " TEST(S) FAILED' TO REPORT-RECORD.",
                "           WRITE REPORT-RECORD.",
                "           MOVE '  END OF TEST-2 " + name + "' TO REPORT-RECORD.",
                "           WRITE REPORT-RECORD.",
                "           CLOSE REPORT-FILE.",
                "           STOP RUN.",
                "");
    }

    private static final String COPYBOOK = String.join("\n",
            "       01  L-FAIL.",
            "           05  FILLER PIC X(12) VALUE '  FAIL*'.",
            "           05  FILLER PIC X(22) VALUE 'SECOND-CHECK'.",
            "           05  FILLER PIC X(86) VALUE 'EXP=A ACT=B'.",
            "");

    private static List<ZcvsRunner.Outcome> run(Path root) throws IOException {
        Files.createDirectories(root.resolve("copy"));
        Files.createDirectories(root.resolve("ZT"));
        Files.writeString(root.resolve("copy/TINYRPT.CBL"), COPYBOOK);
        Files.writeString(root.resolve("ZT/ZT001A.CBL"), program("ZT001A", false, true));
        Files.writeString(root.resolve("ZT/ZT002A.CBL"), program("ZT002A", true, true));
        Files.writeString(root.resolve("ZT/ZT003A.CBL"), program("ZT003A", false, false));
        Files.writeString(root.resolve("ZT/ZT004N.CBL"),
                "       IDENTIFICATION DIVISION.\n       PROGRAM-ID. ZT004N.\n"
                        + "       PROCEDURE DIVISION.\n           MOVE 1 TO NOWHERE.\n");
        Files.writeString(root.resolve("ZT/ZT005N.CBL"),
                "       IDENTIFICATION DIVISION.\n       PROGRAM-ID. ZT005N.\n"
                        + "       PROCEDURE DIVISION.\n           STOP RUN.\n");
        Files.writeString(root.resolve("ZT/ZT006P.CBL"),
                "       IDENTIFICATION DIVISION.\n       PROGRAM-ID. ZT006P.\n"
                        + "       PROCEDURE DIVISION.\n           STOP RUN.\n");
        Files.writeString(root.resolve("manifest.toml"), String.join("\n",
                "[suite]", "name = \"TINY\"", "",
                "[programs.ZT004N]", "expect = \"compile-error\"",
                "diagnostic_contains = \"undefined\"", "",
                "[programs.ZT005N]", "expect = \"compile-error\"", "",
                "[programs.ZT006P]", "expect = \"compile-only\"", ""));
        return new ZcvsRunner(root).run(Set.of());
    }

    private static ZcvsRunner.Outcome of(List<ZcvsRunner.Outcome> outcomes, String program) {
        return ZcvsRunner.find(outcomes, program).orElseThrow();
    }

    @Test
    @DisplayName("集計はプログラムが自分で書いた行から読み、写し句は置き場の copy から引く")
    void readsTheSummaryWrittenByTheProgram(@TempDir Path root) throws IOException {
        List<ZcvsRunner.Outcome> outcomes = run(root);
        ZcvsRunner.Outcome passed = of(outcomes, "ZT001A");
        assertEquals(ZcvsRunner.Kind.PASSED, passed.kind(), passed.reason());
        assertEquals(1, passed.total());
        ZcvsRunner.Outcome failed = of(outcomes, "ZT002A");
        assertEquals(ZcvsRunner.Kind.FAILED, failed.kind(), failed.reason());
        assertEquals(1, failed.passed());
        assertEquals(2, failed.total());
        assertEquals(List.of("SECOND-CHECK  EXP=A ACT=B"), failed.failures());
    }

    @Test
    @DisplayName("最後まで行かなかった本は、落ちた判定が無くても合格に数えない")
    void anUnfinishedReportIsNotAPass(@TempDir Path root) throws IOException {
        assertEquals(ZcvsRunner.Kind.CRASHED, of(run(root), "ZT003A").kind());
    }

    @Test
    @DisplayName("断るはずの本は断れば合格、翻訳してしまえば不合格である")
    void negativeProgramsMustBeRejected(@TempDir Path root) throws IOException {
        List<ZcvsRunner.Outcome> outcomes = run(root);
        assertEquals(ZcvsRunner.Kind.PASSED, of(outcomes, "ZT004N").kind());
        assertEquals(ZcvsRunner.Kind.NOT_REJECTED, of(outcomes, "ZT005N").kind());
        assertEquals(ZcvsRunner.Kind.PASSED, of(outcomes, "ZT006P").kind());
        assertTrue(ZcvsRunner.text(outcomes).contains("ZT005N"));
    }
}
