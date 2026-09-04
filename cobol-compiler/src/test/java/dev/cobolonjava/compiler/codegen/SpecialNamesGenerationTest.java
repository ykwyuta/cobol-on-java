package dev.cobolonjava.compiler.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.CobolCompiler;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 環境部の {@code SPECIAL-NAMES} (要件 FR-054, FR-135)。
 *
 * <p>ここで決まるのは処理系の外側との結び付けである。通貨記号は PICTURE の解釈に、
 * 呼び名は {@code ACCEPT} と {@code DISPLAY} の行き先に効く。
 */
@Tag("V1")
class SpecialNamesGenerationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(SpecialNamesGenerationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    /** 環境部つきのソースを組み立てる。 */
    private static CobolCompiler.Result compile(List<String> specialNames, List<String> storage,
                                                String... procedure) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of("IDENTIFICATION DIVISION.", "PROGRAM-ID. HELLO.")) {
            sb.append("       ").append(line).append('\n');
        }
        if (!specialNames.isEmpty()) {
            for (String line : List.of("ENVIRONMENT DIVISION.", "CONFIGURATION SECTION.",
                    "SPECIAL-NAMES.")) {
                sb.append("       ").append(line).append('\n');
            }
            for (String line : specialNames) {
                sb.append("       ").append(line).append('\n');
            }
        }
        for (String line : List.of("DATA DIVISION.", "WORKING-STORAGE SECTION.")) {
            sb.append("       ").append(line).append('\n');
        }
        for (String line : storage) {
            sb.append("       ").append(line).append('\n');
        }
        sb.append("       PROCEDURE DIVISION.\n");
        for (String line : procedure) {
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

    /** 記憶域を読み返す。 */
    private static String storageOf(List<String> specialNames, List<String> storage,
                                    String... procedure) {
        Storage executed = load(compile(specialNames, storage, procedure)).runFresh();
        return CodePages.DEFAULT.decode(executed.array());
    }

    @Test
    @DisplayName("CURRENCY SIGN は PICTURE の通貨記号を差し替える (FR-054)")
    void currencySignChangesThePictureSymbol() {
        // 既定の $ ではなく Y を通貨記号として読む
        assertEquals("00125" + "  Y1.25", storageOf(
                List.of("    CURRENCY SIGN IS 'Y'."),
                List.of("01 WS-N PIC 9(3)V99 VALUE 1.25.", "01 WS-E PIC YYY9.99."),
                "MOVE WS-N TO WS-E."));
    }

    @Test
    @DisplayName("差し替えたあとは $ が通貨記号でなくなる (FR-054)")
    void theDefaultSymbolStopsBeingCurrency() {
        CobolCompiler.Result result = compile(
                List.of("    CURRENCY SIGN IS 'Y'."),
                List.of("01 WS-E PIC $$$9.99."),
                "CONTINUE.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("PICTURE"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("指定がなければ $ のままである (FR-054)")
    void withoutTheClauseTheDollarStays() {
        // 浮動する通貨記号は、いちばん左の有効数字のすぐ左に出る
        assertEquals("00125" + "  $1.25", storageOf(
                List.of(),
                List.of("01 WS-N PIC 9(3)V99 VALUE 1.25.", "01 WS-E PIC $$$9.99."),
                "MOVE WS-N TO WS-E."));
    }

    @Test
    @DisplayName("1 文字でない通貨記号は誤りとして報告する (FR-054)")
    void aMultiCharacterCurrencySignIsReported() {
        CobolCompiler.Result result = compile(
                List.of("    CURRENCY SIGN IS 'JPY'."),
                List.of("01 WS-A PIC X."), "CONTINUE.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("one-character"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("呼び名で DISPLAY の行き先を指定できる (FR-135)")
    void aMnemonicNamesTheDisplayDestination() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        load(compile(
                List.of("    CONSOLE IS TERM."),
                List.of("01 WS-A PIC X(3) VALUE 'abc'."),
                "DISPLAY WS-A UPON TERM."))
                .runFresh(ProgramContext.capturing(sink));

        assertEquals("abc" + System.lineSeparator(), sink.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("呼び名で ACCEPT の出どころを指定できる (FR-135)")
    void aMnemonicNamesTheAcceptSource() {
        Deque<String> lines = new ArrayDeque<>(List.of("hello"));
        ProgramContext context = ProgramContext.standard()
                .withInput(() -> lines.isEmpty() ? null : lines.removeFirst());
        Storage executed = load(compile(
                List.of("    SYSIN IS READER."),
                List.of("01 WS-A PIC X(5)."),
                "ACCEPT WS-A FROM READER."))
                .runFresh(context);

        assertEquals("hello", CodePages.DEFAULT.decode(executed.array()));
    }

    @Test
    @DisplayName("読み取る側の呼び名を DISPLAY に書いたら誤りとして報告する (FR-135)")
    void anInputMnemonicCannotReceiveDisplay() {
        CobolCompiler.Result result = compile(
                List.of("    SYSIN IS READER."),
                List.of("01 WS-A PIC X."), "DISPLAY 'X' UPON READER.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("output device"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("書き出す側の呼び名を ACCEPT に書いたら誤りとして報告する (FR-135)")
    void anOutputMnemonicCannotFeedAccept() {
        CobolCompiler.Result result = compile(
                List.of("    SYSOUT IS PRINTER."),
                List.of("01 WS-A PIC X."), "ACCEPT WS-A FROM PRINTER.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("input device"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("知らない機能名は誤りとして報告する (FR-135)")
    void anUnknownFunctionNameIsReported() {
        CobolCompiler.Result result = compile(
                List.of("    SYSPUNCH IS PUNCH."),
                List.of("01 WS-A PIC X."), "CONTINUE.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("unknown function name"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("同じ呼び名を 2 度書いたら誤りとして報告する (FR-135)")
    void aDuplicateMnemonicIsReported() {
        CobolCompiler.Result result = compile(
                List.of("    CONSOLE IS TERM", "    SYSOUT IS TERM."),
                List.of("01 WS-A PIC X."), "CONTINUE.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("duplicate mnemonic name"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("DECIMAL-POINT IS COMMA はまだ書けないと報告する (FR-054)")
    void decimalPointIsCommaIsReportedAsUnsupported() {
        // 小数点の入れ替えは数字定数の綴りにも効くので、字句の切り出しまで遡る
        CobolCompiler.Result result = compile(
                List.of("    DECIMAL-POINT IS COMMA."),
                List.of("01 WS-A PIC X."), "CONTINUE.");

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().get(0).message().contains("DECIMAL-POINT"),
                result.diagnostics().toString());
    }

    @Test
    @DisplayName("SOURCE-COMPUTER と OBJECT-COMPUTER は読み飛ばす (FR-093)")
    void theComputerParagraphsAreSkipped() {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. HELLO.",
                "ENVIRONMENT DIVISION.",
                "CONFIGURATION SECTION.",
                "SOURCE-COMPUTER. IBM-Z16.",
                "OBJECT-COMPUTER. IBM-Z16 MEMORY SIZE 4096 WORDS.",
                "SPECIAL-NAMES.",
                "    CURRENCY SIGN IS 'Y'.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-N PIC 9(3)V99 VALUE 1.25.",
                "01 WS-E PIC YYY9.99.",
                "PROCEDURE DIVISION.",
                "MAIN-START.",
                "    MOVE WS-N TO WS-E.")) {
            sb.append("       ").append(line).append('\n');
        }
        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, sb.toString());
        assertEquals("00125" + "  Y1.25",
                CodePages.DEFAULT.decode(load(result).runFresh().array()));
    }
}
