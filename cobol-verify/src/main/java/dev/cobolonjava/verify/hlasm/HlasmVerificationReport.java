package dev.cobolonjava.verify.hlasm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 組み立ての一致率と実行の一致率を<b>別々に</b>数える。
 *
 * <p>混ぜてはならない理由は設計 27 §3 にある。誤った機械語を Hercules も自分のインタプリタも
 * 同じように実行すると結果は一致し、混ぜた数は緑になる。それでは原文の意味を再現できたことに
 * ならない。組み立てが合っていることを先に確かめ、そのうえで実行を数える。
 */
public record HlasmVerificationReport(List<HlasmCaseOutcome> outcomes) {

    public HlasmVerificationReport {
        outcomes = List.copyOf(outcomes);
    }

    public long count(HlasmCaseOutcome.Status status) {
        return outcomes.stream().filter(value -> value.status() == status).count();
    }

    /** 組み立てまで届いた本数。断ったものと壊れたものを除く。 */
    public long assembled() {
        return outcomes.size() - count(HlasmCaseOutcome.Status.REJECTED)
                - count(HlasmCaseOutcome.Status.CRASHED);
    }

    /**
     * 機械語の期待値があって実際に比べた本数。
     *
     * <p>期待値の無い本を入れてはならない。入れると、比べていないものまで
     * 「一致した」に数えられ、組み立ての一致率が実際より高く出る。
     */
    public long objectCases() {
        return outcomes.stream().filter(HlasmCaseOutcome::objectChecked).count();
    }

    /** 機械語が期待値と一致した本数。 */
    public long objectMatched() {
        return outcomes.stream().filter(HlasmCaseOutcome::objectChecked)
                .filter(value -> value.status() != HlasmCaseOutcome.Status.WRONG_OBJECT).count();
    }

    /** 実行まで届いた本数。機械語が違ったものは実行していないので入らない。 */
    public long executionCases() {
        return count(HlasmCaseOutcome.Status.PASSED) + count(HlasmCaseOutcome.Status.WRONG_OUTPUT);
    }

    public double assemblyRate() {
        return outcomes.isEmpty() ? 0 : 100.0 * assembled() / outcomes.size();
    }

    /** 機械語が期待値と一致した割合。比べた本が 1 つも無ければ意味を持たない。 */
    public double objectRate() {
        return objectCases() == 0 ? 0 : 100.0 * objectMatched() / objectCases();
    }

    public double executionRate() {
        return executionCases() == 0 ? 0
                : 100.0 * count(HlasmCaseOutcome.Status.PASSED) / executionCases();
    }

    public Map<String, HlasmVerificationReport> byGroup() {
        Map<String, List<HlasmCaseOutcome>> grouped = new TreeMap<>();
        for (HlasmCaseOutcome outcome : outcomes) {
            grouped.computeIfAbsent(outcome.group(), unused -> new ArrayList<>()).add(outcome);
        }
        Map<String, HlasmVerificationReport> result = new LinkedHashMap<>();
        grouped.forEach((group, values) ->
                result.put(group, new HlasmVerificationReport(values)));
        return result;
    }

    /** 次に実装する機能を選ぶため、通らなかった理由を多い順に返す。 */
    public List<Map.Entry<String, Long>> reasons(int limit) {
        Map<String, Long> counted = new LinkedHashMap<>();
        for (HlasmCaseOutcome outcome : outcomes) {
            if (!outcome.reason().isEmpty()) {
                counted.merge(outcome.reason(), 1L, Long::sum);
            }
        }
        return counted.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit).toList();
    }

    public String text(String title) {
        StringBuilder out = new StringBuilder(title).append('\n')
                .append("=".repeat(title.length())).append("\n\n")
                .append(String.format(
                        "合計 %d 本: 組み立て %d (%.1f%%)、機械語一致 %s、実行一致 %s%n%n",
                        outcomes.size(), assembled(), assemblyRate(),
                        ratio(objectMatched(), objectCases()),
                        ratio(count(HlasmCaseOutcome.Status.PASSED), executionCases())))
                .append(String.format("%-16s %6s %6s %6s %6s %6s %6s%n",
                        "区分", "本数", "組立", "機械語差", "実行一致", "実行差", "破損"));
        byGroup().forEach((group, report) -> out.append(String.format(
                "%-16s %6d %6d %6d %6d %6d %6d%n", group, report.outcomes().size(),
                report.assembled(), report.count(HlasmCaseOutcome.Status.WRONG_OBJECT),
                report.count(HlasmCaseOutcome.Status.PASSED),
                report.count(HlasmCaseOutcome.Status.WRONG_OUTPUT),
                report.count(HlasmCaseOutcome.Status.CRASHED))));
        List<Map.Entry<String, Long>> reasons = reasons(15);
        if (!reasons.isEmpty()) {
            out.append("\n通らなかった理由 (多い順)\n");
            reasons.forEach(reason -> out.append(String.format("%6d  %s%n",
                    reason.getValue(), reason.getKey())));
        }
        return out.toString();
    }

    /**
     * 「比べた本が 0 なら 0.0%」ではなく「測っていない」と書く。
     * 0% は「全部外した」に見えるが、実際には基準がまだ無いだけである。
     */
    private static String ratio(long matched, long total) {
        return total == 0 ? "測っていない"
                : String.format("%d/%d (%.1f%%)", matched, total, 100.0 * matched / total);
    }

    /** 継続測定用。個々の資材名や中身は外へ出さない。 */
    public String csv() {
        StringBuilder out = new StringBuilder("group,total,assembled,object_checked,wrong_object,passed,"
                + "wrong_output,rejected,crashed,assembly_rate,object_rate,execution_rate\n");
        byGroup().forEach((group, report) -> row(out, group, report));
        row(out, "ALL", this);
        return out.toString();
    }

    private static void row(StringBuilder out, String group, HlasmVerificationReport report) {
        out.append(String.format("%s,%d,%d,%d,%d,%d,%d,%d,%d,%.1f,%.1f,%.1f%n", group,
                report.outcomes().size(), report.assembled(),
                report.objectCases(),
                report.count(HlasmCaseOutcome.Status.WRONG_OBJECT),
                report.count(HlasmCaseOutcome.Status.PASSED),
                report.count(HlasmCaseOutcome.Status.WRONG_OUTPUT),
                report.count(HlasmCaseOutcome.Status.REJECTED),
                report.count(HlasmCaseOutcome.Status.CRASHED),
                report.assemblyRate(), report.objectRate(), report.executionRate()));
    }
}
