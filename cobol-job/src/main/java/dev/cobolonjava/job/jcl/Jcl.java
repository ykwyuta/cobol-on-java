package dev.cobolonjava.job.jcl;

import dev.cobolonjava.job.DdAssignment;
import dev.cobolonjava.job.DdTarget;
import dev.cobolonjava.job.Disposition;
import dev.cobolonjava.job.Job;
import dev.cobolonjava.job.JobDiagnostic;
import dev.cobolonjava.job.Step;
import dev.cobolonjava.job.GenerationDataGroup;
import dev.cobolonjava.job.StepCondition;
import dev.cobolonjava.runtime.file.PartitionedDataSet;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /**
     * JCL を読む。
     *
     * <p>置き場を知らない。{@code DSN=} は<b>名前</b>であって場所ではなく、どこにあるかを
     * 引くのは目録の仕事である (要件 FR-131、暫定判断 P-045 の解消)。ここで場所へ直せば、
     * 目録に載せる・外すという操作が表せなくなる。
     */
    public static Result read(String text) {
        return read(text, JclLibrary.empty());
    }

    /**
     * 目録手続きを引ける構成で JCL を読む。
     *
     * @param library {@code EXEC 手続き名} と {@code INCLUDE} の取り出し先
     */
    public static Result read(String text, JclLibrary library) {
        return new Builder(library).build(text);
    }

    /** JCL をバイト列から読む。JCL の本文は UTF-8 のテキストである。 */
    public static Result read(byte[] bytes) {
        return read(new String(bytes, StandardCharsets.UTF_8));
    }

    private static final class Builder {

        private final JclLibrary library;
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
        /**
         * 開いている {@code IF} の条件。内側から外側の順ではなく、外側から並ぶ。
         *
         * <p>ステップの条件は<b>囲んでいる条件をすべて満たしたうえで</b>自分の
         * {@code COND} も満たすときに成り立つ。
         */
        private final java.util.Deque<StepCondition> open = new java.util.ArrayDeque<>();
        /** そのステップを囲んでいた条件。{@code EXEC} を読んだ時点のものである。 */
        private List<StepCondition> enclosing = List.of();

        private Builder(JclLibrary library) {
            this.library = library;
        }

        private Result build(String text) {
            // 手続きとシンボリックパラメタは、モデルを組む前に展開しておく
            List<JclCard> cards = JclExpander.expand(
                    JclReader.read(text, diagnostics), library, diagnostics);
            for (JclCard card : cards) {
                readCard(card);
            }
            closeStep();
            if (!open.isEmpty()) {
                diagnostics.add(new JobDiagnostic(1, "an IF is not closed by ENDIF"));
            }
            if (jobName == null) {
                diagnostics.add(new JobDiagnostic(1, "the JCL needs a JOB statement"));
            }
            return diagnostics.isEmpty()
                    ? new Result(new Job(jobName, steps), List.of())
                    : new Result(null, diagnostics);
        }

        private void readCard(JclCard card) {
            switch (card.operation()) {
                case "JOB" -> readJob(card);
                case "EXEC" -> readExec(card);
                case "DD" -> readDd(card);
                case "IF" -> readIf(card);
                case "ELSE" -> readElse(card);
                case "ENDIF" -> readEndIf(card);
                case "OUTPUT", "JCLLIB" ->
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
            // 囲んでいる条件は EXEC を読んだ時点のものである。ENDIF はあとから来る
            enclosing = List.copyOf(open);
            for (String operand : JclOperands.split(card.operands())) {
                readExecOperand(card, operand);
            }
            if (program == null) {
                report(card, "EXEC needs PGM= or a procedure name");
            }
        }

        private void readExecOperand(JclCard card, String operand) {
            String key = JclOperands.key(operand).toUpperCase(Locale.ROOT);
            if (operand.trim().equals(JclOperands.key(operand))) {
                // 鍵だけのオペランドは手続きの名前である。展開の段で消えているはずである
                report(card, "no such procedure: " + key);
                return;
            }
            String value = JclOperands.value(operand);
            switch (key) {
                case "PGM" -> program = value.toUpperCase(Locale.ROOT);
                case "PARM" -> parm = JclOperands.unquote(value);
                case "COND" -> condition = conditionOf(card, value);
                // 手続きへ渡すシンボリックパラメタは展開の段で使い切っている
                case "PROC", "REGION", "TIME" -> {
                    // 資源の指定は、この実装では効かない
                }
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

        private void readDd(JclCard card) {
            if (stepName == null) {
                report(card, "DD comes inside a step");
                return;
            }
            Allocation allocation = targetOf(card);
            if (allocation == null) {
                return;
            }
            if (card.name() == null) {
                // 名前欄の空いた DD は、直前の DD への連結である
                concatenate(card, allocation.target());
                return;
            }
            lastDd = card.name().toUpperCase(Locale.ROOT);
            dd.add(new DdAssignment(lastDd, allocation.target(), allocation.space(),
                    allocation.directory()));
        }

        /**
         * {@code DD} 文 1 枚が言っていること。行き先と、割り当てる大きさである。
         *
         * @param directory ディレクトリブロックの数。書かなければ {@code 0}
         */
        private record Allocation(DdTarget target, long space, int directory) {

            Allocation(DdTarget target, long space) {
                this(target, space, 0);
            }
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

        private Allocation targetOf(JclCard card) {
            String operands = card.operands().trim();
            if (operands.equals("*") || operands.startsWith("*,")
                    || operands.equals("DATA") || operands.startsWith("DATA,")) {
                return new Allocation(new DdTarget.Inline(
                        card.inline() == null ? new byte[0] : card.inline()),
                        DdAssignment.UNLIMITED);
            }
            String name = null;
            // DISP を書かなければ「新しく作る」である。ホストの既定はこちらであり、
            // 読むつもりの DD には DISP=SHR を書かねばならない
            Disposition disposition = Disposition.of(Disposition.Status.NEW);
            DdTarget special = null;
            long space = DdAssignment.UNLIMITED;
            int directory = 0;
            String serial = null;
            for (String operand : JclOperands.split(operands)) {
                String key = JclOperands.key(operand).toUpperCase(Locale.ROOT);
                String value = JclOperands.value(operand);
                switch (key) {
                    case "DSN", "DSNAME" -> name = JclOperands.unquote(value);
                    case "DISP" -> disposition = dispositionOf(card, value, disposition);
                    case "SYSOUT" -> special = new DdTarget.Sysout();
                    case "DUMMY" -> special = new DdTarget.Dummy();
                    case "SPACE" -> {
                        Space allocated = spaceOf(card, value);
                        space = allocated.bytes();
                        directory = allocated.directory();
                    }
                    case "VOL", "VOLUME" -> serial = serialOf(card, value);
                    // 装置と記述の指定は、ファイルとして持つこの実装では効かない
                    case "UNIT", "LRECL", "RECFM", "BLKSIZE", "DCB" ->
                            report(card, key + " is not supported yet");
                    default -> report(card, "DD does not support: " + key);
                }
            }
            if (special != null) {
                return new Allocation(special, DdAssignment.UNLIMITED);
            }
            if (name == null) {
                report(card, "DD needs DSN=, SYSOUT=, DUMMY or *");
                return null;
            }
            // 先頭が & のものは一時データセットである。シンボリックの展開を抜けた
            // あとなので、ここまで残っている & は名前の一部である
            if (name.startsWith("&")) {
                return new Allocation(new DdTarget.Temporary(name.substring(1), disposition),
                        space, directory);
            }
            // 括弧の中が相対世代なら、これはメンバではなく世代データグループである
            String qualifier = qualifierOf(name);
            if (qualifier != null && GenerationDataGroup.relative(qualifier)) {
                return new Allocation(new DdTarget.DataSet(libraryOf(name), null, serial,
                        disposition, Integer.valueOf(Integer.parseInt(qualifier))),
                        space, directory);
            }
            String member = memberOf(card, name);
            if (member != null && member.isEmpty()) {
                return null;
            }
            return new Allocation(new DdTarget.DataSet(libraryOf(name), member, serial,
                    disposition), space, directory);
        }

        /**
         * {@code VOL=SER=通し番号} または {@code VOL=(...,SER=通し番号)} (暫定判断 P-045 の解消)。
         *
         * <p>これを書いたジョブは<b>目録を通さずに置き場を直に見る</b>。{@code KEEP} で
         * 残したデータセットは目録に載っていないので、名前だけでは届かない。ホストで
         * {@code VOL=SER} を書かねばならないのと同じ形である。
         */
        private String serialOf(JclCard card, String value) {
            for (String part : JclOperands.split(JclOperands.unwrap(value))) {
                String key = JclOperands.key(part).toUpperCase(Locale.ROOT);
                if (key.equals("REF")) {
                    report(card, "VOL=REF is not supported yet");
                    return null;
                }
                if (!key.equals("SER")) {
                    continue;
                }
                List<String> serials =
                        JclOperands.split(JclOperands.unwrap(JclOperands.value(part)));
                // 複数ボリュームにまたがるデータセットは持たない。先頭を採る
                return serials.isEmpty() ? null : JclOperands.unquote(serials.get(0));
            }
            report(card, "VOL needs SER=");
            return null;
        }

        /** {@code DSN=名前(なにか)} の括弧の中。括弧が無ければ {@code null}。 */
        private static String qualifierOf(String name) {
            int open = name.indexOf('(');
            if (open < 0 || !name.endsWith(")")) {
                return null;
            }
            return name.substring(open + 1, name.length() - 1).trim();
        }

        /**
         * {@code DSN=ライブラリ(メンバ)} のメンバ名 (要件 FR-113)。
         *
         * <p>括弧の中が相対世代のときは<b>世代データグループ</b>であり、メンバではない
         * (要件 FR-114)。そちらは呼ぶ前に分けてある。
         *
         * <p>名前そのものも検める (暫定判断 P-056 の解消)。ホストのメンバ名は 8 文字までで
         * あり、超えていればジョブを<b>読む段で</b>弾かれる。ここで通せば、実機なら JCL
         * 誤りで動かないジョブがここでは動いてしまう。
         *
         * @return 書かれていなければ {@code null}。誤りなら空文字列
         */
        private String memberOf(JclCard card, String name) {
            String member = qualifierOf(name);
            if (member == null) {
                return null;
            }
            if (member.isEmpty()) {
                report(card, "DSN needs a member name inside the parentheses: " + name);
                return "";
            }
            String upper = member.toUpperCase(Locale.ROOT);
            if (!PartitionedDataSet.validName(upper)) {
                // ホストはジョブを読む段で弾く。通せば実機で動かないジョブがここでは動く
                report(card, "invalid member name: " + member);
                return "";
            }
            return upper;
        }

        /** {@code DSN=ライブラリ(メンバ)} のライブラリ名。メンバを書かなければ名前そのもの。 */
        private static String libraryOf(String name) {
            int open = name.indexOf('(');
            return open < 0 || !name.endsWith(")") ? name : name.substring(0, open).trim();
        }

        /**
         * {@code SPACE=(単位,(一次,二次))} (要件 FR-141)。
         *
         * <p>読むのは<b>一次割当と二次割当があるかどうか</b>だけである。二次割当があれば
         * 使い切っても伸ばせるので、限りなしとして扱う。無ければ一次割当がそのまま限りに
         * なる。ホストで {@code SPACE} を書き忘れたジョブが途中で止まるのは、この形である。
         *
         * <p>単位は {@code TRK} / {@code CYL} / ブロック長である。トラックとシリンダの
         * 大きさは 3390 のものを使う。実際の装置を持たない以上どこかで決めるほかなく、
         * いちばん広く使われている値を採る。
         */
        private Space spaceOf(JclCard card, String value) {
            List<String> parts = JclOperands.split(JclOperands.unwrap(value));
            if (parts.isEmpty()) {
                report(card, "SPACE needs a unit");
                return Space.NONE;
            }
            long unit = unitOf(parts.get(0));
            if (unit <= 0) {
                report(card, "unknown SPACE unit: " + parts.get(0));
                return Space.NONE;
            }
            if (parts.size() < 2) {
                return Space.NONE;
            }
            List<String> amounts = JclOperands.split(JclOperands.unwrap(parts.get(1)));
            // 3 つ目はディレクトリブロックの数である。書いてあれば区分データセットになる
            int directory = amounts.size() >= 3 ? (int) number(amounts.get(2)) : 0;
            if (amounts.size() >= 2 && number(amounts.get(1)) > 0) {
                // 二次割当があれば伸ばせる。使い切って止まることはない
                return new Space(DdAssignment.UNLIMITED, directory);
            }
            long primary = number(amounts.isEmpty() ? "" : amounts.get(0));
            return new Space(primary > 0 ? primary * unit : DdAssignment.UNLIMITED, directory);
        }

        /**
         * {@code SPACE=} が言っていること。
         *
         * @param bytes     書ける大きさ。{@code 0} なら限りなし
         * @param directory ディレクトリブロックの数。{@code 0} なら区分データセットではない
         */
        private record Space(long bytes, int directory) {

            static final Space NONE = new Space(DdAssignment.UNLIMITED, 0);
        }

        /** 3390 のトラックは 56664 バイト、シリンダは 15 トラックである。 */
        private static long unitOf(String text) {
            return switch (text.trim().toUpperCase(Locale.ROOT)) {
                case "TRK" -> 56664L;
                case "CYL" -> 56664L * 15;
                default -> number(text);
            };
        }

        private static long number(String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        /** {@code DISP=(状態, 正常終了時, 異常終了時)}。書かれていないところは既定で埋める。 */
        private Disposition dispositionOf(JclCard card, String value, Disposition fallback) {
            List<String> parts = JclOperands.split(JclOperands.unwrap(value));
            Disposition.Status status = Disposition.Status.of(parts.get(0));
            if (status == null) {
                report(card, "unknown DISP: " + parts.get(0));
                return fallback;
            }
            Disposition.Action normal = actionOf(card, parts, 1);
            Disposition.Action abnormal = actionOf(card, parts, 2);
            return Disposition.of(status, normal, abnormal);
        }

        /** {@code DISP} の 2 つ目と 3 つ目。書かれていなければ {@code null} である。 */
        private Disposition.Action actionOf(JclCard card, List<String> parts, int at) {
            if (at >= parts.size() || parts.get(at).isBlank()) {
                return null;
            }
            Disposition.Action action = Disposition.Action.of(parts.get(at));
            if (action == null) {
                report(card, "unknown DISP: " + parts.get(at));
            }
            return action;
        }

        private void closeStep() {
            if (stepName == null) {
                return;
            }
            if (program != null) {
                steps.add(new Step(stepName, program, parm, dd, combined()));
            }
            stepName = null;
            program = null;
            parm = null;
            condition = null;
            dd = new ArrayList<>();
            lastDd = null;
            enclosing = List.of();
        }

        /** 囲んでいる条件と、そのステップ自身の {@code COND} を重ねる。 */
        private StepCondition combined() {
            List<StepCondition> parts = new ArrayList<>(enclosing);
            if (condition != null && !(condition instanceof StepCondition.Always)) {
                parts.add(condition);
            }
            if (parts.isEmpty()) {
                return new StepCondition.Always();
            }
            return parts.size() == 1 ? parts.get(0) : new StepCondition.All(parts);
        }

        /**
         * {@code IF (関係式) THEN}。
         *
         * <p>{@code COND} と違って<b>「真なら動かす」</b>の向きで書く。内部モデルの向きと
         * 同じなので裏返さない。
         */
        private void readIf(JclCard card) {
            String text = card.operands();
            String upper = text.toUpperCase(Locale.ROOT).trim();
            if (!upper.endsWith("THEN")) {
                report(card, "IF needs THEN");
                return;
            }
            text = text.trim().substring(0, text.trim().length() - "THEN".length());
            StepCondition condition = JclCondition.parse(text, card, diagnostics);
            // 読めなかった条件も積む。ENDIF との対応を崩さないためである
            open.push(condition == null ? new StepCondition.Always() : condition);
        }

        /** {@code ELSE}。開いている条件を裏返す。 */
        private void readElse(JclCard card) {
            if (open.isEmpty()) {
                report(card, "ELSE without a matching IF");
                return;
            }
            // ステップはここで閉じなくてよい。囲んでいる条件は EXEC を読んだ時点で写してある
            open.push(new StepCondition.Not(open.pop()));
        }

        /** {@code ENDIF}。開いている条件を閉じる。 */
        private void readEndIf(JclCard card) {
            if (open.isEmpty()) {
                report(card, "ENDIF without a matching IF");
                return;
            }
            open.pop();
        }

        private void report(JclCard card, String message) {
            diagnostics.add(new JobDiagnostic(card.line(), message));
        }
    }
}
