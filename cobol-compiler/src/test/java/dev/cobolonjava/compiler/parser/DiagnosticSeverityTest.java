package dev.cobolonjava.compiler.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.source.Origin;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 診断の重大度 (要件 FR-183)。
 *
 * <p>重大度は「翻訳を止めるかどうか」である。規格に沿わないが意味の決まる書き方は
 * <b>告げて通す</b>。止めてしまうと、その先にある本当の誤りが見えなくなる。
 */
@Tag("V1")
class DiagnosticSeverityTest {

    private static final Origin AT = new Origin("MAIN.cbl", 12, 8);

    @Test
    @DisplayName("重大度を書かなければ、翻訳を止める誤りである (FR-183)")
    void theDefaultSeverityStopsTheCompilation() {
        Diagnostic diagnostic = new Diagnostic(AT, "something is wrong");
        assertEquals(Diagnostic.Severity.ERROR, diagnostic.severity());
        assertTrue(diagnostic.severity().blocking());
    }

    @Test
    @DisplayName("警告は翻訳を止めない (FR-183)")
    void aWarningDoesNotStopTheCompilation() {
        Diagnostic diagnostic = Diagnostic.warning(AT, "this is not standard");
        assertFalse(diagnostic.severity().blocking());
        assertTrue(diagnostic.isWarning());
        assertFalse(Diagnostic.blocking(List.of(diagnostic)));
    }

    @Test
    @DisplayName("1 件でも止めるものがあれば止まる (FR-183)")
    void oneBlockingDiagnosticIsEnough() {
        assertTrue(Diagnostic.blocking(List.of(
                Diagnostic.warning(AT, "this is not standard"),
                new Diagnostic(AT, "something is wrong"))));
    }

    @Test
    @DisplayName("誤りの文面は位置から始まる — 印を足さない (FR-183)")
    void anErrorKeepsItsPlainWording() {
        // 診断の文面を数え上げる道具は、位置を落としてから同じ理由をまとめている。
        // 位置の前に印を足すと落とせなくなる
        assertEquals("MAIN.cbl:12:8: something is wrong",
                new Diagnostic(AT, "something is wrong").toString());
    }

    @Test
    @DisplayName("警告には位置の後ろに印を付ける (FR-183)")
    void aWarningIsLabelledAfterItsPosition() {
        assertEquals("MAIN.cbl:12:8: warning: this is not standard",
                Diagnostic.warning(AT, "this is not standard").toString());
    }
}
