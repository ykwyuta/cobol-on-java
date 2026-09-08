package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 英数字編集項目への転記 (要件 FR-030, FR-060、設計 60)。
 *
 * <p>{@code PICTURE XX0XXBXXX} のような項目は、{@code A} と {@code X} の<b>文字位置</b>
 * にだけ送出データを詰め、{@code 0} {@code B} {@code /} はその場所に置く。ただの
 * バイト詰めにすると挿入文字が消えるので、<b>差が出る形</b>で確かめる。
 */
@Tag("V1")
class AlphanumericEditedMoveTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(AlphanumericEditedMoveTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String run(List<String> storage, String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. AEMOVE.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.")) {
            sb.append("       ").append(line).append('\n');
        }
        for (String line : storage) {
            sb.append("       ").append(line).append('\n');
        }
        sb.append("       PROCEDURE DIVISION.\n");
        for (String line : procedure) {
            sb.append("       ").append(line).append('\n');
        }
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, sb.toString());
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            program.runFresh(ProgramContext.capturing(sink));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    private static final List<String> STORAGE = List.of(
            "01 WS-EDIT PIC XX0XXBXXX.",
            "01 WS-SRC  PIC X(9) VALUE 'ABCDEFGHI'.",
            "01 WS-NUM  PIC 9(10) VALUE 0123456789.");

    @Test
    @DisplayName("挿入文字は文字位置を占めない (FR-030)")
    void insertionCharactersTakeTheirOwnPosition() {
        // 送出は 9 文字あるが、文字位置は 7 つしかない。7 文字だけ採る
        assertEquals("[AB0CD EFG]|", run(STORAGE,
                "MOVE WS-SRC TO WS-EDIT.",
                "DISPLAY '[' WS-EDIT ']'.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("数字項目からの転記でも挿入文字は置かれる (FR-060)")
    void movingFromANumericItemStillInserts() {
        // NC105A の MOVE-TEST-F1-52 と同じ形。10 桁のうち左から 7 桁を採る
        assertEquals("[01023 456]|", run(STORAGE,
                "MOVE WS-NUM TO WS-EDIT.",
                "DISPLAY '[' WS-EDIT ']'.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("図形定数は文字位置の数だけ広がる (FR-060)")
    void aFigurativeConstantFillsOnlyTheCharacterPositions() {
        // 項目の長さ (9) ぶん広げてしまうと、挿入文字の分だけ多く採ってしまう
        assertEquals("[00000 000]|[  0      ]|", run(STORAGE,
                "MOVE ZERO TO WS-EDIT.",
                "DISPLAY '[' WS-EDIT ']'.",
                "MOVE SPACE TO WS-EDIT.",
                "DISPLAY '[' WS-EDIT ']'.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("送出が短ければ残りの文字位置は空白になる (FR-030)")
    void aShortSenderLeavesTheRestBlank() {
        assertEquals("[AB0C     ]|", run(STORAGE,
                "MOVE 'ABC' TO WS-EDIT.",
                "DISPLAY '[' WS-EDIT ']'.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("斜線も挿入文字である (FR-030)")
    void theSolidusIsAnInsertionCharacterToo() {
        assertEquals("[12/31/99]|", run(
                List.of("01 WS-DATE PIC XX/XX/XX."),
                "MOVE '123199' TO WS-DATE.",
                "DISPLAY '[' WS-DATE ']'.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("符号付きの表示形式からは、符号を落として送る (FR-060)")
    void aSignedDisplayItemSendsItsAbsoluteValue() {
        // ゾーンに埋め込んだ符号をそのまま送ると、最後の桁が英字に見える。
        // 規格は絶対値を送ると決めている (NC105A の MOVE-TEST-F1-92 / -93)
        assertEquals("[60666][70717]|", run(
                List.of("77 WS-POS PIC S9(5) VALUE +60666.",
                        "77 WS-NEG PIC S9(5) VALUE -70717.",
                        "77 WS-A   PIC X(5) VALUE SPACE.",
                        "77 WS-B   PIC X(5) VALUE SPACE."),
                "MOVE WS-POS TO WS-A.",
                "MOVE WS-NEG TO WS-B.",
                "DISPLAY '[' WS-A '][' WS-B ']'.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("符号を別に持つ項目からも、符号は送らない (FR-060)")
    void aSeparateSignIsNotSentEither() {
        assertEquals("[60666]|", run(
                List.of("77 WS-POS PIC S9(5) SIGN IS LEADING SEPARATE VALUE +60666.",
                        "77 WS-A   PIC X(5) VALUE SPACE."),
                "MOVE WS-POS TO WS-A.",
                "DISPLAY '[' WS-A ']'.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("符号を書かない項目はそのまま送る (FR-060)")
    void anUnsignedItemIsSentAsItStands() {
        assertEquals("[60666]|", run(
                List.of("77 WS-POS PIC 9(5) VALUE 60666.",
                        "77 WS-A   PIC X(5) VALUE SPACE."),
                "MOVE WS-POS TO WS-A.",
                "DISPLAY '[' WS-A ']'.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("受取側が集団項目なら符号も落とさない (FR-060)")
    void aGroupReceiverTakesTheBytesAsTheyStand() {
        // 集団項目への転記はバイト範囲そのものへの写しであり、変換は一切起きない。
        // SQ111A は符号の 1 バイトを FILLER で受け、うしろの 5 桁だけを数える
        assertEquals("[+][60666]|", run(
                List.of("77 WS-POS PIC S9(5) SIGN IS LEADING SEPARATE VALUE +60666.",
                        "01 WS-GRP.",
                        "   02 WS-SIGN PIC X.",
                        "   02 WS-DIGITS PIC 9(5)."),
                "MOVE WS-POS TO WS-GRP.",
                "DISPLAY '[' WS-SIGN '][' WS-DIGITS ']'.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("可変長の表を含む群は、送るときだけ長さが変わる (FR-020)")
    void aGroupWithADependingTableSendsOnlyWhatIsActive() {
        // 送り出す側はいま何個あるかまで。受け取る側は<b>いちばん大きい形</b>である。
        // 分けないと、受取側の古い個数で切ってしまう (NC247A の MOV-TEST-F1-6)
        assertEquals("[3ABC]|[9ABCDEFGHI]|", run(
                List.of("01 WS-SRC.",
                        "   02 WS-N PIC 9.",
                        "   02 WS-E PIC X OCCURS 0 TO 9 DEPENDING ON WS-N.",
                        "01 WS-DST.",
                        "   02 WD-N PIC 9 VALUE 1.",
                        "   02 WD-E PIC X OCCURS 0 TO 9 DEPENDING ON WD-N.",
                        "01 WS-HOLD PIC X(10) VALUE SPACES."),
                "MOVE 9 TO WS-N.",
                "MOVE 'ABCDEFGHI' TO WS-HOLD.",
                "MOVE WS-HOLD (1:9) TO WS-SRC (2:9).",
                "MOVE 3 TO WS-N.",
                "DISPLAY '[' WS-SRC ']'.",
                "MOVE 9 TO WS-N.",
                "MOVE WS-SRC TO WS-DST.",
                "DISPLAY '[' WS-DST ']'.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("添字を書けば 1 個分である (FR-020)")
    void asubscriptedReferenceIsOneOccurrence() {
        // 表そのものを添字なしで指したときだけ、いま何個あるかで長さが決まる
        assertEquals("[C]|", run(
                List.of("01 WS-SRC.",
                        "   02 WS-N PIC 9 VALUE 3.",
                        "   02 WS-E PIC X OCCURS 0 TO 9 DEPENDING ON WS-N."),
                "MOVE 'A' TO WS-E (1).",
                "MOVE 'B' TO WS-E (2).",
                "MOVE 'C' TO WS-E (3).",
                "DISPLAY '[' WS-E (3) ']'.",
                "STOP RUN."));
    }
}
