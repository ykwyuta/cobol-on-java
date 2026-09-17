package dev.cobolonjava.verify.pli;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.cobolonjava.verify.VerifySupport;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 外部 PL/I コーパスを継続測定する。合格率はビルドの門にしない。 */
@Tag("V2")
class PliCorpusSuiteTest {

    @Test
    void measuresExternalPliCorpusWhenAvailable() {
        Path root = VerifySupport.requirePliCorpus();

        PliVerificationReport report = PliVerificationRunner.standard()
                .run(PliSourceDirectory.read(root));

        assertFalse(report.outcomes().isEmpty(), "PLI_CORPUS に .pli または .pl1 が無い");
        System.out.println(report.text("PL/I 外部コーパス"));
    }
}
