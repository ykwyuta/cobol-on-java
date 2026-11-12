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

    @Test
    @DisplayName("on-unit は呼んだ先でも効き、REVERT で外の on-unit に戻る")
    void handlesConditionsDynamically() throws Exception {
        assertEquals("1,12,13", run("""
                DCL FLAG FIXED BIN(31) INIT(0);
                DCL BAD CONDITION;
                ON CONDITION(BAD) FLAG=FLAG+1;
                CALL CHILD;
                PUT SKIP LIST(FLAG);
                CALL OVERRIDE;
                PUT SKIP LIST(FLAG);
                SIGNAL CONDITION(BAD);
                PUT SKIP LIST(FLAG);
                CHILD: PROC; SIGNAL CONDITION(BAD); END CHILD;
                OVERRIDE: PROC;
                  ON CONDITION(BAD) FLAG=FLAG+10;
                  SIGNAL CONDITION(BAD);
                  REVERT CONDITION(BAD);
                  SIGNAL CONDITION(BAD);
                END OVERRIDE;
                """));
    }

    @Test
    @DisplayName("ZERODIVIDE の on-unit から GO TO で抜け、ONCODE は 320 である")
    void handlesZeroDivide() throws Exception {
        assertEquals("320", run("""
                DCL CODE FIXED BIN(31), (R, Z) FIXED BIN(31) INIT(0);
                ON ZERODIVIDE BEGIN; CODE=ONCODE(); GO TO AFTER; END;
                R=1/Z;
                AFTER:;
                PUT SKIP LIST(CODE);
                """));
    }

    @Test
    @DisplayName("CONVERSION の on-unit が ONCHAR を直すと変換をやり直す。元の字は変わらない")
    void repairsConversions() throws Exception {
        assertEquals("12345,X,12X45", run("""
                DCL S CHAR(5) INIT('12X45');
                DCL N FIXED DEC(5), SEEN CHAR(1);
                ON CONVERSION BEGIN; SEEN=ONCHAR(); ONCHAR()='3'; END;
                N=S;
                PUT SKIP LIST(N);
                PUT SKIP LIST(SEEN);
                PUT SKIP LIST(S);
                """));
    }

    @Test
    @DisplayName("SIZE は前置き (SIZE): を書いたときだけ起きる")
    void raisesSizeOnlyWhenEnabled() throws Exception {
        assertEquals("0,2", run("""
                DCL FLAG FIXED BIN(31) INIT(0);
                DCL SMALL FIXED DEC(2);
                ON SIZE FLAG=2;
                SMALL=123;
                PUT SKIP LIST(FLAG);
                (SIZE): SMALL=123;
                PUT SKIP LIST(FLAG);
                """));
    }
}
