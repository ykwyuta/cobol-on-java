package dev.cobolonjava.job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 展開済みジョブモデルから実行可能な Java ソースを作る。 */
public final class JobJavaSource {

    private static final String PACKAGE = "cobol.generated.job";

    private JobJavaSource() {
    }

    public static String className(Job job) {
        StringBuilder name = new StringBuilder("Job_");
        job.name().codePoints().forEach(code -> {
            if ((code >= 'A' && code <= 'Z') || (code >= 'a' && code <= 'z')
                    || (code >= '0' && code <= '9')) {
                name.appendCodePoint(code);
            } else {
                name.append('_').append(Integer.toHexString(code)).append('_');
            }
        });
        return name.toString();
    }

    public static void write(Path root, Job job) throws IOException {
        String className = className(job);
        List<String> steps = new ArrayList<>();
        StringBuilder methods = new StringBuilder();
        for (Step step : job.steps()) {
            String method = "step" + (steps.size() + 1);
            steps.add(method + "()");
            methods.append("""

                        private static Step %s() {
                            return new Step(%s, %s, %s,
                                    %s,
                                    %s);
                        }
                    """.formatted(method, literal(step.name()), literal(step.program()),
                    literal(step.parm()), list(step.dd(), JobJavaSource::assignment),
                    condition(step.condition())));
        }
        String source = """
                package %s;

                import dev.cobolonjava.job.*;
                import dev.cobolonjava.runtime.abend.AbendCode;
                import java.util.HexFormat;
                import java.util.List;

                public final class %s {
                    private %s() {
                    }

                    public static void main(String[] args) {
                        System.exit(Main.runGenerated(job(), args));
                    }

                    public static Job job() {
                        return new Job(%s, %s);
                    }
                %s}
                """.formatted(PACKAGE, className, className, literal(job.name()),
                list(steps, Function.identity()), methods);
        Path directory = root.resolve(PACKAGE.replace('.', '/'));
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(className + ".java"), source);
    }

    private static String assignment(DdAssignment dd) {
        return "new DdAssignment(%s, %s, %sL, %s, %s)".formatted(literal(dd.name()),
                target(dd.target()), dd.space(), dd.directoryBlocks(), dd.secondary());
    }

    private static String target(DdTarget target) {
        return switch (target) {
            case DdTarget.DataSet ds -> "new DdTarget.DataSet(%s, %s, %s, %s, %s)".formatted(
                    literal(ds.name()), literal(ds.member()), literal(ds.serial()),
                    disposition(ds.disposition()), ds.generation());
            case DdTarget.Temporary ds -> "new DdTarget.Temporary(%s, %s)".formatted(
                    literal(ds.name()), disposition(ds.disposition()));
            case DdTarget.Sysout ignored -> "new DdTarget.Sysout()";
            case DdTarget.Dummy ignored -> "new DdTarget.Dummy()";
            case DdTarget.Inline inline -> "new DdTarget.Inline(" + bytes(inline.data()) + ")";
            case DdTarget.Concatenation cat -> "new DdTarget.Concatenation("
                    + list(cat.parts(), JobJavaSource::target) + ")";
        };
    }

    private static String disposition(Disposition disp) {
        return "new Disposition(Disposition.Status.%s, Disposition.Action.%s, Disposition.Action.%s)"
                .formatted(disp.status().name(), disp.normal().name(), disp.abnormal().name());
    }

    private static String condition(StepCondition condition) {
        return switch (condition) {
            case StepCondition.Always ignored -> "new StepCondition.Always()";
            case StepCondition.ReturnCode rc ->
                    "new StepCondition.ReturnCode(%s, StepCondition.Comparison.%s, %s)"
                            .formatted(literal(rc.step()), rc.comparison().name(), rc.value());
            case StepCondition.Not not -> "new StepCondition.Not(" + condition(not.inner()) + ")";
            case StepCondition.All all -> "new StepCondition.All("
                    + list(all.parts(), JobJavaSource::condition) + ")";
            case StepCondition.Any any -> "new StepCondition.Any("
                    + list(any.parts(), JobJavaSource::condition) + ")";
            case StepCondition.Ran ran -> "new StepCondition.Ran(" + literal(ran.step()) + ")";
            case StepCondition.EvenIfAbend ignored -> "new StepCondition.EvenIfAbend()";
            case StepCondition.OnlyIfAbend ignored -> "new StepCondition.OnlyIfAbend()";
            case StepCondition.Abend abend -> "new StepCondition.Abend(%s, %s, %s)"
                    .formatted(literal(abend.step()), abend.equal(),
                            abend.code() == null ? "null" : "AbendCode." + abend.code().name());
        };
    }

    private static String bytes(byte[] data) {
        // 文字列定数一つに載る長さに分けて、コンパイル時の再結合を避ける。
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < data.length; start += 8192) {
            chunks.add(literal(HexFormat.of().formatHex(data, start,
                    Math.min(start + 8192, data.length))));
        }
        return "HexFormat.of().parseHex(String.join(\"\", " + list(chunks, Function.identity())
                + "))";
    }

    private static <T> String list(List<T> values, Function<T, String> expression) {
        return values.stream().map(expression).collect(Collectors.joining(", ", "List.of(", ")"));
    }

    private static String literal(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\', '"' -> escaped.append('\\').append(c);
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (Character.isISOControl(c)) {
                        escaped.append("\\%03o".formatted((int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}
