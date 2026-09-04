package dev.cobolonjava.job;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ジョブ実行のコマンドライン入口 (要件 FR-130, FR-132)。
 *
 * <pre>
 * cobolj [-d クラスの置き場] [-w 作業領域] ジョブ記述
 * </pre>
 *
 * <p>プロセスの終了コードは<b>ジョブの終了コード</b>である。いちばん大きいステップの
 * 復帰コードであり、後続のジョブがそれを見て動く。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Path classes = Path.of(".");
        Path work = Path.of("work");
        Path description = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-d" -> classes = Path.of(args[++i]);
                case "-w" -> work = Path.of(args[++i]);
                default -> description = Path.of(args[i]);
            }
        }
        if (description == null) {
            System.err.println("usage: cobolj [-d classes] [-w work] job-description");
            System.exit(2);
            return;
        }

        JobScript.Result script = JobScript.read(read(description));
        if (!script.succeeded()) {
            for (JobDiagnostic diagnostic : script.diagnostics()) {
                System.err.println(description + ":" + diagnostic);
            }
            System.exit(2);
            return;
        }

        JobRunner.Result result = JobRunner.at(work, loaderFor(classes), System.out)
                .run(script.job());
        for (JobRunner.StepOutcome step : result.steps()) {
            System.err.println(describe(script.job().name(), step));
        }
        System.exit(result.returnCode());
    }

    /** ステップの結末を、ジョブログの 1 行として書く。 */
    private static String describe(String job, JobRunner.StepOutcome step) {
        return switch (step.status()) {
            case EXECUTED -> job + "." + step.name() + " ENDED - RC=" + step.returnCode();
            case BYPASSED -> job + "." + step.name() + " NOT EXECUTED";
            case ABENDED -> job + "." + step.name() + " ABENDED - " + step.failure();
        };
    }

    private static String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    private static ClassLoader loaderFor(Path classes) {
        try {
            return new URLClassLoader(new URL[] {classes.toUri().toURL()},
                    Main.class.getClassLoader());
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("bad class directory: " + classes, e);
        }
    }
}
