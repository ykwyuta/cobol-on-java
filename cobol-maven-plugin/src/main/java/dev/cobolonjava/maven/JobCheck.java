package dev.cobolonjava.maven;

import dev.cobolonjava.job.JobDescription;
import dev.cobolonjava.job.JobDiagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * ジョブ記述を検め、成果物へ載せる手順 (設計 91 §4)。
 *
 * <p>JCL に「翻訳」は無い。実行時に {@code cobolj} が読むのと<b>同じ読み取り</b>
 * ({@link JobDescription}) をビルドの時点で通し、誤りを実行より前に出す。目録手続きの
 * 展開もここで通すので、{@code EXEC 手続き名} の書き誤りや {@code PROCLIB} に無い手続きも
 * ビルドで分かる。
 *
 * <p>検めたものは {@code META-INF/cobol/jcl/} と {@code META-INF/cobol/proclib/} へ
 * 下位ディレクトリごと写す。配る単位 (jar) にプログラムとジョブを一緒に入れておくためである。
 */
final class JobCheck {

    static final String JCL_RESOURCE = "META-INF/cobol/jcl";
    static final String PROCLIB_RESOURCE = "META-INF/cobol/proclib";

    private JobCheck() {
    }

    static void run(SourceLayout layout, Path output, Report report)
            throws IOException, BuildFailure {
        List<Path> descriptions = layout.jobDescriptions();
        Path proclib = Files.isDirectory(layout.proclib()) ? layout.proclib() : null;
        int failures = 0;
        for (Path description : descriptions) {
            JobDescription.Result result = JobDescription.read(description, proclib);
            if (!result.succeeded()) {
                for (JobDiagnostic diagnostic : result.diagnostics()) {
                    report.error(description + ":" + diagnostic);
                }
                failures++;
                continue;
            }
            copy(layout.jcl(), description, output.resolve(JCL_RESOURCE));
        }
        if (proclib != null) {
            try (Stream<Path> members = Files.walk(proclib)) {
                for (Path member : members.filter(Files::isRegularFile).sorted().toList()) {
                    copy(proclib, member, output.resolve(PROCLIB_RESOURCE));
                }
            }
        }
        if (descriptions.isEmpty()) {
            report.info("no job descriptions in " + layout.jcl());
        } else {
            report.info("JCL: " + (descriptions.size() - failures) + " job(s) checked");
        }
        if (failures > 0) {
            throw new BuildFailure(failures + " job description(s) have errors");
        }
    }

    private static void copy(Path root, Path file, Path target) throws IOException {
        Path destination = target.resolve(root.relativize(file).toString());
        Files.createDirectories(destination.getParent());
        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
    }
}
