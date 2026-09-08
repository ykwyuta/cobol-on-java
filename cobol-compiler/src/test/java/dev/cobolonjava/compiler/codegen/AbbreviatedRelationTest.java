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
        return withStorage(List.of("01 WS-A PIC 9(4) VALUE " + value + "."), condition);
    }

    /** 2 つ目の項目も置く。 */
    private static String runWith(int value, int other, String... condition) {
        return withStorage(List.of(
                "01 WS-A PIC 9(4) VALUE " + value + ".",
                "01 WS-B PIC 9(4) VALUE " + other + "."), condition);
    }

    /** 条件名 (88 レベル) を持つ項目を置く。 */
    private static String runWithFlag(int value, String... condition) {
        return withStorage(List.of(
                "01 WS-A PIC 9(4) VALUE " + value + ".",
                "    88 A-IS-BIG VALUE 40 THRU 9999."), condition);
    }

    private static String withStorage(List<String> storage, String... condition) {
        StringBuilder sb = new StringBuilder();
        for (String line : List.of(
                "IDENTIFICATION DIVISION.",
                "PROGRAM-ID. ABBREV.",
                "DATA DIVISION.",
                "WORKING-STORAGE SECTION.")) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : storage) {
            FixedFormatSource.append(sb, line);
        }
        for (String line : List.of("PROCEDURE DIVISION.", "MAIN-START.")) {
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
        // 「= 30 OR > 10 AND < 21」は「(= 30) OR ((> 10) AND (< 21))」である。
        // 左から順に畳むと「((= 30 OR > 10) AND < 21)」になり、30 の答えが逆になる。
        // <b>30 でしか差が出ない</b>ので、この値を選んである
        String[] mixed = {
            "    IF WS-A = 30 OR > 10 AND < 21",
            "        DISPLAY 'HIT' ELSE DISPLAY 'MISS' END-IF."};

        assertEquals("HIT", run(30, mixed));
        assertEquals("HIT", run(15, mixed));
        assertEquals("MISS", run(5, mixed));
        assertEquals("MISS", run(50, mixed));
    }

    @Test
    @DisplayName("省いた値は式でもよい (FR-046)")
    void anAbbreviatedValueMayBeAnExpression() {
        // 「= 8 OR WS-B - 1」の最後は「= WS-B - 1」である
        String[] mixed = {
            "    IF WS-A = 8 OR WS-B - 1",
            "        DISPLAY 'HIT' ELSE DISPLAY 'MISS' END-IF."};

        assertEquals("HIT", runWith(8, 40, mixed));
        assertEquals("HIT", runWith(39, 40, mixed));
        assertEquals("MISS", runWith(40, 40, mixed));
    }

    @Test
    @DisplayName("名前 1 個が条件名なら、条件名条件として読む (FR-046)")
    void aBareNameThatIsAConditionNameStaysACondition() {
        // 「AND B」の B が 88 レベルなら、省略した比較ではない。
        // 文法では見分けられず、<b>名前を引かないと決まらない</b>
        String[] withFlag = {
            "    IF WS-A > 10 AND A-IS-BIG",
            "        DISPLAY 'HIT' ELSE DISPLAY 'MISS' END-IF."};

        assertEquals("HIT", runWithFlag(50, withFlag));
        assertEquals("MISS", runWithFlag(15, withFlag));
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
