package dev.cobolonjava.runtime.abend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.decimal.DataException;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.Storage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 異常終了の診断出力 (要件 FR-142, FR-143)。
 *
 * <p>異常終了コードだけでは、どこで何が起きたのか分からない。本番で一度だけ起きた事故を
 * 追うには、止まった場所と記憶域の中身が要る。
 */
@Tag("V1")
class DiagnosisTest {

    private static ProgramContext context() {
        return ProgramContext.standard();
    }

    private static String textOf(List<String> lines) {
        return String.join("|", lines);
    }

    @Test
    @DisplayName("異常終了コードと理由を書く (FR-142)")
    void theCodeAndReasonAreWritten() {
        String text = textOf(Diagnosis.of(AbendCode.S0C7,
                new DataException("invalid digit"), context()));

        assertTrue(text.contains("ABEND S0C7 WAS ISSUED"), text);
        assertTrue(text.contains("data exception"), text);
        assertTrue(text.contains("invalid digit"), text);
    }

    @Test
    @DisplayName("コードが分からなくても覚え書きは書く (FR-142)")
    void anUnknownCodeStillGetsAMessage() {
        String text = textOf(Diagnosis.of(null,
                new IllegalStateException("something went wrong"), context()));

        assertTrue(text.contains("UNHANDLED CONDITION"), text);
        assertTrue(text.contains("something went wrong"), text);
    }

    @Test
    @DisplayName("翻訳したプログラムが履歴に無ければそう書く (FR-142)")
    void anEmptyCallChainIsSaidSo() {
        String text = textOf(Diagnosis.of(AbendCode.S0C7,
                new DataException("invalid digit"), context()));

        assertTrue(text.contains("TRACEBACK"), text);
        assertTrue(text.contains("no compiled program"), text);
    }

    @Test
    @DisplayName("QUIET は何も書かない (FR-143)")
    void quietWritesNothing() {
        ProgramContext context = context();
        context.setDumpLevel(DumpLevel.QUIET);

        assertEquals(List.of(),
                Diagnosis.of(AbendCode.S0C7, new DataException("bad"), context));
    }

    @Test
    @DisplayName("MSG は覚え書きだけを書く (FR-143)")
    void msgWritesOnlyTheMessage() {
        ProgramContext context = context();
        context.setDumpLevel(DumpLevel.MSG);

        String text = textOf(Diagnosis.of(AbendCode.S0C7, new DataException("bad"), context));
        assertTrue(text.contains("ABEND S0C7"), text);
        assertFalse(text.contains("TRACEBACK"), text);
    }

    @Test
    @DisplayName("DUMP は動いていたプログラムの記憶域を書く (FR-142, FR-143)")
    void dumpWritesTheStorage() {
        ProgramContext context = context();
        context.setDumpLevel(DumpLevel.DUMP);
        context.enter("PAYCHK", Storage.copyOf(CodePages.DEFAULT.encode("ABC12345")));

        String text = textOf(Diagnosis.of(AbendCode.S0C7, new DataException("bad"), context));
        assertTrue(text.contains("STORAGE FOR PAYCHK"), text);
        // 16 進が正で、文字は目で追うための添え物である
        assertTrue(text.contains("C1C2C3F1 F2F3F4F5"), text);
        assertTrue(text.contains("|ABC12345"), text);
    }

    @Test
    @DisplayName("記憶域を持たないプログラムでも書ける (FR-142)")
    void aProgramWithoutStorageIsFine() {
        ProgramContext context = context();
        context.setDumpLevel(DumpLevel.DUMP);
        context.enter("IEFBR14", Storage.allocate(0));

        String text = textOf(Diagnosis.of(AbendCode.S806, new IllegalStateException("x"), context));
        assertTrue(text.contains("no working storage"), text);
    }

    @Test
    @DisplayName("内側のプログラムが先に並ぶ (FR-142)")
    void theInnermostProgramComesFirst() {
        ProgramContext context = context();
        context.setDumpLevel(DumpLevel.DUMP);
        context.enter("OUTER", Storage.allocate(1));
        context.enter("INNER", Storage.allocate(1));

        String text = textOf(Diagnosis.of(AbendCode.S0C7, new DataException("bad"), context));
        assertTrue(text.indexOf("STORAGE FOR INNER") < text.indexOf("STORAGE FOR OUTER"), text);
    }

    @Test
    @DisplayName("正常に戻ったプログラムは積まれたまま残らない (FR-142)")
    void aProgramThatReturnedIsNotOnTheStack() {
        ProgramContext context = context();
        context.enter("SUB1", Storage.allocate(1));
        context.leave();

        assertEquals(List.of(), context.active());
    }

    @Test
    @DisplayName("TERMTHDACT の綴りを読む (FR-143)")
    void levelsAreReadFromTheirSpelling() {
        assertSame(DumpLevel.QUIET, DumpLevel.of("QUIET"));
        assertSame(DumpLevel.TRACE, DumpLevel.of("trace"));
        assertSame(DumpLevel.DUMP, DumpLevel.of("UADUMP"));
        assertSame(DumpLevel.MSG, DumpLevel.of("UAONLY"));
        assertSame(null, DumpLevel.of("LOUD"));
        assertSame(null, DumpLevel.of(null));
    }
}
