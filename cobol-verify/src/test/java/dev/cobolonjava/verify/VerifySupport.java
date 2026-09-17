package dev.cobolonjava.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

/**
 * 外にあるコーパスを使う試験の共通補助 (要件 NFR-040, NFR-042)。
 *
 * <p>コーパスはリポジトリに同梱しない (要件 NFR-042)。だから<b>無い環境がふつう</b>で
 * あり、無いときはテストをスキップする。Hercules を使う V2 の試験と同じ構えである。
 * 検査の道具の有無でビルドが壊れてはならない。
 *
 * <ul>
 *   <li>{@code CCVS85} — {@code newcob.val} の在り処
 *   <li>{@code COBOL_CORPUS} — OSS 資産の置き場
 *   <li>{@code PLI_CORPUS} — PL/I 資産と参照出力の置き場
 * </ul>
 */
public final class VerifySupport {

    private VerifySupport() {
    }

    /** CCVS85 の配布物。無ければ理由を示してスキップする。 */
    public static Path requireCcvs85() {
        return required("CCVS85", "tools/verify/fetch-ccvs85.sh で取ってくること");
    }

    /** OSS 資産の置き場。無ければ理由を示してスキップする。 */
    public static Path requireCorpus() {
        return required("COBOL_CORPUS", "tools/verify/fetch-corpus.sh で取ってくること");
    }

    /** PL/I の外部コーパス。無ければ理由を示してスキップする。 */
    public static Path requirePliCorpus() {
        return required("PLI_CORPUS", "設計 26 に従って外部に用意すること");
    }

    private static Path required(String variable, String how) {
        String written = System.getenv(variable);
        if (written == null || written.isBlank()) {
            Assumptions.abort(variable + " が設定されていないのでスキップする。" + how);
        }
        Path path = Path.of(written);
        if (!Files.exists(path)) {
            Assumptions.abort(variable + " の指す先が無いのでスキップする: " + path);
        }
        return path;
    }
}
