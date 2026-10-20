package dev.cobolonjava.verify.suite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Zeus の検査スイートを流す道具そのものの検査 (CLAUDE.md §6)。
 *
 * <p>小さなスイートを作り、通る本、落ちる検査、途中で止まって書かれなかった検査、断るべき本、
 * 断るべきなのに通る本、終了コードを、道具が取り違えずに数えるかを見る。道具が失敗を作れば、
 * 数そのものが信用できなくなる。
 */
class CobolSuiteRunnerTest {

    @Test
    @DisplayName("manifest.toml のプログラムごとの条件を読み、知らない鍵は断る")
    void readsTheManifest() {
        SuiteManifest manifest = SuiteManifest.parse(String.join("\n",
                "[suite]",
                "name = \"ZCVS\"  # 注釈",
                "required_modules = [\"ZA\", \"ZE\"]",
                "optional_modules = [\"ZX\"]",
                "[programs.ZA101A]",
                "ccsid = 939",
                "parm = \"NIGHTLY,20261016\"",
                "expect_exit_code = 4",
                "[programs.ZE101N]",
                "expect = \"compile-error\"",
                "diagnostic_contains = \"does not # match\""));

        assertEquals(List.of("ZA", "ZE", "ZX"), manifest.modules());
        assertEquals(939, manifest.program("ZA101A").ccsid());
        assertEquals("NIGHTLY,20261016", manifest.program("ZA101A").parm());
        assertEquals(4, manifest.program("ZA101A").expectExitCode());
        assertEquals(SuiteManifest.Expect.COMPILE_ERROR, manifest.program("ZE101N").expect());
        assertEquals("does not # match", manifest.program("ZE101N").diagnosticContains(),
                "引用符の中の # は注釈ではない");
        assertEquals(SuiteManifest.Expect.RUN, manifest.program("ZZ999A").expect());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SuiteManifest.parse("[programs.X]\nretries = 3"));
    }

    @Test
    @DisplayName("coverage.json の検査の名前を並びのまま読む")
    void readsTheCoverage() {
        SuiteCoverage coverage = SuiteCoverage.parse(
                "{ \"ZA101A\": {\"expect\": \"run\", \"tests\": [\"T-ONE\", \"T-TWO\"]},"
                        + " \"ZE101N\": {\"expect\": \"compile-error\", \"tests\": []} }");

        assertEquals(List.of("T-ONE", "T-TWO"), coverage.testsOf("ZA101A"));
        assertEquals(List.of(), coverage.testsOf("ZE101N"));
    }

    @Test
    @DisplayName("通る本、落ちる検査、書かれなかった検査、断るべき本、終了コードを取り違えずに数える")
    void countsEachOutcomeOfASmallSuite(@TempDir Path suite) throws IOException {
        Files.writeString(suite.resolve("manifest.toml"), String.join("\n",
                "[suite]",
                "name = \"MINI\"",
                "required_modules = [\"ZA\", \"ZE\"]",
                "[programs.ZA103A]",
                "expect_exit_code = 4",
                "[programs.ZE101N]",
                "expect = \"compile-error\"",
                "diagnostic_contains = \"undefined\"",
                "[programs.ZE101P]",
                "expect = \"compile-error\""));
        Files.writeString(suite.resolve("coverage.json"), "{"
                + "\"ZA101A\": {\"expect\": \"run\", \"tests\": [\"T-ONE\", \"T-TWO\"]},"
                + "\"ZA102A\": {\"expect\": \"run\", \"tests\": [\"T-ONE\", \"T-TWO\", \"T-THREE\"]},"
                + "\"ZA103A\": {\"expect\": \"run\", \"tests\": [\"T-ONE\"]},"
                + "\"ZE101N\": {\"expect\": \"compile-error\", \"tests\": []},"
                + "\"ZE101P\": {\"expect\": \"compile-error\", \"tests\": []}}");
        Files.createDirectories(suite.resolve("copy"));
        Path za = Files.createDirectories(suite.resolve("ZA"));
        Path ze = Files.createDirectories(suite.resolve("ZE"));
        // 2 つとも通る
        Files.writeString(za.resolve("ZA101A.CBL"), program("ZA101A",
                "MOVE 'PASS ' TO R-STATUS MOVE 'T-ONE' TO R-NAME WRITE R",
                "MOVE 'PASS ' TO R-STATUS MOVE 'T-TWO' TO R-NAME WRITE R",
                "MOVE '  END OF TEST-2 ZA101A' TO R WRITE R"));
        // 1 つ落ち、1 つは書かずに終わる
        Files.writeString(za.resolve("ZA102A.CBL"), program("ZA102A",
                "MOVE 'PASS ' TO R-STATUS MOVE 'T-ONE' TO R-NAME WRITE R",
                "MOVE 'FAIL*' TO R-STATUS MOVE 'T-TWO' TO R-NAME WRITE R",
                "MOVE '  END OF TEST-2 ZA102A' TO R WRITE R"));
        // 終了コード 4 を期待されている
        Files.writeString(za.resolve("ZA103A.CBL"), program("ZA103A",
                "MOVE 'PASS ' TO R-STATUS MOVE 'T-ONE' TO R-NAME WRITE R",
                "MOVE '  END OF TEST-2 ZA103A' TO R WRITE R",
                "MOVE 4 TO RETURN-CODE"));
        // 断るべき (未定義の名前) と、断るべきなのに通るもの
        Files.writeString(ze.resolve("ZE101N.CBL"), program("ZE101N", "MOVE NOWHERE TO R"));
        Files.writeString(ze.resolve("ZE101P.CBL"), program("ZE101P", "MOVE SPACES TO R"));

        Map<String, SuiteOutcome> outcomes = new CobolSuiteRunner(suite).run(Set.of()).stream()
                .collect(Collectors.toMap(SuiteOutcome::program, Function.identity()));

        assertEquals(SuiteOutcome.Status.PASSED, outcomes.get("ZA101A").status());
        assertEquals(2, outcomes.get("ZA101A").passed());
        SuiteOutcome partial = outcomes.get("ZA102A");
        assertEquals(SuiteOutcome.Status.FAILED, partial.status());
        assertEquals(List.of(1, 1, 1), List.of(partial.passed(), partial.failed(), partial.missing()),
                "通った 1、落ちた 1、書かれなかった 1");
        assertEquals(SuiteOutcome.Status.PASSED, outcomes.get("ZA103A").status(), "期待した終了コード 4");
        assertEquals(SuiteOutcome.Status.PASSED, outcomes.get("ZE101N").status());
        assertTrue(outcomes.get("ZE101N").zeusWording());
        assertEquals(SuiteOutcome.Status.ACCEPTED_WRONGLY, outcomes.get("ZE101P").status());
        assertFalse(SuiteSummary.text("MINI", List.copyOf(outcomes.values())).isEmpty());
    }

    /** 報告を ASSIGN TO REPORT へ書く小さなプログラム。ZCVS の報告の行と同じ桁に置く。 */
    private static String program(String name, String... statements) {
        StringBuilder out = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. " + name + ".",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT REPORT-FILE ASSIGN TO REPORT.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  REPORT-FILE.",
                "01  R.",
                "    05  FILLER   PIC X(2).",
                "    05  R-STATUS PIC X(5).",
                "    05  FILLER   PIC X(5).",
                "    05  R-NAME   PIC X(20).",
                "    05  FILLER   PIC X(88).",
                "PROCEDURE DIVISION.",
                "    OPEN OUTPUT REPORT-FILE",
                "    MOVE SPACES TO R")) {
            out.append("       ").append(line).append('\n');
        }
        for (String statement : statements) {
            out.append("           ").append(statement).append('\n');
            out.append("           MOVE SPACES TO R\n");
        }
        out.append("           CLOSE REPORT-FILE\n");
        out.append("           GOBACK.\n");
        return out.toString();
    }
}
