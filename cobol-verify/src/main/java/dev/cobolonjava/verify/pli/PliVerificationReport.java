package dev.cobolonjava.verify.pli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** PL/I コーパスの翻訳率と参照処理系との一致率を別々に数える。 */
public record PliVerificationReport(List<PliCaseOutcome> outcomes) {

    public PliVerificationReport {
        outcomes = List.copyOf(outcomes);
    }

    public long count(PliCaseOutcome.Status status) {
        return outcomes.stream().filter(value -> value.status() == status).count();
    }

    public long translated() {
        return outcomes.size() - count(PliCaseOutcome.Status.REJECTED)
                - count(PliCaseOutcome.Status.CRASHED);
    }

    public long oracleCases() {
        return count(PliCaseOutcome.Status.PASSED) + count(PliCaseOutcome.Status.WRONG_OUTPUT);
    }

    public double translationRate() {
        return outcomes.isEmpty() ? 0 : 100.0 * translated() / outcomes.size();
    }

    public double oracleRate() {
        return oracleCases() == 0 ? 0
                : 100.0 * count(PliCaseOutcome.Status.PASSED) / oracleCases();
    }

    public Map<String, PliVerificationReport> byGroup() {
        Map<String, List<PliCaseOutcome>> grouped = new TreeMap<>();
        for (PliCaseOutcome outcome : outcomes) {
            grouped.computeIfAbsent(outcome.group(), unused -> new ArrayList<>()).add(outcome);
        }
        Map<String, PliVerificationReport> result = new LinkedHashMap<>();
        grouped.forEach((group, values) -> result.put(group,
                new PliVerificationReport(values)));
        return result;
    }

    /** 次に実装する機能を選ぶため、失敗理由を多い順に返す。 */
    public List<Map.Entry<String, Long>> reasons(int limit) {
        Map<String, Long> counted = new LinkedHashMap<>();
        for (PliCaseOutcome outcome : outcomes) {
            if (!outcome.reason().isEmpty()) counted.merge(outcome.reason(), 1L, Long::sum);
        }
        return counted.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit).toList();
    }

    public String text(String title) {
        StringBuilder out = new StringBuilder(title).append('\n')
                .append("=".repeat(title.length())).append("\n\n")
                .append(String.format("合計 %d 本: 翻訳 %d (%.1f%%)、参照出力一致 %d/%d (%.1f%%)%n%n",
                        outcomes.size(), translated(), translationRate(),
                        count(PliCaseOutcome.Status.PASSED), oracleCases(), oracleRate()))
                .append(String.format("%-16s %6s %6s %6s %6s %6s%n",
                        "区分", "本数", "翻訳", "一致", "不一致", "破損"));
        byGroup().forEach((group, report) -> out.append(String.format(
                "%-16s %6d %6d %6d %6d %6d%n", group, report.outcomes().size(),
                report.translated(), report.count(PliCaseOutcome.Status.PASSED),
                report.count(PliCaseOutcome.Status.WRONG_OUTPUT),
                report.count(PliCaseOutcome.Status.CRASHED))));
        List<Map.Entry<String, Long>> reasons = reasons(15);
        if (!reasons.isEmpty()) {
            out.append("\n通らなかった理由 (多い順)\n");
            reasons.forEach(reason -> out.append(String.format("%6d  %s%n",
                    reason.getValue(), reason.getKey())));
        }
        return out.toString();
    }

    /** 継続測定用。個々の資産名や内容は外へ出さない。 */
    public String csv() {
        StringBuilder out = new StringBuilder(
                "group,total,translated,passed,wrong_output,rejected,crashed,translation_rate,oracle_rate\n");
        byGroup().forEach((group, report) -> row(out, group, report));
        row(out, "ALL", this);
        return out.toString();
    }

    private static void row(StringBuilder out, String group, PliVerificationReport report) {
        out.append(String.format("%s,%d,%d,%d,%d,%d,%d,%.1f,%.1f%n", group,
                report.outcomes().size(), report.translated(),
                report.count(PliCaseOutcome.Status.PASSED),
                report.count(PliCaseOutcome.Status.WRONG_OUTPUT),
                report.count(PliCaseOutcome.Status.REJECTED),
                report.count(PliCaseOutcome.Status.CRASHED), report.translationRate(),
                report.oracleRate()));
    }
}
