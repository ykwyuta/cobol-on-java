package dev.cobolonjava.verify.execute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 動かした結果の数え上げ (要件 NFR-040、暫定判断 P-062)。
 *
 * <p>数えるのは 2 つある。
 *
 * <ul>
 *   <li><b>本数</b> — 全部の検査に通ったプログラムが何本か
 *   <li><b>検査の数</b> — プログラムの中の個々の検査が何件通ったか
 * </ul>
 *
 * <p>本数だけでは粗い。1 本の中に 100 件の検査があり、そのうち 1 件が落ちても
 * その本は落ちたことになる。逆に検査の数だけを見ると、<b>翻訳が通らなくて 1 件も
 * 流れていないプログラム</b>が数に出ない。両方を並べる。
 */
public record ExecutionReport(List<RunOutcome> outcomes) {

    public long passed() {
        return count(RunOutcome.Status.PASSED);
    }

    public long failed() {
        return count(RunOutcome.Status.FAILED);
    }

    /** 翻訳の診断を見るための検査。動かさないので合否には数えない。 */
    public long compileOnly() {
        return count(RunOutcome.Status.COMPILE_ONLY);
    }

    public long notCompiled() {
        return count(RunOutcome.Status.NOT_COMPILED);
    }

    public long crashed() {
        return count(RunOutcome.Status.CRASHED);
    }

    public long timedOut() {
        return count(RunOutcome.Status.TIMED_OUT);
    }

    private long count(RunOutcome.Status status) {
        return outcomes.stream().filter(o -> o.status() == status).count();
    }

    /**
     * 全部の検査に通った本数の割合 (百分率)。
     *
     * <p>動かさない検査 (翻訳の診断を見るもの) は<b>母数から外す</b>。
     * 動かしていないものを合否に数えると、数の意味が変わってしまう。
     */
    public double rate() {
        long counted = outcomes.size() - compileOnly();
        return counted == 0 ? 0 : 100.0 * passed() / counted;
    }

    /** 流れた検査の総数。 */
    public int executedChecks() {
        return outcomes.stream().mapToInt(RunOutcome::executed).sum();
    }

    /** 落ちた検査の総数。 */
    public int failedChecks() {
        return outcomes.stream().mapToInt(RunOutcome::failed).sum();
    }

    /** 流さなかった検査の総数。 */
    public int deletedChecks() {
        return outcomes.stream().mapToInt(RunOutcome::deleted).sum();
    }

    /** 人が見て判断する検査の総数。 */
    public int inspectedChecks() {
        return outcomes.stream().mapToInt(RunOutcome::inspected).sum();
    }

    /** 検査ごとの合格率 (百分率)。流れた検査が無ければ 0 である。 */
    public double checkRate() {
        int executed = executedChecks();
        int failed = failedChecks();
        return executed + failed == 0 ? 0 : 100.0 * executed / (executed + failed);
    }

    /** 区分だけを選んだ数え上げ。 */
    public ExecutionReport only(List<String> groups) {
        return new ExecutionReport(outcomes.stream()
                .filter(o -> groups.contains(o.group()))
                .toList());
    }

    /** 区分ごとの数え上げ。並びは区分の名前の順である。 */
    public Map<String, ExecutionReport> byGroup() {
        Map<String, List<RunOutcome>> grouped = new TreeMap<>();
        for (RunOutcome outcome : outcomes) {
            grouped.computeIfAbsent(outcome.group(), k -> new ArrayList<>()).add(outcome);
        }
        Map<String, ExecutionReport> out = new LinkedHashMap<>();
        grouped.forEach((group, list) -> out.put(group, new ExecutionReport(List.copyOf(list))));
        return out;
    }

    /**
     * 落ちた検査を多く抱えているプログラムを、多い順に。
     *
     * <p>これが<b>次にどこを直すか</b>の一覧である。1 件しか落ちていないプログラムより、
     * 50 件落ちているプログラムを先に見るほうがよい。
     */
    public List<RunOutcome> worst(int limit) {
        return outcomes.stream()
                .filter(o -> o.failed() > 0)
                .sorted((a, b) -> Integer.compare(b.failed(), a.failed()))
                .limit(limit)
                .toList();
    }

    /**
     * 落ちた検査を<b>機能ごとに</b>数え、多い順に。
     *
     * <p>これが<b>次に何を直すか</b>の一覧である。本数で数えると「1 本が全滅」までしか
     * 分からない。機能で数えると、どの言語機能が壊れているかが出る。
     */
    public List<Map.Entry<String, Long>> failedFeatures(int limit) {
        Map<String, Long> counted = new LinkedHashMap<>();
        for (RunOutcome outcome : outcomes) {
            for (TestReport.Failure failure : outcome.failures()) {
                counted.merge(failure.feature(), 1L, Long::sum);
            }
        }
        return counted.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .toList();
    }

    /** 動かなかったものを、結末ごとに。 */
    public List<RunOutcome> notRun() {
        return outcomes.stream()
                .filter(o -> o.status() == RunOutcome.Status.CRASHED
                        || o.status() == RunOutcome.Status.TIMED_OUT)
                .toList();
    }

    /** 人が読む形。 */
    public String text(String title) {
        StringBuilder out = new StringBuilder();
        out.append(title).append('\n');
        out.append("=".repeat(title.length())).append("\n\n");
        out.append(String.format(
                "合計 %d 本 (うち動かさない診断の検査 %d 本):"
                        + " 全部通った %d / 落ちた %d / 翻訳できない %d"
                        + " / 壊れた %d / 返らない %d  (合格率 %.1f%%)%n",
                outcomes.size(), compileOnly(), passed(), failed(), notCompiled(),
                crashed(), timedOut(), rate()));
        out.append(String.format(
                "検査は %d 件流れて %d 件落ちた (合格率 %.1f%%)。"
                        + "流さなかったもの %d 件、人が見るもの %d 件%n",
                executedChecks(), failedChecks(), checkRate(),
                deletedChecks(), inspectedChecks()));
        out.append('\n');
        out.append(String.format("%-8s %6s %6s %6s %6s %6s %6s %6s  %s%n",
                "区分", "本数", "診断", "通", "落", "未翻訳", "壊", "検査落", "合格率"));
        byGroup().forEach((group, report) -> out.append(String.format(
                "%-8s %6d %6d %6d %6d %6d %6d %6d  %5.1f%%%n",
                group, report.outcomes().size(), report.compileOnly(), report.passed(),
                report.failed(), report.notCompiled(), report.crashed() + report.timedOut(),
                report.failedChecks(), report.rate())));
        List<RunOutcome> worst = worst(20);
        if (!worst.isEmpty()) {
            out.append("\n落ちた検査の多い順\n");
            for (RunOutcome outcome : worst) {
                out.append(String.format("%6d / %-4d  %s%n",
                        outcome.failed(), outcome.executed() + outcome.failed(), outcome.name()));
            }
        }
        List<Map.Entry<String, Long>> features = failedFeatures(25);
        if (!features.isEmpty()) {
            out.append("\n落ちた検査を機能ごとに数えたもの (多い順)\n");
            for (Map.Entry<String, Long> feature : features) {
                out.append(String.format("%6d  %s%n", feature.getValue(), feature.getKey()));
            }
        }
        List<RunOutcome> broken = notRun();
        if (!broken.isEmpty()) {
            out.append("\n動かなかったもの (処理系の欠陥)\n");
            for (RunOutcome outcome : broken) {
                out.append(String.format("  %-28s %s%n", outcome.name(), outcome.failure()));
            }
        }
        return out.toString();
    }
}
