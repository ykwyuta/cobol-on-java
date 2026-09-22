package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 翻訳して実行したとき、日本語のバイトが端から端まで変わらないこと (要件 FR-050)。
 *
 * <p>ランタイムの層で透明でも、翻訳系が途中でバイトを作り変えていれば資産は動かない。
 * ここは<b>原文から実行結果まで</b>を 1 本に通して確かめる。
 *
 * <p><b>なぜ 16 進定数で書くのか</b>: 翻訳時のコードページは処理系のどこでも
 * {@code CodePages.DEFAULT} (IBM-1047) に固定されており、{@code CODEPAGE} オプションは
 * まだ無い (暫定判断 P-177)。IBM-1047 は日本語を持たないので、原文に
 * {@code VALUE '山田太郎'} と書くことはいまはできない。<b>いま日本語の資産を扱える唯一の
 * 書き方</b>が 16 進定数であり、これはコードページを経由しないバイトである。
 *
 * <p>実行時のコードページは {@code ProgramContext.withCodePage} で差し替えられる。
 * {@code DISPLAY} はこれを見るので、混在コードページで読ませることができる。
 */
@Tag("V1")
class JapaneseGenerationTest {

    private static final String FILE = "MAIN.cbl";

    /** 山田太郎 を IBM-930 で符号化したバイト。10 バイト。出どころは {@code JapaneseFixtures}。 */
    private static final String NAME_930_HEX = "0E4565456345AB456E0F";

    /** 山田太郎。 */
    private static final String NAME = "山田太郎";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(JapaneseGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static CobolCompiler.Result compile(List<String> storage, String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.")) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : storage) {
            FixedFormatSource.append(sb, line);
        }
        FixedFormatSource.append(sb, "PROCEDURE DIVISION.");
        for (String line : procedure) {
            FixedFormatSource.append(sb, line);
        }
        return CobolCompiler.standard().compile(FILE, sb.toString());
    }

    /** 翻訳して、指定したコードページで実行し、{@code DISPLAY} が出したバイトを返す。 */
    private static byte[] runWith(CodePage codePage, List<String> storage, String... procedure) {
        CobolCompiler.Result result = compile(storage, procedure);
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            CobolProgram program = (CobolProgram) type.getDeclaredConstructor().newInstance();
            program.runFresh(ProgramContext.capturing(sink).withCodePage(codePage));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
        return sink.toByteArray();
    }

    /** {@code DISPLAY} が出した 1 行を文字として読む。改行は落とす。 */
    private static String displayed(CodePage codePage, List<String> storage, String... procedure) {
        return new String(runWith(codePage, storage, procedure), StandardCharsets.UTF_8)
                .replace(System.lineSeparator(), "");
    }

    /**
     * {@code DISPLAY} した項目の<b>バイトそのもの</b>を取り出す。
     *
     * <p>IBM-1047 で実行させるのは、このコードページが 256 バイトすべてに文字を当てて
     * いるからである ({@code JapaneseCodePageTest} で固定してある)。復号して符号化し直すと
     * 元のバイトに戻るので、{@code DISPLAY} を<b>記憶域を覗く窓</b>として使える。
     * 混在コードページでは往復しないので、この用途には使えない。
     */
    private static byte[] storedBytes(List<String> storage, String... procedure) {
        return CodePages.IBM_1047.encode(displayed(CodePages.IBM_1047, storage, procedure));
    }

    @Test
    @DisplayName("16 進定数で書いた日本語は VALUE から DISPLAY まで化けない (FR-050)")
    void japaneseSurvivesFromValueClauseToDisplay() {
        assertEquals(NAME, displayed(CodePages.IBM_930,
                List.of("01 WS-NAME PIC X(10) VALUE X'" + NAME_930_HEX + "'."),
                "DISPLAY WS-NAME.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("転記を経ても日本語は化けない (FR-060)")
    void japaneseSurvivesAMove() {
        assertEquals(NAME, displayed(CodePages.IBM_930,
                List.of("01 WS-NAME PIC X(10) VALUE X'" + NAME_930_HEX + "'.",
                        "01 WS-COPY PIC X(10)."),
                "MOVE WS-NAME TO WS-COPY.",
                "DISPLAY WS-COPY.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("受取項目が長ければ空白が付く。日本語のバイトはそのまま残る (FR-060)")
    void aLongerItemIsPaddedAndTheJapaneseIsUntouched() {
        assertEquals(NAME + "   ", displayed(CodePages.IBM_930,
                List.of("01 WS-NAME PIC X(10) VALUE X'" + NAME_930_HEX + "'.",
                        "01 WS-WIDE PIC X(13)."),
                "MOVE WS-NAME TO WS-WIDE.",
                "DISPLAY WS-WIDE.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("生成コードが記憶域へ置いたバイトは、書いた 16 進定数と同一である (FR-050)")
    void theGeneratedImageHoldsExactlyTheBytesThatWereWritten() {
        assertArrayEquals(HexFormat.of().parseHex(NAME_930_HEX),
                storedBytes(List.of("01 WS-NAME PIC X(10) VALUE X'" + NAME_930_HEX + "'."),
                        "DISPLAY WS-NAME.",
                        "STOP RUN."));
    }

    @Test
    @DisplayName("転記の先に入ったバイトも、書いた 16 進定数と同一である (FR-060)")
    void theBytesAfterAMoveAreIdentical() {
        assertArrayEquals(HexFormat.of().parseHex(NAME_930_HEX),
                storedBytes(List.of("01 WS-NAME PIC X(10) VALUE X'" + NAME_930_HEX + "'.",
                                "01 WS-COPY PIC X(10)."),
                        "MOVE WS-NAME TO WS-COPY.",
                        "DISPLAY WS-COPY.",
                        "STOP RUN."));
    }

    @Test
    @DisplayName("STRING で連結しても日本語は化けない (FR-065)")
    void japaneseSurvivesString() {
        assertEquals(NAME + NAME, displayed(CodePages.IBM_930,
                List.of("01 WS-NAME PIC X(10) VALUE X'" + NAME_930_HEX + "'.",
                        "01 WS-BOTH PIC X(20)."),
                "STRING WS-NAME DELIMITED BY SIZE",
                "       WS-NAME DELIMITED BY SIZE",
                "  INTO WS-BOTH",
                "END-STRING",
                "DISPLAY WS-BOTH.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("部分参照はバイトの位置で切る。シフトコードの内側を取り出せる (FR-024)")
    void referenceModificationCutsByByte() {
        // (2:8) はシフトアウトとシフトインを外した中身である。全角 4 文字ぶんの 8 バイトが
        // そのまま出る。組が開いたままなので文字としては読めない。読めるかどうかではなく
        // バイトが動いていないことを見る
        byte[] whole = HexFormat.of().parseHex(NAME_930_HEX);

        assertArrayEquals(java.util.Arrays.copyOfRange(whole, 1, 9),
                storedBytes(List.of("01 WS-NAME PIC X(10) VALUE X'" + NAME_930_HEX + "'.",
                                "01 WS-PART PIC X(8)."),
                        "MOVE WS-NAME(2:8) TO WS-PART.",
                        "DISPLAY WS-PART.",
                        "STOP RUN."));
    }

    @Test
    @DisplayName("短い受取項目は頭から詰めて切る。処理系は切り口を繕わない (FR-060)")
    void aShorterItemTruncatesWithoutRepair() {
        // 5 バイトで切ればシフトインが落ちて組が開いたままになる。ホストの PIC X も
        // 同じところで切れる。処理系が整えて返すほうが嘘になる
        byte[] whole = HexFormat.of().parseHex(NAME_930_HEX);

        assertArrayEquals(java.util.Arrays.copyOf(whole, 5),
                storedBytes(List.of("01 WS-NAME PIC X(10) VALUE X'" + NAME_930_HEX + "'.",
                                "01 WS-SHORT PIC X(5)."),
                        "MOVE WS-NAME TO WS-SHORT.",
                        "DISPLAY WS-SHORT.",
                        "STOP RUN."));
    }

    @Test
    @DisplayName("比較はバイト値の順で決まる。同じバイトなら等しい (FR-046)")
    void comparisonOnJapaneseBytes() {
        assertEquals("SAME", displayed(CodePages.IBM_930,
                List.of("01 WS-A PIC X(10) VALUE X'" + NAME_930_HEX + "'.",
                        "01 WS-B PIC X(10) VALUE X'" + NAME_930_HEX + "'."),
                "IF WS-A = WS-B",
                "   DISPLAY 'SAME'",
                "ELSE",
                "   DISPLAY 'DIFFERENT'",
                "END-IF.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("IBM-1047 で表せない文字を書いた定数は診断になる。黙って X'3F' にしない (FR-181)")
    void aJapaneseLiteralIsDiagnosedInsteadOfBeingSubstituted() {
        // 直す前はこれが診断なしに翻訳を通り、実行すると DISPLAY が空行を出していた。
        // 書いた 4 文字が消えたことに気付ける場所がどこにも無かった
        CobolCompiler.Result result = compile(
                List.of("01 WS-NAME PIC X(10) VALUE '" + NAME + "'."),
                "DISPLAY WS-NAME.",
                "STOP RUN.");

        assertFalse(result.succeeded(), "表せない文字は翻訳を通してはならない");
        assertTrue(result.diagnostics().toString().contains("cannot be represented in IBM-1047"),
                () -> result.diagnostics().toString());
        assertTrue(result.diagnostics().toString().contains("U+5C71"),
                () -> "どの文字かを名指しする: " + result.diagnostics().toString());
    }

    @Test
    @DisplayName("手続き部に書いた日本語の定数も診断になる (FR-181)")
    void aJapaneseLiteralInTheProcedureDivisionIsDiagnosed() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-X PIC X."),
                "DISPLAY '" + NAME + "'.",
                "STOP RUN.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().toString().contains("cannot be represented in IBM-1047"),
                () -> result.diagnostics().toString());
    }

    @Test
    @DisplayName("診断は止まった原文の位置を指す (FR-183)")
    void theDiagnosticPointsAtTheLineThatStopped() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-A PIC X(4).",
                        "01 WS-B PIC X(10) VALUE '" + NAME + "'."),
                "DISPLAY WS-B.",
                "STOP RUN.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).origin().line() == 6,
                () -> "6 行目を指すべきである: " + result.diagnostics());
    }

    @Test
    @DisplayName("16 進定数はコードページを通らないので、日本語のバイトを書ける (FR-013)")
    void hexLiteralsBypassTheCodePage() {
        CobolCompiler.Result result = compile(
                List.of("01 WS-NAME PIC X(10) VALUE X'" + NAME_930_HEX + "'."),
                "DISPLAY WS-NAME.",
                "STOP RUN.");

        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
    }

    @Test
    @DisplayName("英数字だけの定数はこれまでどおり通る。既存の書き方を壊していない")
    void ordinaryLiteralsStillCompile() {
        assertEquals("HELLO", displayed(CodePages.IBM_1047,
                List.of("01 WS-A PIC X(5) VALUE 'HELLO'."),
                "DISPLAY WS-A.",
                "STOP RUN."));
    }

    @Test
    @DisplayName("日本語コードページで実行しても英数字のバイトは変わらない (FR-051)")
    void asciiRangeIsUnaffectedByTheRuntimeCodePage() {
        // 生成コードは CodePages.DEFAULT を焼き込んでいる。英大文字・数字・空白は
        // 1047 / 930 / 939 で同じバイトなので、実行時のコードページが違っても食い違わない
        byte[] under1047 = runWith(CodePages.IBM_1047,
                List.of("01 WS-A PIC X(5) VALUE 'ABC12'."), "DISPLAY WS-A.", "STOP RUN.");
        byte[] under930 = runWith(CodePages.IBM_930,
                List.of("01 WS-A PIC X(5) VALUE 'ABC12'."), "DISPLAY WS-A.", "STOP RUN.");

        assertArrayEquals(under1047, under930);
    }
}
