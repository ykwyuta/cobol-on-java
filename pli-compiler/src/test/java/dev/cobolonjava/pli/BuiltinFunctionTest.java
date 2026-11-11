package dev.cobolonjava.pli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.program.CobolProgram;
import dev.cobolonjava.runtime.program.ProgramContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 組み込み関数と、記憶域の形を見る関数 (UNSPEC、HEX)。期待値は Enterprise PL/I Language
 * Reference の各頁と、Zeus の PL/I の検査スイート (ZPVS) の PD・PM の区分と同じである。
 * 実機とは突き合わせていない。
 */
class BuiltinFunctionTest {

    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader() {
            super(BuiltinFunctionTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] classFile) {
            return defineClass(name, classFile, 0, classFile.length);
        }
    }

    private static String run(String body) throws Exception {
        PliCompiler.Result result = PliCompiler.standard().compile("BIF.pli",
                "BIF: PROCEDURE OPTIONS(MAIN);\n" + body + "\nEND BIF;\n");
        assertTrue(result.succeeded(), () -> result.diagnostics().toString());
        CobolProgram program = (CobolProgram) new GeneratedLoader()
                .define(result.className(), result.classFile())
                .getDeclaredConstructor().newInstance();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        program.runFresh(ProgramContext.capturing(output));
        return String.join(",", output.toString(StandardCharsets.UTF_8).strip()
                .lines().map(String::strip).toList());
    }

    @Test
    @DisplayName("UNSPEC と HEX は記憶域を見る。10 進は詰め 10 進、UNSIGNED の 2 進は符号を持たない")
    void showsTheStorage() throws Exception {
        assertEquals("099D,FF,01010000", run("""
                DCL D FIXED DEC(3);
                DCL U FIXED BIN(8) UNSIGNED;
                DCL B BIT(8);
                D = -99;
                U = 255;
                B = 5;
                PUT SKIP LIST(HEX(UNSPEC(D)));
                PUT SKIP LIST(HEX(UNSPEC(U)));
                PUT SKIP LIST(B);
                """).replace("'", "").replace("B", ""));
    }

    @Test
    @DisplayName("文字列の関数")
    void handlesStrings() throws Exception {
        assertEquals("EDCBA,3,0,2,AXC,ABC**", run("""
                PUT SKIP LIST(REVERSE('ABCDE'));
                PUT SKIP LIST(INDEX('ABCDE', 'CD'));
                PUT SKIP LIST(VERIFY('123', '0123456789'));
                PUT SKIP LIST(TALLY('ABABA', 'AB'));
                PUT SKIP LIST(TRANSLATE('ABC', 'X', 'B'));
                PUT SKIP LIST(LEFT('ABC', 5, '*'));
                """));
    }

    @Test
    @DisplayName("DAYS は 1582 年 10 月 15 日を 1 とする日の番号を返し、DAYSTODATE で戻る")
    void countsLilianDays() throws Exception {
        assertEquals("1,2,20000229", run("""
                PUT SKIP LIST(DAYS('15821015', 'YYYYMMDD'));
                PUT SKIP LIST(DAYS('20000301','YYYYMMDD') - DAYS('20000228','YYYYMMDD'));
                PUT SKIP LIST(DAYSTODATE(DAYS('20000229','YYYYMMDD'), 'YYYYMMDD'));
                """));
    }
}
