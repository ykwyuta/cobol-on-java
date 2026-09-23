package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.compiler.source.CompilerOptions;
import dev.cobolonjava.compiler.source.ProcessStatement;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 国字 ({@code PIC N}、{@code USAGE NATIONAL}、{@code N'..'}、{@code NX'..'}、
 * {@code NATIONAL-OF}、{@code DISPLAY-OF})。
 *
 * <p>国字は UTF-16 のビッグエンディアンである。期待値は Unicode の符号位置から書いた。
 * 実機の {@code NATIONAL-OF} が 1 バイトの EBCDIC を JDK と同じ符号位置へ直すかは、
 * z/OS probe の {@code CBLCP} が確かめる (暫定判断 P-012)。
 */
class NationalGenerationTest {

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(NationalGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String sourceOf(List<String> storage, String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. NATL.",
                "DATA DIVISION.", "WORKING-STORAGE SECTION.")) {
            sb.append("       ").append(line).append('\n');
        }
        for (String line : storage) {
            sb.append("       ").append(line).append('\n');
        }
        sb.append("       PROCEDURE DIVISION.\n");
        for (String line : procedure) {
            sb.append("       ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static CobolCompiler.Result compile(String options, List<String> storage,
                                                String... procedure) {
        CompilerOptions given = options == null ? CompilerOptions.NONE
                : ProcessStatement.parse(options);
        return CobolCompiler.standard().withOptions(given)
                .compile("NATL.cbl", sourceOf(storage, procedure));
    }

    private static CobolProgram load(CobolCompiler.Result result) {
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            return (CobolProgram) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot run the generated program", e);
        }
    }

    /** 実行したあとの作業場所節を 16 進で返す。 */
    private static String storage(List<String> storage, String... procedure) {
        Storage executed = load(compile(null, storage, procedure)).runFresh();
        return HexFormat.of().withUpperCase().formatHex(executed.array());
    }

    private static String output(List<String> storage, String... procedure) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        load(compile(null, storage, procedure)).runFresh(ProgramContext.capturing(sink));
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "|");
    }

    private static String refusal(List<String> storage, String... procedure) {
        CobolCompiler.Result result = compile(null, storage, procedure);
        assertFalse(result.succeeded(), "expected the compiler to refuse");
        return result.diagnostics().toString();
    }

    // --- 項目と VALUE ---

    @Test
    @DisplayName("PIC N は 1 文字 2 バイトで、VALUE の余りは国字の空白 X'0020' で埋める")
    void holdsTwoBytesPerCharacter() {
        assertEquals("00410042" + "0020", storage(List.of("01 N PIC N(3) VALUE N'AB'.")));
    }

    @Test
    @DisplayName("USAGE NATIONAL を書いても同じ。群項目の長さに 2 倍で入る")
    void countsTheLengthInBytes() {
        String image = storage(List.of(
                "01 G.",
                "   05 N PIC N(2) USAGE NATIONAL VALUE N'Z'.",
                "   05 X PIC X VALUE 'A'."));
        assertEquals("005A0020" + "C1", image);
    }

    @Test
    @DisplayName("N'..' は原文の文字をそのまま UTF-16 にする。日本語も書ける")
    void writesJapaneseWithNationalLiterals() {
        assertEquals("5C717530" + "0020", storage(List.of("01 N PIC N(3) VALUE N'山田'.")));
    }

    @Test
    @DisplayName("NX'..' は符号単位の 16 進である")
    void readsNationalHexadecimalLiterals() {
        assertEquals("30423044", storage(List.of("01 N PIC N(2) VALUE NX'30423044'.")));
    }

    @Test
    @DisplayName("NX の桁が 4 の倍数でなければ断る")
    void refusesAShortNationalHexadecimalLiteral() {
        assertTrue(refusal(List.of("01 N PIC N(2) VALUE NX'304'.")).contains("multiple of four"));
    }

    // --- MOVE ---

    @Test
    @DisplayName("英数字を国字へ移すと、コードページの文字として読んで直す")
    void convertsAlphanumericToNational() {
        String image = storage(List.of(
                        "01 X PIC X(3) VALUE 'XYZ'.",
                        "01 N PIC N(4)."),
                "MOVE X TO N.");
        assertEquals("E7E8E9" + "0058" + "0059" + "005A" + "0020", image);
    }

    @Test
    @DisplayName("図形定数は国字の図形定数になる。HIGH-VALUE は X'FFFF'")
    void usesNationalFigurativeConstants() {
        String image = storage(List.of(
                        "01 A PIC N(2).",
                        "01 B PIC N(2).",
                        "01 C PIC N(2).",
                        "01 D PIC N(3)."),
                "MOVE SPACES TO A.",
                "MOVE HIGH-VALUES TO B.",
                "MOVE ZEROS TO C.",
                "MOVE ALL 'AB' TO D.");
        assertEquals("00200020" + "FFFFFFFF" + "00300030" + "004100420041", image);
    }

    @Test
    @DisplayName("国字どうしの転記は文字の単位で切り、JUSTIFIED なら右へ寄せる")
    void movesNationalToNational() {
        String image = storage(List.of(
                        "01 A PIC N(3) VALUE N'ABC'.",
                        "01 B PIC N(2).",
                        "01 C PIC N(4) JUSTIFIED RIGHT."),
                "MOVE A TO B.",
                "MOVE A TO C.");
        assertEquals("004100420043" + "00410042" + "0020004100420043", image);
    }

    @Test
    @DisplayName("国字から英数字へは移せない。DISPLAY-OF を使う")
    void refusesNationalToAlphanumeric() {
        String diagnostics = refusal(List.of(
                        "01 N PIC N(2) VALUE N'AB'.",
                        "01 X PIC X(2)."),
                "MOVE N TO X.");
        assertTrue(diagnostics.contains("DISPLAY-OF"), diagnostics);
    }

    @Test
    @DisplayName("整数の項目は英数字として読んでから国字にする")
    void movesAnIntegerToNational() {
        String image = storage(List.of(
                        "01 I PIC S9(3) VALUE -12.",
                        "01 N PIC N(3)."),
                "MOVE I TO N.");
        // 符号は落ちる (英数字へ移すときと同じ)
        assertTrue(image.endsWith("003000310032"), image);
    }

    // --- DISPLAY と比較 ---

    @Test
    @DisplayName("DISPLAY は国字をコードページの文字へ直して出す")
    void displaysNationalData() {
        assertEquals("AB |ABC|", output(List.of("01 N PIC N(3) VALUE N'AB'."),
                "DISPLAY N.",
                "DISPLAY N'ABC'."));
    }

    @Test
    @DisplayName("比較は短いほうを国字の空白で埋める。英数字は国字に直して比べる")
    void comparesNationalData() {
        assertEquals("EQ|EQ|LT|", output(List.of("01 N PIC N(3) VALUE N'AB'."),
                "IF N = N'AB' DISPLAY 'EQ' ELSE DISPLAY 'NE' END-IF.",
                "IF N = 'AB' DISPLAY 'EQ' ELSE DISPLAY 'NE' END-IF.",
                "IF N < N'AC' DISPLAY 'LT' ELSE DISPLAY 'GE' END-IF."));
    }

    @Test
    @DisplayName("国字の比較は照合順序ではなく符号単位の値で決まる")
    void comparesByCodeUnits() {
        // EBCDIC では英字 < 数字だが、Unicode では数字 (U+0031) < 英字 (U+0041)
        assertEquals("LT|", output(List.of("01 N PIC N(1) VALUE N'1'."),
                "IF N < N'A' DISPLAY 'LT' ELSE DISPLAY 'GE' END-IF."));
    }

    // --- 関数 ---

    @Test
    @DisplayName("NATIONAL-OF と DISPLAY-OF で往復する")
    void convertsWithFunctions() {
        String image = storage(List.of(
                        "01 X PIC X(2) VALUE 'AB'.",
                        "01 N PIC N(2).",
                        "01 Y PIC X(2)."),
                "MOVE FUNCTION NATIONAL-OF(X) TO N.",
                "MOVE FUNCTION DISPLAY-OF(N) TO Y.");
        assertEquals("C1C2" + "00410042" + "C1C2", image);
    }

    @Test
    @DisplayName("第 2 引数の CCSID で読む。037 と 1047 は角括弧の位置が違う")
    void readsWithTheGivenCcsid() {
        // X'BA' は IBM-037 では '[' (U+005B)、IBM-1047 では U+00DD
        String image = storage(List.of(
                        "01 X PIC X VALUE X'BA'.",
                        "01 A PIC N.",
                        "01 B PIC N."),
                "MOVE FUNCTION NATIONAL-OF(X, 37) TO A.",
                "MOVE FUNCTION NATIONAL-OF(X) TO B.");
        assertEquals("BA" + "005B" + "00DD", image);
    }

    @Test
    @DisplayName("日本語の CCSID 939 で国字から英数字 (DBCS の混在) へ直す")
    void convertsToAMixedCodePage() {
        String image = storage(List.of(
                        "01 N PIC N(2) VALUE N'山田'.",
                        "01 X PIC X(6)."),
                "MOVE FUNCTION DISPLAY-OF(N, 939) TO X.");
        byte[] expected = "山田".getBytes(java.nio.charset.Charset.forName("x-IBM939"));
        assertEquals(HexFormat.of().withUpperCase().formatHex(expected),
                image.substring(8, 8 + expected.length * 2));
        assertEquals((byte) 0x0E, expected[0], "shift-out opens the DBCS run");
    }

    @Test
    @DisplayName("CCSID は項目でも渡せる。LENGTH は国字を文字で数える")
    void takesTheCcsidFromAnItemAndCountsCharacters() {
        String image = storage(List.of(
                        "01 X PIC X(4) VALUE X'0E45650F'.",
                        "01 C PIC 9(5) VALUE 939.",
                        "01 N PIC N(2).",
                        "01 L1 PIC 9(2).",
                        "01 L2 PIC 9(2)."),
                "MOVE FUNCTION NATIONAL-OF(X, C) TO N.",
                "COMPUTE L1 = FUNCTION LENGTH(N).",
                "COMPUTE L2 = FUNCTION LENGTH(FUNCTION NATIONAL-OF(X, C)).");
        String expected = HexFormat.of().withUpperCase().formatHex(
                new String(new byte[] {0x0E, 0x45, 0x65, 0x0F},
                        java.nio.charset.Charset.forName("x-IBM939"))
                        .getBytes(StandardCharsets.UTF_16BE));
        // SO / SI は国字に残らないので、4 バイトの英数字が 1 文字になる
        assertEquals(4, expected.length());
        assertEquals("0E45650F" + "F0F0F9F3F9" + expected + "0020" + "F0F2" + "F0F1", image);
    }

    @Test
    @DisplayName("1390 と 1399 は JDK に無いので断る (P-002)")
    void refusesUnsupportedCcsids() {
        String diagnostics = refusal(List.of(
                        "01 X PIC X(2).",
                        "01 N PIC N(2)."),
                "MOVE FUNCTION NATIONAL-OF(X, 1399) TO N.");
        assertTrue(diagnostics.contains("1399"), diagnostics);
    }

    // --- INITIALIZE ---

    @Test
    @DisplayName("INITIALIZE は国字の空白で埋める")
    void initializesWithNationalSpaces() {
        assertEquals("00200020", storage(List.of("01 N PIC N(2) VALUE N'AB'."),
                "INITIALIZE N."));
    }

    // --- 断るもの ---

    @Test
    @DisplayName("国字を扱えない文 (INSPECT、STRING) では断る")
    void refusesNationalDataInOtherStatements() {
        assertTrue(refusal(List.of("01 N PIC N(2).", "01 C PIC 9(2)."),
                "INSPECT N TALLYING C FOR ALL N'A'.").contains("cannot be used"));
        assertTrue(refusal(List.of("01 N PIC N(2).", "01 X PIC X(4)."),
                "STRING N DELIMITED BY SIZE INTO X.").contains("cannot be used"));
    }

    @Test
    @DisplayName("国字の部分参照は断る (位置は文字で数えるため)")
    void refusesReferenceModification() {
        assertTrue(refusal(List.of("01 N PIC N(3).", "01 M PIC N(1)."),
                "MOVE N(2:1) TO M.").contains("reference modification"));
    }

    @Test
    @DisplayName("国字編集、国字の数字、DBCS (USAGE DISPLAY の PIC N) は断る")
    void refusesOtherNationalForms() {
        assertFalse(compile(null, List.of("01 N PIC NBN."), "GOBACK.").succeeded());
        assertFalse(compile(null, List.of("01 N PIC 9(3) USAGE NATIONAL."), "GOBACK.").succeeded());
        assertFalse(compile(null, List.of("01 N PIC N(3) USAGE DISPLAY."), "GOBACK.").succeeded());
        assertFalse(compile("NSYMBOL(DBCS)", List.of("01 N PIC N(3)."), "GOBACK.").succeeded());
        assertTrue(compile("NSYMBOL(NATIONAL)", List.of("01 N PIC N(3)."), "GOBACK.").succeeded());
    }
}
