package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.FileOperationException;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.program.ProgramNotFoundException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code CALL} による副プログラムの呼び出し (要件 FR-080, FR-081, FR-083)。
 *
 * <p>呼ぶ側と呼ばれる側を両方翻訳し、<b>同じクラスローダへ読み込んで</b>実行する。
 * 呼び先を探すのは実行時であり、生成クラスが同じところに置かれていることが前提である。
 */
@Tag("V1")
class CallGenerationTest {

    /** 呼ぶ側と呼ばれる側を一緒に読み込む。 */
    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(CallGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    /** プログラム 1 本のソース。行は自動で 7 桁目から始める。 */
    private static String source(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("       ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static CobolCompiler.Result compile(List<String> lines) {
        return CobolCompiler.standard().compile("PGM.cbl", source(lines));
    }

    /**
     * 複数のプログラムを翻訳して同じローダへ読み込み、最初のものを実行する。
     *
     * @return {@code DISPLAY} の出力
     */
    private static String run(List<List<String>> programs) {
        GeneratedLoader loader = new GeneratedLoader();
        List<CobolProgram> loaded = new ArrayList<>();
        for (List<String> lines : programs) {
            CobolCompiler.Result result = compile(lines);
            assertTrue(result.succeeded(),
                    () -> "unexpected diagnostics: " + result.diagnostics());
            // 1 本のソースにプログラムが何本あってもよい。ぜんぶ読み込む
            for (CobolCompiler.Compiled program : result.programs()) {
                try {
                    Class<?> type = loader.define(program.className(), program.classFile());
                    loaded.add((CobolProgram) type.getDeclaredConstructor().newInstance());
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("cannot load the generated program", e);
                }
            }
        }
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        loaded.get(0).runFresh(ProgramContext.capturing(sink));
        return sink.toString(StandardCharsets.UTF_8);
    }

    /** 引数を書き換えて戻る副プログラム。 */
    private static final List<String> SUB_WRITES = List.of(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. SUBA.",
            "DATA DIVISION.",
            "LINKAGE SECTION.",
            "01 LK-A PIC X(3).",
            "PROCEDURE DIVISION USING LK-A.",
            "MAIN-START.",
            "    MOVE 'xyz' TO LK-A",
            "    GOBACK.");

    /** EXTERNAL の領域だけを書き換えて戻る副プログラム。引数は取らない。 */
    private static final List<String> SUB_EXTERNAL = List.of(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. SUBEXT.",
            "DATA DIVISION.",
            "WORKING-STORAGE SECTION.",
            "01 SHARED-DATA IS EXTERNAL.",
            "   03 SH-TEXT PIC X(2).",
            "   03 SH-NUM  PIC 9(4).",
            "PROCEDURE DIVISION.",
            "MAIN-START.",
            "    MOVE 'ZZ' TO SH-TEXT",
            "    ADD 10 TO SH-NUM",
            "    GOBACK.");

    /** 囲む側の GLOBAL 項目を、囲まれた側が書き換えて戻る。 */
    private static final List<String> NESTED_GLOBAL = List.of(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. GLOMAIN.",
            "DATA DIVISION.",
            "WORKING-STORAGE SECTION.",
            "01 SHARED-DATA IS GLOBAL.",
            "   03 SH-TEXT PIC X(2).",
            "   03 SH-NUM  PIC 9(4).",
            "PROCEDURE DIVISION.",
            "MAIN-START.",
            "    MOVE 'AA' TO SH-TEXT",
            "    MOVE 1 TO SH-NUM",
            "    CALL 'GLOSUB'",
            "    DISPLAY SH-TEXT SH-NUM",
            "    STOP RUN.",
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. GLOSUB.",
            "PROCEDURE DIVISION.",
            "SUB-START.",
            "    MOVE 'ZZ' TO SH-TEXT",
            "    ADD 10 TO SH-NUM",
            "    GOBACK.",
            "END PROGRAM GLOSUB.",
            "END PROGRAM GLOMAIN.");

    @Test
    @DisplayName("囲む側の GLOBAL 項目は、囲まれた側から見える (FR-091)")
    void acontainedProgramSeesTheGlobalItemsOfItsContainer() {
        // GLOSUB は SHARED-DATA を宣言していない。囲む GLOMAIN が GLOBAL と書いたので
        // 見えている。実体は 1 つなので、書き換えは戻ったところで見える
        assertEquals("ZZ0011", run(List.of(NESTED_GLOBAL)).trim());
    }

    @Test
    @DisplayName("GLOBAL と書かなければ、囲まれた側からは見えない (FR-091)")
    void withoutTheGlobalClauseAcontainedProgramCannotSeeTheItem() {
        // 見えないものを使えば<b>翻訳が通らない</b>。黙って別の項目を使うより良い
        List<String> lines = new ArrayList<>(NESTED_GLOBAL);
        lines.set(lines.indexOf("01 SHARED-DATA IS GLOBAL."), "01 SHARED-DATA.");
        CobolCompiler.Result result = compile(lines);

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("SH-TEXT"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("並んだプログラムでは GLOBAL は見えない (FR-091)")
    void asiblingProgramDoesNotSeeTheGlobalItem() {
        // 入れ子でなければ引き継がない。END PROGRAM の位置だけが違う —
        // GLOMAIN を閉じてから GLOSUB を始めれば、2 本は並んだ関係になる
        List<String> lines = new ArrayList<>(NESTED_GLOBAL);
        lines.remove("END PROGRAM GLOSUB.");
        lines.remove("END PROGRAM GLOMAIN.");
        lines.add(lines.lastIndexOf("IDENTIFICATION DIVISION."), "END PROGRAM GLOMAIN.");
        lines.add("END PROGRAM GLOSUB.");
        CobolCompiler.Result result = compile(lines);

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("SH-TEXT"),
                result.diagnostics().toString());
    }

    /**
     * 囲む側が {@code FD ... GLOBAL} と {@code USE GLOBAL} を持ち、囲まれた側が
     * そのファイルを読む。読む先が無いので {@code AT END} になり、受け止め手が
     * 無いので囲む側の宣言節が動く。
     */
    private static List<String> nestedFile(String use) {
        return List.of(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. GFMAIN.",
            "ENVIRONMENT DIVISION.",
            "INPUT-OUTPUT SECTION.",
            "FILE-CONTROL.",
            "    SELECT OPTIONAL IN-FILE ASSIGN TO GFDD.",
            "DATA DIVISION.",
            "FILE SECTION.",
            "FD  IN-FILE GLOBAL.",
            "01  IN-REC PIC X(4).",
            "WORKING-STORAGE SECTION.",
            "01  WS-MARK IS GLOBAL PIC 9 VALUE 0.",
            "PROCEDURE DIVISION.",
            "DECLARATIVES.",
            "CATCHER SECTION.",
            "    " + use,
            "CATCH-IT.",
            "    MOVE 7 TO WS-MARK.",
            "END DECLARATIVES.",
            "MAIN SECTION.",
            "MAIN-START.",
            "    CALL 'GFSUB'",
            "    DISPLAY WS-MARK",
            "    STOP RUN.",
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. GFSUB.",
            "PROCEDURE DIVISION.",
            "SUB-START.",
            "    OPEN INPUT IN-FILE",
            "    READ IN-FILE",
            "    GOBACK.",
            "END PROGRAM GFSUB.",
            "END PROGRAM GFMAIN.");
    }

    @Test
    @DisplayName("囲む側の FD ... GLOBAL と USE GLOBAL は、囲まれた側でも効く (FR-091)")
    void acontainedProgramUsesTheGlobalFileAndDeclarativeOfItsContainer() {
        // GFSUB はファイルも宣言節も持たない。囲む GFMAIN が GLOBAL と書いたので、
        // ファイルが見え、受け止め手のない AT END で<b>囲む側の宣言節が動く</b>。
        // 節は囲む側の記憶域で動くので、書いた値は戻ったところで見えていなければならない
        assertEquals("7", run(List.of(nestedFile(
                "USE GLOBAL AFTER STANDARD ERROR PROCEDURE ON INPUT."))).trim());
    }

    @Test
    @DisplayName("GLOBAL でない宣言節は、囲まれた側では動かない (FR-091)")
    void anonGlobalDeclarativeDoesNotReachAcontainedProgram() {
        // GLOBAL と書かなければ、囲む側の中でしか動かない。囲まれた側では受け止め手が
        // 無いままになり、<b>異常終了する</b> (要件 FR-104)。GLOBAL の 1 語だけが違う
        assertThrows(FileOperationException.class, () -> run(List.of(nestedFile(
                "USE AFTER STANDARD ERROR PROCEDURE ON INPUT."))));
    }

    @Test
    @DisplayName("EXTERNAL の領域は実行単位で 1 つである (FR-014)")
    void anExternalItemIsOneAreaForTheWholeRunUnit() {
        // 引数を渡していないのに、呼ぶ側が書いた値が呼ばれた側から見え、
        // 呼ばれた側が書いた値が呼ぶ側から見える。それが EXTERNAL である。
        // ADD 10 TO SH-NUM が 11 になるのは<b>両方向</b>が通っている証拠になる
        assertEquals("ZZ0011", run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. EXTMAIN.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 SHARED-DATA IS EXTERNAL.",
                        "   03 SH-TEXT PIC X(2).",
                        "   03 SH-NUM  PIC 9(4).",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    MOVE 'AA' TO SH-TEXT",
                        "    MOVE 1 TO SH-NUM",
                        "    CALL 'SUBEXT'",
                        "    DISPLAY SH-TEXT SH-NUM",
                        "    STOP RUN."),
                SUB_EXTERNAL)).trim());
    }

    @Test
    @DisplayName("EXTERNAL と書かなければ、名前が同じでも別の領域である (FR-014)")
    void anItemWithoutTheExternalClauseIsNotShared() {
        // 名前が同じだけでは分け合わない。EXTERNAL と書いたときだけである
        assertEquals("AA0001", run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. EXTMAIN2.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 SHARED-DATA.",
                        "   03 SH-TEXT PIC X(2).",
                        "   03 SH-NUM  PIC 9(4).",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    MOVE 'AA' TO SH-TEXT",
                        "    MOVE 1 TO SH-NUM",
                        "    CALL 'SUBEXT'",
                        "    DISPLAY SH-TEXT SH-NUM",
                        "    STOP RUN."),
                SUB_EXTERNAL)).trim());
    }

    @Test
    @DisplayName("EXTERNAL は 2 度目の CALL でも引き継がれる (FR-014)")
    void anExternalItemKeepsItsValueAcrossCalls() {
        // 副プログラムの作業場所は 2 度目の呼び出しでも前回のままだが、EXTERNAL は
        // <b>呼ぶ側の書き換えも</b>引き継ぐ。1 + 10 + 10 で 21 になる
        assertEquals("ZZ0021", run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. EXTMAIN3.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 SHARED-DATA IS EXTERNAL.",
                        "   03 SH-TEXT PIC X(2).",
                        "   03 SH-NUM  PIC 9(4).",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    MOVE 'AA' TO SH-TEXT",
                        "    MOVE 1 TO SH-NUM",
                        "    CALL 'SUBEXT'",
                        "    CALL 'SUBEXT'",
                        "    DISPLAY SH-TEXT SH-NUM",
                        "    STOP RUN."),
                SUB_EXTERNAL)).trim());
    }

    @Test
    @DisplayName("CALL は副プログラムを呼び、書き換えは呼ぶ側に届く (FR-080, FR-081)")
    void aCalledProgramWritesThroughToTheCaller() {
        assertEquals("[xyz]\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-A PIC X(3) VALUE 'abc'.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'SUBA' USING WS-A",
                        "    DISPLAY '[' WS-A ']'."),
                SUB_WRITES)));
    }

    @Test
    @DisplayName("BY CONTENT は写しを渡すので書き換えが届かない (FR-081)")
    void byContentPassesACopy() {
        assertEquals("[abc]\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-A PIC X(3) VALUE 'abc'.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'SUBA' USING BY CONTENT WS-A",
                        "    DISPLAY '[' WS-A ']'."),
                SUB_WRITES)));
    }

    @Test
    @DisplayName("BY REFERENCE と BY CONTENT は次の指定まで効き続ける (FR-081)")
    void theModeStaysUntilTheNextPhrase() {
        // 1 番目は写し、2 番目と 3 番目は領域そのもの
        assertEquals("[abc][222][333]\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-A PIC X(3) VALUE 'abc'.",
                        "01 WS-B PIC X(3) VALUE 'abc'.",
                        "01 WS-C PIC X(3) VALUE 'abc'.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'SUBB' USING BY CONTENT WS-A",
                        "                      BY REFERENCE WS-B WS-C",
                        "    DISPLAY '[' WS-A ']' '[' WS-B ']' '[' WS-C ']'."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBB.",
                        "DATA DIVISION.",
                        "LINKAGE SECTION.",
                        "01 LK-1 PIC X(3).",
                        "01 LK-2 PIC X(3).",
                        "01 LK-3 PIC X(3).",
                        "PROCEDURE DIVISION USING LK-1 LK-2 LK-3.",
                        "MAIN-START.",
                        "    MOVE '111' TO LK-1",
                        "    MOVE '222' TO LK-2",
                        "    MOVE '333' TO LK-3",
                        "    GOBACK."))));
    }

    @Test
    @DisplayName("定数も引数に書ける (FR-081)")
    void aLiteralMayBePassed() {
        // 定数は渡す先の領域を持たないので、常に写しになる
        assertEquals("[QRS]\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-A PIC X(3) VALUE 'abc'.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'SUBC' USING 'QRS' WS-A",
                        "    DISPLAY '[' WS-A ']'."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBC.",
                        "DATA DIVISION.",
                        "LINKAGE SECTION.",
                        "01 LK-IN  PIC X(3).",
                        "01 LK-OUT PIC X(3).",
                        "PROCEDURE DIVISION USING LK-IN LK-OUT.",
                        "MAIN-START.",
                        "    MOVE LK-IN TO LK-OUT",
                        "    GOBACK."))));
    }

    @Test
    @DisplayName("GOBACK は呼んだ側へ戻り、続きが実行される (FR-080)")
    void gobackReturnsToTheCaller() {
        assertEquals("before after\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    DISPLAY 'before ' WITH NO ADVANCING",
                        "    CALL 'SUBD'",
                        "    DISPLAY 'after'."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBD.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    GOBACK."))));
    }

    @Test
    @DisplayName("STOP RUN は呼び先からでも実行そのものを終える (FR-080)")
    void stopRunEndsEverything() {
        assertEquals("before ".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    DISPLAY 'before ' WITH NO ADVANCING",
                        "    CALL 'SUBE'",
                        "    DISPLAY 'after'."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBE.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    STOP RUN."))));
    }

    @Test
    @DisplayName("手続き部の終わりは暗黙の GOBACK である (FR-080)")
    void fallingOffTheEndReturnsToTheCaller() {
        assertEquals("before after\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    DISPLAY 'before ' WITH NO ADVANCING",
                        "    CALL 'SUBF'",
                        "    DISPLAY 'after'."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBF.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CONTINUE."))));
    }

    @Test
    @DisplayName("副プログラムの作業場所は呼び出しをまたいで残る (FR-080)")
    void theWorkingStorageOfACalledProgramPersists() {
        assertEquals("[001][002][003]\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-N PIC 9(3).",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'SUBG' USING WS-N",
                        "    DISPLAY '[' WS-N ']' WITH NO ADVANCING",
                        "    CALL 'SUBG' USING WS-N",
                        "    DISPLAY '[' WS-N ']' WITH NO ADVANCING",
                        "    CALL 'SUBG' USING WS-N",
                        "    DISPLAY '[' WS-N ']'."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBG.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-COUNT PIC 9(3) VALUE 0.",
                        "LINKAGE SECTION.",
                        "01 LK-N PIC 9(3).",
                        "PROCEDURE DIVISION USING LK-N.",
                        "MAIN-START.",
                        "    ADD 1 TO WS-COUNT",
                        "    MOVE WS-COUNT TO LK-N",
                        "    GOBACK."))));
    }

    @Test
    @DisplayName("CANCEL すると次の呼び出しは初期状態から始まる (FR-083)")
    void cancelResetsTheWorkingStorage() {
        assertEquals("[001][001]\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-N PIC 9(3).",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'SUBG' USING WS-N",
                        "    DISPLAY '[' WS-N ']' WITH NO ADVANCING",
                        "    CANCEL 'SUBG'",
                        "    CALL 'SUBG' USING WS-N",
                        "    DISPLAY '[' WS-N ']'."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBG.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-COUNT PIC 9(3) VALUE 0.",
                        "LINKAGE SECTION.",
                        "01 LK-N PIC 9(3).",
                        "PROCEDURE DIVISION USING LK-N.",
                        "MAIN-START.",
                        "    ADD 1 TO WS-COUNT",
                        "    MOVE WS-COUNT TO LK-N",
                        "    GOBACK."))));
    }

    @Test
    @DisplayName("呼び先の名前をデータ項目で書ける (FR-080)")
    void theProgramNameMayComeFromADataItem() {
        assertEquals("[xyz]\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-NAME PIC X(8) VALUE 'SUBA'.",
                        "01 WS-A PIC X(3) VALUE 'abc'.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL WS-NAME USING WS-A",
                        "    DISPLAY '[' WS-A ']'."),
                SUB_WRITES)));
    }

    @Test
    @DisplayName("副プログラムはさらに別のプログラムを呼べる (FR-080)")
    void callsNest() {
        assertEquals("[xyz]\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "DATA DIVISION.",
                        "WORKING-STORAGE SECTION.",
                        "01 WS-A PIC X(3) VALUE 'abc'.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'MIDDLE' USING WS-A",
                        "    DISPLAY '[' WS-A ']'."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MIDDLE.",
                        "DATA DIVISION.",
                        "LINKAGE SECTION.",
                        "01 LK-A PIC X(3).",
                        "PROCEDURE DIVISION USING LK-A.",
                        "MAIN-START.",
                        "    CALL 'SUBA' USING LK-A",
                        "    GOBACK."),
                SUB_WRITES)));
    }

    @Test
    @DisplayName("呼び先が見つからなければ ON EXCEPTION を通る (FR-082)")
    void aMissingProgramTakesTheExceptionBranch() {
        assertEquals("missing\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'NOSUCH'",
                        "        ON EXCEPTION DISPLAY 'missing'",
                        "    END-CALL."))));
    }

    @Test
    @DisplayName("見つかれば NOT ON EXCEPTION を通る (FR-082)")
    void aFoundProgramTakesTheOtherBranch() {
        assertEquals("found\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'SUBD'",
                        "        NOT ON EXCEPTION DISPLAY 'found'",
                        "    END-CALL."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBD.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    GOBACK."))));
    }

    @Test
    @DisplayName("ON EXCEPTION を書かなければ呼び先がないのは異常終了である (FR-082)")
    void aMissingProgramWithoutTheBranchIsAnAbend() {
        assertThrows(ProgramNotFoundException.class, () -> run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'NOSUCH'."))));
    }

    @Test
    @DisplayName("BY VALUE はまだ書けないと報告する (FR-081)")
    void byValueIsReportedAsUnsupported() {
        CobolCompiler.Result result = compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. MAIN.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-A PIC X(3).",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    CALL 'SUBA' USING BY VALUE WS-A."));

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("BY VALUE"),
                result.diagnostics().toString());
    }

    // ---- EXIT PROGRAM (FR-067) ----

    @Test
    @DisplayName("EXIT PROGRAM は呼んだ側へ戻る (FR-067)")
    void exitProgramReturnsToTheCaller() {
        assertEquals("[in][back]\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'SUBX'",
                        "    DISPLAY '[back]'."),
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBX.",
                        "PROCEDURE DIVISION.",
                        "SUB-START.",
                        "    DISPLAY '[in]' WITH NO ADVANCING",
                        "    EXIT PROGRAM.",
                        "    DISPLAY '[not reached]'."))));
    }

    @Test
    @DisplayName("主プログラムの EXIT PROGRAM は何もしない (FR-067)")
    void exitProgramDoesNothingInAMainProgram() {
        // COBOL の決まりである。GOBACK と違うのはここだけであり、
        // どちらの意味になるかは実行時にしか分からない
        assertEquals("[one][two]\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    DISPLAY '[one]' WITH NO ADVANCING",
                        "    EXIT PROGRAM.",
                        "    DISPLAY '[two]'."))));
    }

    @Test
    @DisplayName("EXIT だけなら何もしない (FR-067)")
    void aPlainExitIsStillNothing() {
        assertEquals("[one][two]\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    DISPLAY '[one]' WITH NO ADVANCING",
                        "    EXIT.",
                        "    DISPLAY '[two]'."))));
    }

    // ---- 1 本のソースに何本でも書ける (FR-080) ----

    @Test
    @DisplayName("END PROGRAM で区切れば 1 本のソースに何本でも書ける (FR-080)")
    void oneSourceMayHoldSeveralPrograms() {
        CobolCompiler.Result result = compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. PROGA.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    DISPLAY '[one]'.",
                "END PROGRAM PROGA.",
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. PROGB.",
                "PROCEDURE DIVISION.",
                "SUB-START.",
                "    DISPLAY '[two]'."));

        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        assertEquals(2, result.programs().size());
        assertTrue(result.programs().get(0).className().endsWith("PROGA"),
                result.programs().get(0).className());
        assertTrue(result.programs().get(1).className().endsWith("PROGB"),
                result.programs().get(1).className());
    }

    @Test
    @DisplayName("プログラムごとに名前は独立である (FR-080)")
    void namesDoNotLeakBetweenPrograms() {
        // 同じ名前のファイルを 2 本が別々に持っていても、互いに関わりがない。
        // まとめて 1 つの割り付けにすると、関わりのない重なりを誤りとして報せてしまう
        CobolCompiler.Result result = compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. PROGA.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT PRINT-FILE ASSIGN TO PRTDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  PRINT-FILE.",
                "01  PRINT-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    STOP RUN.",
                "END PROGRAM PROGA.",
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. PROGB.",
                "ENVIRONMENT DIVISION.",
                "INPUT-OUTPUT SECTION.",
                "FILE-CONTROL.",
                "    SELECT PRINT-FILE ASSIGN TO PRTDD.",
                "DATA DIVISION.",
                "FILE SECTION.",
                "FD  PRINT-FILE.",
                "01  PRINT-REC PIC X(3).",
                "PROCEDURE DIVISION.",
                "SUB-START.",
                "    STOP RUN."));

        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        assertEquals(2, result.programs().size());
    }

    @Test
    @DisplayName("並べて書いたプログラムは呼び合える (FR-080)")
    void programsInOneSourceCanCallEachOther() {
        assertEquals("[in][back]\n".replace("\n", System.lineSeparator()), run(List.of(
                List.of("IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. MAIN.",
                        "PROCEDURE DIVISION.",
                        "MAIN-START.",
                        "    CALL 'SUBY'",
                        "    DISPLAY '[back]'.",
                        "END PROGRAM MAIN.",
                        "IDENTIFICATION DIVISION.",
                        "PROGRAM-ID. SUBY.",
                        "PROCEDURE DIVISION.",
                        "SUB-START.",
                        "    DISPLAY '[in]' WITH NO ADVANCING",
                        "    EXIT PROGRAM."))));
    }
}
