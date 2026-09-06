package dev.cobolonjava.runtime.abend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.cobolonjava.runtime.decimal.DataException;
import dev.cobolonjava.runtime.decimal.DecimalDivideException;
import dev.cobolonjava.runtime.program.FileOperationException;
import dev.cobolonjava.runtime.program.ProgramNotFoundException;
import dev.cobolonjava.runtime.program.RangeCheckException;
import java.io.UncheckedIOException;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 異常終了コード (要件 FR-141)。
 *
 * <p>どの条件がどのコードになるかは<b>条件そのものが名乗る</b>。1 か所の対応表にすると、
 * 条件を足したときに表を直し忘れる。
 */
@Tag("V1")
class AbendCodeTest {

    @Test
    @DisplayName("条件を表す例外は自分のコードを名乗る (FR-141)")
    void causesNameTheirOwnCode() {
        assertEquals(AbendCode.S0C7, Abend.codeOf(new DataException("bad digit")));
        assertEquals(AbendCode.S0CB, Abend.codeOf(new DecimalDivideException("by zero")));
        assertEquals(AbendCode.S806,
                Abend.codeOf(new ProgramNotFoundException("SUB1", null)));
        assertEquals(AbendCode.U4038, Abend.codeOf(new RangeCheckException("out of range")));
        assertEquals(AbendCode.U4038, Abend.codeOf(new FileOperationException("IN", "35")));
    }

    @Test
    @DisplayName("包まれてももとの条件のコードが残る (FR-141)")
    void wrappedCausesKeepTheirCode() {
        RuntimeException wrapped = new IllegalStateException("while reading",
                new DataException("bad digit"));
        assertEquals(AbendCode.S0C7, Abend.codeOf(wrapped));

        UncheckedIOException deeper = new UncheckedIOException(
                new IOException("io", new DecimalDivideException("by zero")));
        assertEquals(AbendCode.S0CB, Abend.codeOf(deeper));
    }

    @Test
    @DisplayName("異常終了にならない誤りはコードを持たない (FR-141)")
    void ordinaryFailuresHaveNoCode() {
        assertNull(Abend.codeOf(new IllegalStateException("something else")));
        assertNull(Abend.codeOf(null));
    }

    @Test
    @DisplayName("自分を原因に持つ誤りでも回り続けない (FR-141)")
    void aSelfReferencingCauseTerminates() {
        RuntimeException loop = new RuntimeException("loop") {
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertNull(Abend.codeOf(loop));
    }

    @Test
    @DisplayName("綴りからコードを読める (FR-141)")
    void codesAreReadFromTheirSpelling() {
        assertSame(AbendCode.S0C7, AbendCode.of("S0C7"));
        assertSame(AbendCode.U4038, AbendCode.of("u4038"));
        assertNull(AbendCode.of("S9Z9"));
        assertNull(AbendCode.of(null));
    }

    @Test
    @DisplayName("直に投げるコードは綴りを覚え書きへ入れる (FR-141)")
    void aDirectAbendCarriesItsSpelling() {
        Abend abend = new Abend(AbendCode.S0C4, "no argument for LK-ITEM");
        assertEquals(AbendCode.S0C4, abend.abendCode());
        assertEquals("S0C4 no argument for LK-ITEM", abend.getMessage());
    }
}
