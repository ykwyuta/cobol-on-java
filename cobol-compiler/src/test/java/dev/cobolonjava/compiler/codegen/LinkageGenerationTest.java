package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code LINKAGE SECTION} と {@code PROCEDURE DIVISION USING} (要件 FR-070)。
 *
 * <p>連絡節の項目は<b>記憶域を持たない</b>。実体は呼ぶ側にあり、書き換えは呼ぶ側から
 * 即座に見える。ここではその「呼ぶ側」を Java から与えて確かめる。
 */
@Tag("V1")
class LinkageGenerationTest {

    private static final String FILE = "SUB.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(LinkageGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static CobolCompiler.Result compile(List<String> divisions) {
        StringBuilder sb = new StringBuilder();
        for (String line : divisions) {
            sb.append("       ").append(line).append('\n');
        }
        return CobolCompiler.standard().compile(FILE, sb.toString());
    }

    private static CobolProgram load(CobolCompiler.Result result) {
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            return (CobolProgram) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot load the generated program", e);
        }
    }

    /** 呼ぶ側の記憶域を作る。中身は IBM-1047 で書く。 */
    private static Storage callerStorage(String text) {
        return Storage.wrap(CodePages.DEFAULT.encode(text));
    }

    /** 引数を渡して実行し、呼ぶ側の記憶域を読み返す。 */
    private static String call(CobolProgram program, Storage caller, DataView... arguments) {
        program.runFresh(ProgramContext.standard(), arguments);
        return CodePages.DEFAULT.decode(caller.array());
    }

    /** 引数 1 つを取り、その中身を書き換える副プログラム。 */
    private static final List<String> ONE_ARGUMENT = List.of(
            "IDENTIFICATION DIVISION.",
            "PROGRAM-ID. SUB.",
            "DATA DIVISION.",
            "LINKAGE SECTION.",
            "01 LK-REC.",
            "   05 LK-A PIC X(3).",
            "   05 LK-N PIC 9(3).",
            "PROCEDURE DIVISION USING LK-REC.",
            "MAIN-START.",
            "    MOVE 'XYZ' TO LK-A",
            "    ADD 1 TO LK-N.");

    @Test
    @DisplayName("連絡節への書き込みは呼ぶ側の記憶域に届く (FR-070)")
    void writingToLinkageReachesTheCallersStorage() {
        Storage caller = callerStorage("--ABC010--");
        CobolProgram program = load(compile(ONE_ARGUMENT));

        assertEquals("--XYZ011--", call(program, caller, caller.view(2, 6)));
    }

    @Test
    @DisplayName("渡す位置が変われば書き換わる場所も変わる (FR-070)")
    void theArgumentOffsetDecidesWhereTheWriteLands() {
        Storage caller = callerStorage("ABC010----");
        CobolProgram program = load(compile(ONE_ARGUMENT));

        assertEquals("XYZ011----", call(program, caller, caller.view(0, 6)));
    }

    @Test
    @DisplayName("連絡節の項目は作業場所を占めない (FR-070)")
    void linkageItemsTakeNoWorkingStorage() {
        CobolProgram program = load(compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SUB.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-A PIC X(2) VALUE 'ws'.",
                "LINKAGE SECTION.",
                "01 LK-A PIC X(9).",
                "PROCEDURE DIVISION USING LK-A.",
                "MAIN-START.",
                "    CONTINUE.")));

        // 作業場所は 2 バイトだけである。連絡節の 9 バイトは含まれない
        assertEquals(2, program.initialStorage().length);
    }

    @Test
    @DisplayName("作業場所と連絡節を同じ文で扱える (FR-070)")
    void workingStorageAndLinkageMixInOneStatement() {
        Storage caller = callerStorage("--000--");
        CobolProgram program = load(compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SUB.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-N PIC 9(3) VALUE 042.",
                "LINKAGE SECTION.",
                "01 LK-N PIC 9(3).",
                "PROCEDURE DIVISION USING LK-N.",
                "MAIN-START.",
                "    MOVE WS-N TO LK-N.")));

        assertEquals("--042--", call(program, caller, caller.view(2, 3)));
    }

    @Test
    @DisplayName("引数は USING に並べた順に対応する (FR-070)")
    void argumentsFollowTheOrderOfTheUsingList() {
        Storage caller = callerStorage("aaabbb");
        CobolProgram program = load(compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SUB.",
                "DATA DIVISION.",
                "LINKAGE SECTION.",
                "01 LK-1 PIC X(3).",
                "01 LK-2 PIC X(3).",
                "PROCEDURE DIVISION USING LK-1 LK-2.",
                "MAIN-START.",
                "    MOVE '111' TO LK-1",
                "    MOVE '222' TO LK-2.")));

        assertEquals("111222", call(program, caller, caller.view(0, 3), caller.view(3, 3)));
    }

    @Test
    @DisplayName("同じ領域を 2 つの引数に渡せる (FR-070)")
    void twoArgumentsMayShareOneArea() {
        // 参照渡しであるから、一方への書き込みは他方から見える。写し取っていれば見えない
        Storage caller = callerStorage("---");
        CobolProgram program = load(compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SUB.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-SEEN PIC X VALUE 'n'.",
                "LINKAGE SECTION.",
                "01 LK-1 PIC X(3).",
                "01 LK-2 PIC X(3).",
                "PROCEDURE DIVISION USING LK-1 LK-2.",
                "MAIN-START.",
                "    MOVE 'abc' TO LK-1",
                "    IF LK-2 = 'abc'",
                "        MOVE 'y' TO WS-SEEN",
                "    END-IF.")));

        Storage own = Storage.wrap(program.initialStorage());
        program.run(own, ProgramContext.standard(),
                new DataView[] {caller.view(0, 3), caller.view(0, 3)});

        assertEquals("abc", CodePages.DEFAULT.decode(caller.array()));
        assertEquals("y", CodePages.DEFAULT.decode(own.array()));
    }

    @Test
    @DisplayName("連絡節の表も添字で引ける (FR-024, FR-070)")
    void aTableInLinkageIsSubscripted() {
        Storage caller = callerStorage("--aaabbbccc--");
        CobolProgram program = load(compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SUB.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-I PIC 9(3) VALUE 2.",
                "LINKAGE SECTION.",
                "01 LK-T.",
                "   05 LK-E OCCURS 3 TIMES PIC X(3).",
                "PROCEDURE DIVISION USING LK-T.",
                "MAIN-START.",
                "    MOVE '111' TO LK-E (1)",
                "    MOVE '222' TO LK-E (WS-I).")));

        assertEquals("--111222ccc--", call(program, caller, caller.view(2, 9)));
    }

    @Test
    @DisplayName("連絡節に VALUE を書いたら誤りとして報告する (FR-070)")
    void aValueClauseInLinkageIsReported() {
        CobolCompiler.Result result = compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SUB.",
                "DATA DIVISION.",
                "LINKAGE SECTION.",
                "01 LK-A PIC X(3) VALUE 'abc'.",
                "PROCEDURE DIVISION USING LK-A.",
                "MAIN-START.",
                "    CONTINUE."));

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("VALUE is not allowed"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("USING に作業場所の項目を並べたら誤りとして報告する (FR-070)")
    void aWorkingStorageParameterIsReported() {
        CobolCompiler.Result result = compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SUB.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-A PIC X(3).",
                "PROCEDURE DIVISION USING WS-A.",
                "MAIN-START.",
                "    CONTINUE."));

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("requires a LINKAGE SECTION"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("USING に並べていない連絡節を使ったら誤りとして報告する (FR-070)")
    void anUnboundLinkageItemIsReported() {
        CobolCompiler.Result result = compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SUB.",
                "DATA DIVISION.",
                "LINKAGE SECTION.",
                "01 LK-A PIC X(3).",
                "01 LK-B PIC X(3).",
                "PROCEDURE DIVISION USING LK-A.",
                "MAIN-START.",
                "    MOVE 'xyz' TO LK-B."));

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message()
                        .contains("not listed in PROCEDURE DIVISION USING"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("BY VALUE はまだ書けないと報告する (FR-070)")
    void byValueIsReportedAsUnsupported() {
        CobolCompiler.Result result = compile(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. SUB.",
                "DATA DIVISION.",
                "LINKAGE SECTION.",
                "01 LK-A PIC X(3).",
                "PROCEDURE DIVISION USING BY VALUE LK-A.",
                "MAIN-START.",
                "    CONTINUE."));

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("BY VALUE"),
                result.diagnostics().toString());
    }
}
