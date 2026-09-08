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
 * 省略した比較 (要件 FR-046)。
 *
 * <p>{@code IF A > 10 AND < 21} は {@code IF A > 10 AND A < 21} である。主語は
 * 引き継がれ、演算子は<b>書き直されるまで</b>引き継がれる。古い資産がよく使う書き方で
 * あり、読めないと条件文がまるごと通らない。
 */
@Tag("V1")
class AbbreviatedRelationTest {

    private static final String FILE = "MAIN.cbl";

    private static final class GeneratedLoader extends ClassLoader {

        private GeneratedLoader() {
            super(AbbreviatedRelationTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    /** 値を入れてから条件を試し、通った枝の印を出す。 */
    private static String run(int value, String... condition) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. ABBREV.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.",
                "01 WS-A PIC 9(4) VALUE " + value + ".",
                "PROCEDURE DIVISION.",
                "MAIN-START.")) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : condition) {
            FixedFormatSource.append(sb, line);
        }
        FixedFormatSource.append(sb, "    STOP RUN.");

        CobolCompiler.Result result = CobolCompiler.standard().compile(FILE, sb.toString());
        assertTrue(result.succeeded(), () -> "unexpected diagnostics: " + result.diagnostics());
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try {
            Class<?> type = new GeneratedLoader().define(result.className(), result.classFile());
            ((CobolProgram) type.getDeclaredConstructor().newInstance())
                    .runFresh(ProgramContext.capturing(sink));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot load the generated program", e);
        }
        return sink.toString(StandardCharsets.UTF_8).replace(System.lineSeparator(), "");
    }

    private static final String[] RANGE = {
        "    IF WS-A > 10 AND < 21",
        "        DISPLAY 'IN' ELSE DISPLAY 'OUT' END-IF."};

    @Test
    @DisplayName("演算子を書き直した比較は、主語を引き継ぐ (FR-046)")
    void anAbbreviationWithItsOwnOperatorKeepsTheSubject() {
        assertEquals("IN", run(15, RANGE));
        assertEquals("OUT", run(10, RANGE));
        assertEquals("OUT", run(21, RANGE));
    }

    @Test
    @DisplayName("値だけを並べた比較は、主語も演算子も引き継ぐ (FR-046)")
    void anAbbreviationOfOnlyAValueKeepsTheOperatorToo() {
        String[] anyOf = {
            "    IF WS-A EQUAL TO 1 OR 98 OR 99",
            "        DISPLAY 'HIT' ELSE DISPLAY 'MISS' END-IF."};

        assertEquals("HIT", run(1, anyOf));
        assertEquals("HIT", run(98, anyOf));
        assertEquals("HIT", run(99, anyOf));
        assertEquals("MISS", run(2, anyOf));
    }

    @Test
    @DisplayName("書き直した演算子は、そこから先へも引き継がれる (FR-046)")
    void arewrittenOperatorCarriesOnward() {
        // 「= 5 OR < 3 OR 200」の最後は「< 200」である。= には戻らない
        String[] mixed = {
            "    IF WS-A = 5 OR < 3 OR 200",
            "        DISPLAY 'HIT' ELSE DISPLAY 'MISS' END-IF."};

        assertEquals("HIT", run(5, mixed));
        assertEquals("HIT", run(2, mixed));
        // 100 は 5 でも 3 未満でもない。< 200 で当たる
        assertEquals("HIT", run(100, mixed));
        assertEquals("MISS", run(250, mixed));
    }

    @Test
    @DisplayName("AND は OR より先に結ばれる (FR-046)")
    void andBindsTighterThanOr() {
        // 「= 1 OR > 10 AND < 21」は「(= 1) OR ((> 10) AND (< 21))」である
        String[] mixed = {
            "    IF WS-A = 1 OR > 10 AND < 21",
            "        DISPLAY 'HIT' ELSE DISPLAY 'MISS' END-IF."};

        assertEquals("HIT", run(1, mixed));
        assertEquals("HIT", run(15, mixed));
        assertEquals("MISS", run(30, mixed));
    }

    @Test
    @DisplayName("省略しない比較を続けて書いてもよい (FR-046)")
    void aFullRelationMayStillFollow() {
        // 「AND B」の B が名前なら、省略した比較ではなく普通の条件である。
        // ここを取り違えると、正しいプログラムが読めなくなる
        String[] full = {
            "    IF WS-A > 10 AND WS-A < 21",
            "        DISPLAY 'IN' ELSE DISPLAY 'OUT' END-IF."};

        assertEquals("IN", run(15, full));
        assertEquals("OUT", run(30, full));
    }
}
