package dev.cobolonjava.job.jcl;

import dev.cobolonjava.job.DdAssignment;
import dev.cobolonjava.job.DdTarget;
import dev.cobolonjava.job.Disposition;
import dev.cobolonjava.job.Job;
import dev.cobolonjava.job.JobDiagnostic;
import dev.cobolonjava.job.Step;
import dev.cobolonjava.job.StepCondition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JCL フロントエンド (要件 FR-131)。
 *
 * <p>既存の JCL 資産を<b>書き換えずに実行する</b>ためのものである。読み取った結果は
 * 内部ジョブモデルであり、宣言的形式で書いたものと区別がない。したがって実行結果も同じになる。
 *
 * <h2>知らない書き方は誤りにする</h2>
 * <p>JCL の全機能を再現するのは目標ではない。しかし<b>読み飛ばしてはならない</b>。
 * 書いたつもりの指定が効いていないまま動くと、出力が違うことに気付くのが遅れる。
 *
 * <h2>COND の向きを裏返す</h2>
 * <p>JCL の {@code COND} は<b>「真なら飛ばす」</b>という向きで書く。
 * {@code COND=(4,LT,STEP1)} は「STEP1 の RC が 4 より大きければ飛ばす」である。
 * 内部モデルは「真なら動かす」の向きなので、ここで裏返す。
 *
 * <p>試験が複数あれば<b>どれか 1 つでも成り立てば飛ばす</b>。裏返すと「すべて成り立たなければ
 * 動かす」になる。
 */
public final class Jcl {

    private Jcl() {
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

    /** JCL を読む。データセット名は基点のディレクトリの下に置かれているものとする。 */
    public static Result read(String text, Path base) {
        return new Builder(base).build(text);
    }

    /** JCL をバイト列から読む。JCL の本文は UTF-8 のテキストである。 */
    public static Result read(byte[] bytes, Path base) {
        return read(new String(bytes, StandardCharsets.UTF_8), base);
    }

    private static final class Builder {

        private final Path base;
        private final List<JobDiagnostic> diagnostics = new ArrayList<>();
        private final List<Step> steps = new ArrayList<>();
        private String jobName;

        /** 組み立て中のステップ。 */
        private String stepName;
        private String program;
        private String parm;
        private StepCondition condition;
        private List<DdAssignment> dd = new ArrayList<>();
        /** 直前の DD 名。名前欄が空の DD カードは、これに連結される。 */
        private String lastDd;

        private Builder(Path base) {
            this.base = base;
        }

        private Result build(String text) {
            JclReader.Result read = JclReader.read(text, diagnostics);
            for (JclCard card : read.cards()) {
                readCard(card, read.data());
            }
            closeStep();
            if (jobName == null) {
                diagnostics.add(new JobDiagnostic(1, "the JCL needs a JOB statement"));
            }
            return diagnostics.isEmpty()
                    ? new Result(new Job(jobName, steps), List.of())
                    : new Result(null, diagnostics);
        }

        private void readCard(JclCard card, Map<Integer, byte[]> data) {
            switch (card.operation()) {
                case "JOB" -> readJob(card);
                case "EXEC" -> readExec(card);
                case "DD" -> readDd(card, data);
                case "PROC", "PEND", "INCLUDE", "SET", "IF", "ELSE", "ENDIF", "OUTPUT", "JCLLIB" ->
                        report(card, card.operation() + " is not supported yet");
                default -> report(card, "unknown JCL operation: " + card.operation());
            }
        }

        private void readJob(JclCard card) {
            if (jobName != null) {
                report(card, "a JCL holds one JOB statement");
                return;
            }
            if (card.name() == null) {
                report(card, "a JOB statement needs a name");
                return;
            }
            jobName = card.name().toUpperCase(Locale.ROOT);
        }

        private void readExec(JclCard card) {
            closeStep();
            if (jobName == null) {
                report(card, "EXEC comes after JOB");
                return;
            }
            stepName = card.name() == null
                    ? "STEP" + (steps.size() + 1)
                    : card.name().toUpperCase(Locale.ROOT);
            lastDd = null;
            for (String operand : JclOperands.split(card.operands())) {
                readExecOperand(card, operand);
            }
            if (program == null) {
                report(card, "EXEC needs PGM=; cataloged procedures are not supported yet");
            }
        }

        private void readExecOperand(JclCard card, String operand) {
            String key = JclOperands.key(operand).toUpperCase(Locale.ROOT);
            if (operand.trim().equals(JclOperands.key(operand))) {
                // 鍵だけのオペランドは目録手続きの名前である
                report(card, "EXEC needs PGM=; a procedure name is not supported yet");
                return;
            }
            String value = JclOperands.value(operand);
            switch (key) {
                case "PGM" -> program = value.toUpperCase(Locale.ROOT);
                case "PARM" -> parm = JclOperands.unquote(value);
                case "COND" -> condition = conditionOf(card, value);
                default -> report(card, "EXEC does not support: " + key);
            }
        }

        /**
         * {@code COND=} を条件へ変える。
         *
         * <p>向きを裏返す。JCL は「真なら飛ばす」、内部モデルは「真なら動かす」である。
         */
        private StepCondition conditionOf(JclCard card, String value) {
            String text = value.trim();
            if (text.equalsIgnoreCase("EVEN")) {
                return new StepCondition.EvenIfAbend();
            }
            if (text.equalsIgnoreCase("ONLY")) {
                return new StepCondition.OnlyIfAbend();
            }
            // 括弧の中が括弧で始まっていれば試験の並び、そうでなければ試験 1 個である
            String inner = JclOperands.unwrap(text);
            List<String> items = inner.startsWith("(")
                    ? JclOperands.split(inner)
                    : List.of(inner);
            List<StepCondition> tests = new ArrayList<>();
            boolean even = false;
            boolean only = false;
            for (String part : items) {
                String one = part.trim();
                if (one.equalsIgnoreCase("EVEN")) {
                    even = true;
                    continue;
                }
                if (one.equalsIgnoreCase("ONLY")) {
                    only = true;
                    continue;
                }
                StepCondition test = testOf(card, one);
                if (test == null) {
                    return null;
                }
                tests.add(test);
            }
            if (even && only) {
                report(card, "COND takes EVEN or ONLY, not both");
                return null;
            }
            // どれか 1 つでも成り立てば飛ばす。裏返せば「すべて成り立たなければ動かす」である
            StepCondition bypass = tests.size() == 1 ? tests.get(0) : new StepCondition.Any(tests);
            StepCondition run = tests.isEmpty() ? null : new StepCondition.Not(bypass);
            if (only) {
                return combine(run, new StepCondition.OnlyIfAbend());
            }
            if (even) {
                return combine(run, new StepCondition.EvenIfAbend());
            }
            return run == null ? new StepCondition.Always() : run;
        }

        private static StepCondition combine(StepCondition run, StepCondition abend) {
            return run == null ? abend : new StepCondition.All(List.of(abend, run));
        }

        /** {@code (コード,関係)} か {@code (コード,関係,ステップ)}。真なら飛ばす向きである。 */
        private StepCondition testOf(JclCard card, String text) {
            List<String> parts = JclOperands.split(JclOperands.unwrap(text));
            if (parts.size() != 2 && parts.size() != 3) {
                report(card, "malformed COND test: " + text);
                return null;
            }
            int value;
            try {
                value = Integer.parseInt(parts.get(0).trim());
            } catch (NumberFormatException e) {
                report(card, "a COND return code must be an integer: " + parts.get(0));
                return null;
            }
            StepCondition.Comparison comparison = comparisonOf(parts.get(1));
            if (comparison == null) {
                report(card, "unknown COND comparison: " + parts.get(1));
                return null;
            }
            String step = parts.size() == 3 ? parts.get(2).trim().toUpperCase(Locale.ROOT) : null;
            // JCL は「コード 関係 RC」の順で書く。内部モデルは「RC 関係 値」なので向きを入れ替える
            return new StepCondition.ReturnCode(step, flip(comparison), value);
        }

        /** {@code 4,LT,STEP1} は「4 < STEP1 の RC」である。左右を入れ替えて読む。 */
        private static StepCondition.Comparison flip(StepCondition.Comparison comparison) {
            return switch (comparison) {
                case EQ -> StepCondition.Comparison.EQ;
                case NE -> StepCondition.Comparison.NE;
                case LT -> StepCondition.Comparison.GT;
                case LE -> StepCondition.Comparison.GE;
                case GT -> StepCondition.Comparison.LT;
                case GE -> StepCondition.Comparison.LE;
            };
        }

        private static StepCondition.Comparison comparisonOf(String text) {
            return switch (text.trim().toUpperCase(Locale.ROOT)) {
                case "EQ" -> StepCondition.Comparison.EQ;
                case "NE" -> StepCondition.Comparison.NE;
                case "LT" -> StepCondition.Comparison.LT;
                case "LE" -> StepCondition.Comparison.LE;
                case "GT" -> StepCondition.Comparison.GT;
                case "GE" -> StepCondition.Comparison.GE;
                default -> null;
            };
        }

        private void readDd(JclCard card, Map<Integer, byte[]> data) {
            if (stepName == null) {
                report(card, "DD comes inside a step");
                return;
            }
            DdTarget target = targetOf(card, data);
            if (target == null) {
                return;
            }
            if (card.name() == null) {
                // 名前欄の空いた DD は、直前の DD への連結である
                concatenate(card, target);
                return;
            }
            lastDd = card.name().toUpperCase(Locale.ROOT);
            dd.add(new DdAssignment(lastDd, target));
        }

        /** 連結。読むときは並べた順に 1 つのファイルに見える。 */
        private void concatenate(JclCard card, DdTarget target) {
            if (lastDd == null) {
                report(card, "a DD without a name continues the one before it");
                return;
            }
            int last = dd.size() - 1;
            DdTarget previous = dd.get(last).target();
            List<DdTarget> parts = new ArrayList<>();
            if (previous instanceof DdTarget.Concatenation concatenation) {
                parts.addAll(concatenation.parts());
            } else {
                parts.add(previous);
            }
            parts.add(target);
            dd.set(last, new DdAssignment(lastDd, new DdTarget.Concatenation(parts)));
        }

        private DdTarget targetOf(JclCard card, Map<Integer, byte[]> data) {
            String operands = card.operands().trim();
            if (operands.equals("*") || operands.startsWith("*,")
                    || operands.equals("DATA") || operands.startsWith("DATA,")) {
                return new DdTarget.Inline(data.getOrDefault(card.line(), new byte[0]));
            }
            String name = null;
            Disposition disposition = Disposition.SHR;
            DdTarget special = null;
            for (String operand : JclOperands.split(operands)) {
                String key = JclOperands.key(operand).toUpperCase(Locale.ROOT);
                String value = JclOperands.value(operand);
                switch (key) {
                    case "DSN", "DSNAME" -> name = JclOperands.unquote(value);
                    case "DISP" -> disposition = dispositionOf(card, value, disposition);
                    case "SYSOUT" -> special = new DdTarget.Sysout();
                    case "DUMMY" -> special = new DdTarget.Dummy();
                    // 装置と大きさの指定は、ファイルとして持つこの実装では効かない
                    case "UNIT", "SPACE", "VOL", "VOLUME", "LRECL", "RECFM", "BLKSIZE", "DCB" ->
                            report(card, key + " is not supported yet");
                    default -> report(card, "DD does not support: " + key);
                }
            }
            if (special != null) {
                return special;
            }
            if (name == null) {
                report(card, "DD needs DSN=, SYSOUT=, DUMMY or *");
                return null;
            }
            return new DdTarget.DataSet(base.resolve(name), disposition);
        }

        private Disposition dispositionOf(JclCard card, String value, Disposition fallback) {
            List<String> parts = JclOperands.split(JclOperands.unwrap(value));
            Disposition disposition = Disposition.of(parts.get(0));
            if (disposition == null) {
                report(card, "unknown DISP: " + parts.get(0));
                return fallback;
            }
            return disposition;
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
            lastDd = null;
        }

        private void report(JclCard card, String message) {
            diagnostics.add(new JobDiagnostic(card.line(), message));
        }
    }
}
