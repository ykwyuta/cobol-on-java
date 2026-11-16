package dev.cobolonjava.job;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * ジョブ実行のコマンドライン入口 (要件 FR-130, FR-132)。
 *
 * <pre>
 * cobolj [-d クラスの置き場] [-w 作業領域] [-b データセットの基点] [-p 手続きの入れ物]
 *        [--emit-java] ジョブ記述
 * </pre>
 *
 * <p>記述形式は拡張子で見分ける。{@code .jcl} なら JCL、それ以外は宣言的形式である。
 * どちらも同じ内部モデルへ落ちるので、結果は同じになる (要件 FR-132)。
 *
 * <p>プロセスの終了コードは<b>ジョブの終了コード</b>である。いちばん大きいステップの
 * 復帰コードであり、後続のジョブがそれを見て動く。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args, false);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(
                    "usage: cobolj [-d classes] [-w work] [-b base] [-p proclib]"
                            + " [--emit-java] job-description");
            System.exit(2);
            return;
        }

        try {
            JobDescription.Result parsed =
                    JobDescription.read(options.description(), options.procedures());
            if (!parsed.succeeded()) {
                for (JobDiagnostic diagnostic : parsed.diagnostics()) {
                    System.err.println(options.description() + ":" + diagnostic);
                }
                System.exit(2);
                return;
            }
            if (options.emitJava()) {
                JobJavaSource.write(options.directory(), parsed.job());
                return;
            }
            System.exit(run(parsed.job(), options));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 生成した Java の起動口。展開済みのジョブを同じ実行機構で流す。 */
    public static int runGenerated(Job job, String[] args) {
        Options options;
        try {
            options = Options.parse(args, true);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println("usage: [-d classes] [-w work] [-b base]");
            return 2;
        }
        return run(job, options);
    }

    private static int run(Job job, Options options) {
        JobRunner.Result result = JobRunner.at(options.work(), loaderFor(options.directory()), System.out)
                .withBase(options.base())
                .run(job);
        for (JobRunner.StepOutcome step : result.steps()) {
            System.err.println(describe(job.name(), step));
        }
        return result.returnCode();
    }

    private record Options(Path directory, Path work, Path base, Path procedures,
                           Path description, boolean emitJava) {
        static Options parse(String[] args, boolean generated) {
            Path directory = Path.of(".");
            Path work = Path.of("work");
            Path base = Path.of(".");
            Path procedures = null;
            Path description = null;
            boolean emitJava = false;
            for (int i = 0; i < args.length; i++) {
                String option = args[i];
                switch (option) {
                    case "-d" -> directory = path(args, ++i, option);
                    case "-w" -> work = path(args, ++i, option);
                    case "-b" -> base = path(args, ++i, option);
                    case "-p" -> {
                        if (generated) throw new IllegalArgumentException("unknown option: " + option);
                        procedures = path(args, ++i, option);
                    }
                    case "--emit-java" -> {
                        if (generated) throw new IllegalArgumentException("unknown option: " + option);
                        emitJava = true;
                    }
                    default -> {
                        if (generated || option.startsWith("-") || description != null) {
                            throw new IllegalArgumentException("unexpected argument: " + option);
                        }
                        description = Path.of(option);
                    }
                }
            }
            if (!generated && description == null) {
                throw new IllegalArgumentException("no job description given");
            }
            return new Options(directory, work, base, procedures, description, emitJava);
        }

        private static Path path(String[] args, int index, String option) {
            if (index == args.length) {
                throw new IllegalArgumentException(option + " requires a path");
            }
            return Path.of(args[index]);
        }
    }

    /** ステップの結末を、ジョブログの 1 行として書く。 */
    private static String describe(String job, JobRunner.StepOutcome step) {
        return switch (step.status()) {
            case EXECUTED -> job + "." + step.name() + " ENDED - RC=" + step.returnCode();
            case BYPASSED -> job + "." + step.name() + " NOT EXECUTED";
            case ABENDED -> job + "." + step.name() + " ABENDED"
                    + (step.abendCode() == null ? "" : " " + step.abendCode().text())
                    + " - " + step.failure();
            case FAILED -> job + "." + step.name() + " JCL ERROR - " + step.failure();
            case FLUSHED -> job + "." + step.name() + " FLUSHED";
        };
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
