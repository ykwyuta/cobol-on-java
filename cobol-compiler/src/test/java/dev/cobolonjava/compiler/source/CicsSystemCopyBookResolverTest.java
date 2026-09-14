package dev.cobolonjava.compiler.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.bms.BmsAid;
import dev.cobolonjava.compiler.CobolCompiler;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CICS 提供の写し句を公開仕様の値から作る。 */
@Tag("V1")
class CicsSystemCopyBookResolverTest {

    @Test
    @DisplayName("DFHAIDのAID定数でEIBAIDを判定する文が翻訳できる")
    void compilesAidComparisons() {
        String source = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. AIDTEST.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       COPY DFHAID.",
                "       01  WS-KEY PIC X VALUE X'7D'.",
                "       PROCEDURE DIVISION.",
                "           EVALUATE TRUE",
                "              WHEN WS-KEY = DFHPA1 OR DFHPA2 OR DFHPA3",
                "              WHEN WS-KEY = DFHPF3",
                "              WHEN WS-KEY = DFHCLEAR",
                "              WHEN WS-KEY = DFHENTER",
                "                 CONTINUE",
                "           END-EVALUATE.",
                "           GOBACK.") + "\n";

        CobolCompiler.Result result = CobolCompiler.with(new CicsSystemCopyBookResolver())
                .compile("AIDTEST.cbl", source);

        assertTrue(result.succeeded(), result.diagnostics().toString());
    }

    @Test
    @DisplayName("AIDの値は3270データストリームの公開値であり、16進定数で書く")
    void writesAidValuesAsHexLiterals() {
        String text = new CicsSystemCopyBookResolver().resolve("dfhaid", null)
                .orElseThrow().text();

        assertTrue(text.contains("02  DFHENTER PIC X VALUE X'7D'."), text);
        assertTrue(text.contains("02  DFHCLEAR PIC X VALUE X'6D'."), text);
        assertTrue(text.contains("02  DFHPF12  PIC X VALUE X'7C'."), text);
        assertTrue(text.contains("02  DFHPF24  PIC X VALUE X'4C'."), text);
        assertEquals(Optional.of(BmsAid.PF13), BmsAid.ofValue(0xC1));
        assertEquals(Optional.empty(), new CicsSystemCopyBookResolver().resolve("DFHAID", "SYSLIB"));
        // 公開仕様から作れない写し句は「見つからない」のままにする
        assertEquals(Optional.empty(), new CicsSystemCopyBookResolver().resolve("DFHEIBLK", null));
    }
}
