package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.compiler.source.ProcessStatement;
import dev.cobolonjava.runtime.abend.Abend;
import dev.cobolonjava.runtime.abend.AbendCode;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 異常終了コード (要件 FR-141)。
 *
 * <p>ホストは実行を打ち切るとき、何が起きたのかを 4 桁で言う。運用はそのコードを見て
 * 次にやることを決めるので、<b>コードが合っていない実装は動いても使えない</b>。
 *
 * <p>どの条件がどのコードになるかは、条件を表す例外自身が名乗る。ここで確かめるのは、
 * 翻訳したプログラムを走らせて<b>その条件へ実際に届くか</b>である。
 */
@Tag("V1")
class AbendGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(AbendGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String sourceOf(String processLine, List<String> storage, List<String> linkage,
                                   String... procedure) {
        StringBuilder sb = new StringBuilder();
        if (processLine != null) {
            sb.append("       ").append(processLine).append('\n');
        }
        List<String> head = new ArrayList<>(List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION."));
        head.addAll(storage);
        if (!linkage.isEmpty()) {
            head.add("LINKAGE SECTION.");
            head.addAll(linkage);
        }
        for (String line : head) {
            sb.append("       ").append(line).append('\n');
        }
        sb.append("       PROCEDURE DIVISION")
                .append(linkage.isEmpty() ? "" : " USING LK-ITEM")
                .append(".\n");
        for (String line : procedure) {
            sb.append("       ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static CobolProgram compile(String given, String source) {
        CompilerOptions options = given == null
                ? CompilerOptions.NONE
                : ProcessStatement.parse(given);
        CobolCompiler.Result result = CobolCompiler.standard().withOptions(options)
                .compile(FILE, source);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            return (CobolProgram) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot load the generated program", e);
        }
    }

    /** プログラムを走らせ、抜けてきた条件の異常終了コードを返す。 */
    private static AbendCode codeOf(String given, List<String> storage, String... procedure) {
        CobolProgram program = compile(given, sourceOf(null, storage, List.of(), procedure));
        RuntimeException thrown = assertThrows(RuntimeException.class, program::runFresh);
        return Abend.codeOf(thrown);
    }

    @Test
    @DisplayName("数値でないバイトを数として使えば S0C7 (FR-141)")
    void badNumericDataIsS0c7() {
        // 英数字として文字を入れてから、同じ場所をパック 10 進数として足す。
        // 'A' は X'C1' であり、上位の 4 ビットが数字にならない
        assertEquals(AbendCode.S0C7, codeOf(null, List.of(
                        "01 WS-G.",
                        "   05 WS-TEXT PIC X(3).",
                        "01 WS-N REDEFINES WS-G PIC 9(5) COMP-3.",
                        "01 WS-R PIC 9(5) VALUE 0."),
                "MOVE 'AAA' TO WS-TEXT",
                "ADD WS-N TO WS-R."));
    }

    @Test
    @DisplayName("ゼロで割れば S0CB (FR-141)")
    void divisionByZeroIsS0cb() {
        assertEquals(AbendCode.S0CB, codeOf(null, List.of(
                        "01 WS-A PIC 9(3) VALUE 100.",
                        "01 WS-B PIC 9(3) VALUE 0.",
                        "01 WS-R PIC 9(3) VALUE 0."),
                "DIVIDE WS-A BY WS-B GIVING WS-R."));
    }

    @Test
    @DisplayName("ON SIZE ERROR を書けばゼロ除算は異常終了にならない (FR-043, FR-141)")
    void sizeErrorCatchesTheDivision() {
        CobolProgram program = compile(null, sourceOf(null, List.of(
                "01 WS-A PIC 9(3) VALUE 100.",
                "01 WS-B PIC 9(3) VALUE 0.",
                "01 WS-R PIC 9(3) VALUE 0.",
                "01 WS-F PIC X VALUE 'N'."), List.of(),
                "DIVIDE WS-A BY WS-B GIVING WS-R",
                "    ON SIZE ERROR MOVE 'Y' TO WS-F",
                "END-DIVIDE."));
        // 受け止め手があれば条件のまま。打ち切らない
        program.runFresh();
    }

    @Test
    @DisplayName("SSRANGE の範囲外は U4038 (FR-024, FR-141)")
    void anOutOfRangeSubscriptIsU4038() {
        assertEquals(AbendCode.U4038, codeOf("SSRANGE", List.of(
                        "01 WS-T.",
                        "   05 WS-E OCCURS 3 TIMES PIC X VALUE 'A'.",
                        "01 WS-I PIC 9(3) VALUE 4."),
                "MOVE 'B' TO WS-E (WS-I)."));
    }

    @Test
    @DisplayName("見つからない CALL は S806 (FR-080, FR-141)")
    void anUnresolvedCallIsS806() {
        assertEquals(AbendCode.S806, codeOf(null, List.of(
                        "01 WS-NAME PIC X(8) VALUE 'NOSUCHPG'."),
                "CALL WS-NAME."));
    }

    @Test
    @DisplayName("渡されていない連絡節の項目を触れば S0C4 (FR-141)")
    void anUnsuppliedLinkageItemIsS0c4() {
        CobolProgram program = compile(null, sourceOf(null,
                List.of("01 WS-R PIC X(4) VALUE SPACES."),
                List.of("01 LK-ITEM PIC X(4)."),
                "MOVE LK-ITEM TO WS-R."));

        // 呼ぶ側が何も渡していない。ホストでは連絡節の指す先が定まらない
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> program.runFresh(ProgramContext.standard(), new DataView[0]));
        assertEquals(AbendCode.S0C4, Abend.codeOf(thrown));
    }

    @Test
    @DisplayName("渡してあれば連絡節はそのまま触れる (FR-141)")
    void aSuppliedLinkageItemIsFine() {
        CobolProgram program = compile(null, sourceOf(null,
                List.of("01 WS-R PIC X(4) VALUE SPACES."),
                List.of("01 LK-ITEM PIC X(4)."),
                "MOVE LK-ITEM TO WS-R."));

        program.runFresh(ProgramContext.standard(),
                new DataView[] {dev.cobolonjava.runtime.storage.Storage.allocate(4).whole()});
    }

    @Test
    @DisplayName("異常終了にならない誤りはコードを持たない (FR-141)")
    void anOrdinaryFailureHasNoCode() {
        assertNull(Abend.codeOf(new IllegalStateException("something else")));
    }
}
