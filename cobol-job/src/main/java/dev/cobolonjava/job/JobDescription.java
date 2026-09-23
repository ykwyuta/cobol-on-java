package dev.cobolonjava.job;

import dev.cobolonjava.job.jcl.Jcl;
import dev.cobolonjava.job.jcl.JclLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * ジョブ記述のファイルを内部ジョブモデルへ読む (要件 FR-131, FR-132)。
 *
 * <p>記述形式は拡張子で見分ける。{@code .jcl} なら JCL、それ以外は宣言的形式である。
 * どちらも同じ内部モデルへ落ちるので、結果は同じになる。
 *
 * <p>コマンドラインの {@link Main} と、ビルド時にジョブ記述を検める Maven プラグインが
 * 同じ規則で読むためにここへ置く。ビルドで通った記述が実行で断られる、という食い違いを作らない。
 */
public final class JobDescription {

    private JobDescription() {
    }

    /**
     * 読み取りの結果。
     *
     * @param job         組み立てたジョブ。誤りがあれば {@code null}
     * @param diagnostics 見つかった誤り。空なら成功
     */
    public record Result(Job job, List<JobDiagnostic> diagnostics) {

        public Result {
            diagnostics = List.copyOf(diagnostics);
        }

        public boolean succeeded() {
            return diagnostics.isEmpty();
        }
    }

    /** JCL として読むファイルか。 */
    public static boolean isJcl(Path description) {
        return description.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jcl");
    }

    /**
     * ジョブ記述を読む。
     *
     * @param procedures 目録手続きと {@code INCLUDE} の置き場。無ければ {@code null}。
     *                   宣言的形式では使わない
     */
    public static Result read(Path description, Path procedures) throws IOException {
        String text = new String(Files.readAllBytes(description), StandardCharsets.UTF_8);
        if (isJcl(description)) {
            Jcl.Result parsed = Jcl.read(text, procedures == null
                    ? JclLibrary.empty() : JclLibrary.at(procedures));
            return new Result(parsed.job(), parsed.diagnostics());
        }
        JobScript.Result parsed = JobScript.read(text);
        return new Result(parsed.job(), parsed.diagnostics());
    }
}
