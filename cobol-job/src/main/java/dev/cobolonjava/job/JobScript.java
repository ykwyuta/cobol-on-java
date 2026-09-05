package dev.cobolonjava.job;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 宣言的形式のジョブ記述 (要件 FR-132)。
 *
 * <p>内部ジョブモデルを<b>そのまま書ける</b>形式である。新しく組むジョブと、この処理系自身の
 * 試験に使う。JCL と同じ内部モデルへ落ちるので、どちらで書いても結果は同じになる。
 *
 * <h2>書き方</h2>
 * <pre>
 * # 行頭の # は注記
 * JOB PAYROLL
 * STEP EXTRACT PGM=PAYEXT PARM=202609
 *   DD PAYIN DSN=data/pay.dat
 *   DD PAYOUT DSN=work/extract.dat
 *   DD SYSOUT SYSOUT
 * STEP REPORT PGM=PAYRPT
 *   WHEN RC EXTRACT = 0
 *   DD RPTOUT SYSOUT
 *   DD SYSIN DATA
 *     DETAIL
 *     TOTAL
 *   END
 * STEP CLEANUP PGM=PAYCLN
 *   WHEN ABEND
 *   DD SYSOUT SYSOUT
 * </pre>
 *
 * <p>字下げに意味はない。読みやすさのためだけのものである。
 *
 * <h2>知らない書き方は誤りにする</h2>
 * <p>読み飛ばさない。書いたつもりの指定が効いていないことに気付けないからである。
 * これは JCL のフロントエンドでも同じ方針である (要件 FR-131)。
 */
public final class JobScript {

    private JobScript() {
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

    /** 宣言的形式のジョブ記述を読む。 */
    public static Result read(String text) {
        return new Reader(text.split("\n", -1)).read();
    }

    private static final class Reader {

        private final String[] lines;
        private final List<JobDiagnostic> diagnostics = new ArrayList<>();
        private final List<Step> steps = new ArrayList<>();
        private String jobName;

        /** 組み立て中のステップ。 */
        private String stepName;
        private String program;
        private String parm;
        private List<DdAssignment> dd = new ArrayList<>();
        private StepCondition condition;
        private int at;

        private Reader(String[] lines) {
            this.lines = lines;
        }

        private Result read() {
            while (at < lines.length) {
                String line = strip(lines[at]);
                at++;
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                readLine(line, at);
            }
            closeStep();
            if (jobName == null) {
                report(1, "the job description needs a JOB line");
            }
            return diagnostics.isEmpty()
                    ? new Result(new Job(jobName, steps), List.of())
                    : new Result(null, diagnostics);
        }

        private void readLine(String line, int number) {
            List<String> words = words(line);
            String keyword = words.get(0).toUpperCase(Locale.ROOT);
            switch (keyword) {
                case "JOB" -> readJob(words, number);
                case "STEP" -> readStep(words, number);
                case "WHEN" -> readWhen(words, number);
                case "DD" -> readDd(words, number);
                default -> report(number, "unknown keyword: " + words.get(0));
            }
        }

        private void readJob(List<String> words, int number) {
            if (jobName != null) {
                report(number, "a description holds one JOB");
                return;
            }
            if (words.size() != 2) {
                report(number, "JOB takes a name");
                return;
            }
            jobName = words.get(1).toUpperCase(Locale.ROOT);
        }

        private void readStep(List<String> words, int number) {
            closeStep();
            if (jobName == null) {
                report(number, "STEP comes after JOB");
                return;
            }
            if (words.size() < 3) {
                report(number, "STEP takes a name and PGM=");
                return;
            }
            stepName = words.get(1).toUpperCase(Locale.ROOT);
            for (String word : words.subList(2, words.size())) {
                if (word.toUpperCase(Locale.ROOT).startsWith("PGM=")) {
                    program = word.substring(4).toUpperCase(Locale.ROOT);
                } else if (word.toUpperCase(Locale.ROOT).startsWith("PARM=")) {
                    parm = word.substring(5);
                } else {
                    report(number, "STEP does not take: " + word);
                }
            }
            if (program == null) {
                report(number, "STEP needs PGM=");
            }
        }

        private void readWhen(List<String> words, int number) {
            if (stepName == null) {
                report(number, "WHEN comes inside a STEP");
                return;
            }
            if (condition != null) {
                report(number, "a step takes one WHEN");
                return;
            }
            condition = conditionOf(words.subList(1, words.size()), number);
        }

        /**
         * 条件を読む。
         *
         * <p>組み合わせは<b>1 段だけ</b>である。{@code AND} と {@code OR} を混ぜて書けない。
         * 混ぜたときの優先順位は書いた人の思い込みと食い違いやすく、誤りとしたほうがよい。
         */
        private StepCondition conditionOf(List<String> words, int number) {
            List<List<String>> parts = new ArrayList<>();
            List<String> current = new ArrayList<>();
            String joiner = null;
            for (String word : words) {
                String upper = word.toUpperCase(Locale.ROOT);
                if (upper.equals("AND") || upper.equals("OR")) {
                    if (joiner != null && !joiner.equals(upper)) {
                        report(number, "AND and OR cannot be mixed in one WHEN");
                        return null;
                    }
                    joiner = upper;
                    parts.add(current);
                    current = new ArrayList<>();
                    continue;
                }
                current.add(word);
            }
            parts.add(current);

            List<StepCondition> built = new ArrayList<>();
            for (List<String> part : parts) {
                StepCondition one = testOf(part, number);
                if (one == null) {
                    return null;
                }
                built.add(one);
            }
            if (built.size() == 1) {
                return built.get(0);
            }
            return "OR".equals(joiner)
                    ? new StepCondition.Any(built)
                    : new StepCondition.All(built);
        }

        /** 条件 1 個。 */
        private StepCondition testOf(List<String> words, int number) {
            if (words.isEmpty()) {
                report(number, "WHEN needs a condition");
                return null;
            }
            if (words.get(0).equalsIgnoreCase("NOT")) {
                StepCondition inner = testOf(words.subList(1, words.size()), number);
                return inner == null ? null : new StepCondition.Not(inner);
            }
            String first = words.get(0).toUpperCase(Locale.ROOT);
            if (first.equals("ABEND")) {
                return words.size() == 1 ? new StepCondition.OnlyIfAbend() : bad(words, number);
            }
            if (first.equals("EVEN")) {
                return words.size() == 1 ? new StepCondition.EvenIfAbend() : bad(words, number);
            }
            if (!first.equals("RC")) {
                report(number, "a condition starts with RC, ABEND, EVEN or NOT: " + words.get(0));
                return null;
            }
            // RC <ステップ> <関係> <値> か、ステップを書かない RC <関係> <値>
            int operand = words.size() == 4 ? 2 : 1;
            if (words.size() != 3 && words.size() != 4) {
                return bad(words, number);
            }
            String step = operand == 2 ? words.get(1).toUpperCase(Locale.ROOT) : null;
            StepCondition.Comparison comparison = comparisonOf(words.get(operand));
            if (comparison == null) {
                report(number, "unknown comparison: " + words.get(operand));
                return null;
            }
            try {
                return new StepCondition.ReturnCode(step, comparison,
                        Integer.parseInt(words.get(operand + 1)));
            } catch (NumberFormatException e) {
                report(number, "a return code must be an integer: " + words.get(operand + 1));
                return null;
            }
        }

        private StepCondition bad(List<String> words, int number) {
            report(number, "malformed condition: " + String.join(" ", words));
            return null;
        }

        private static StepCondition.Comparison comparisonOf(String text) {
            return switch (text.toUpperCase(Locale.ROOT)) {
                case "=", "==", "EQ" -> StepCondition.Comparison.EQ;
                case "<>", "!=", "NE" -> StepCondition.Comparison.NE;
                case "<", "LT" -> StepCondition.Comparison.LT;
                case "<=", "LE" -> StepCondition.Comparison.LE;
                case ">", "GT" -> StepCondition.Comparison.GT;
                case ">=", "GE" -> StepCondition.Comparison.GE;
                default -> null;
            };
        }

        private void readDd(List<String> words, int number) {
            if (stepName == null) {
                report(number, "DD comes inside a STEP");
                return;
            }
            if (words.size() != 3) {
                report(number, "DD takes a name and a target");
                return;
            }
            String name = words.get(1).toUpperCase(Locale.ROOT);
            String target = words.get(2);
            String upper = target.toUpperCase(Locale.ROOT);
            if (upper.startsWith("DSN=")) {
                dd.add(new DdAssignment(name,
                        new DdTarget.DataSet(java.nio.file.Path.of(target.substring(4)))));
                return;
            }
            switch (upper) {
                case "SYSOUT" -> dd.add(new DdAssignment(name, new DdTarget.Sysout()));
                case "DUMMY" -> dd.add(new DdAssignment(name, new DdTarget.Dummy()));
                case "DATA" -> dd.add(new DdAssignment(name, readInline(number)));
                default -> report(number, "unknown DD target: " + target);
            }
        }

        /** {@code DATA} から {@code END} までを、そのまま埋め込みのデータにする。 */
        private DdTarget readInline(int number) {
            StringBuilder sb = new StringBuilder();
            while (at < lines.length) {
                String line = strip(lines[at]);
                at++;
                if (line.equalsIgnoreCase("END")) {
                    return new DdTarget.Inline(
                            CodePageHolder.CODE_PAGE.encode(sb.toString()));
                }
                sb.append(line).append('\n');
            }
            report(number, "DATA is not closed by END");
            return new DdTarget.Dummy();
        }

        private void closeStep() {
            if (stepName == null) {
                return;
            }
            if (program != null) {
                steps.add(new Step(stepName, program, parm, dd,
                        condition == null ? new StepCondition.Always() : condition));
            }
            stepName = null;
            program = null;
            parm = null;
            condition = null;
            dd = new ArrayList<>();
        }

        private void report(int line, String message) {
            diagnostics.add(new JobDiagnostic(line, message));
        }

        private static String strip(String line) {
            return line.strip().replace("\r", "");
        }

        private static List<String> words(String line) {
            List<String> out = new ArrayList<>();
            for (String word : line.split("\\s+")) {
                if (!word.isEmpty()) {
                    out.add(word);
                }
            }
            return out;
        }
    }

    /** 埋め込みデータを符号化するコードページ。データセットと同じものである。 */
    private static final class CodePageHolder {
        private static final dev.cobolonjava.runtime.codepage.CodePage CODE_PAGE =
                dev.cobolonjava.runtime.codepage.CodePages.DEFAULT;

        private CodePageHolder() {
        }
    }

    /** 記述をバイト列から読む。ジョブの記述は UTF-8 のテキストである。 */
    public static Result read(byte[] bytes) {
        return read(new String(bytes, StandardCharsets.UTF_8));
    }
}
