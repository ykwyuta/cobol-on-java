package dev.cobolonjava.verify.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.verify.VerifySupport;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 本物の OSS 資産を流す (要件 NFR-042)。
 *
 * <p>コーパスは同梱しないので、{@code COBOL_CORPUS} が指していなければスキップする。
 *
 * <p>ここでも<b>合格率は試験しない</b>。R-7 (実装漏れによる翻訳失敗) を早く見つけるのが
 * 目的であり、数に下限を課すと、資産を 1 つ足すたびにビルドが赤くなる。
 */
@Tag("V1")
class CorpusSuiteTest {

    @Test
    @DisplayName("置き場の資産をすべて流して数えられる (NFR-042)")
    void theWholeCorpusCanBeMeasured() {
        Path root = VerifySupport.requireCorpus();

        List<CorpusRunner.Source> sources = SourceDirectory.read(root);
        CorpusReport report = CorpusRunner.standard().run(sources);

        assertFalse(sources.isEmpty(), "no COBOL sources under " + root);
        assertEquals(sources.size(), report.outcomes().size());
        // 1 本が壊れても残りは流れる。流せた本数が入力と合っていることがその証である
        assertEquals(sources.size(),
                report.compiled() + report.rejected() + report.crashed());
        assertTrue(report.csv().contains("ALL,"), report.csv());
    }
}
