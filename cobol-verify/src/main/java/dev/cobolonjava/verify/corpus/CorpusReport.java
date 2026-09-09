package dev.cobolonjava.verify.corpus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 流した結果の数え上げ (要件 NFR-040, NFR-042)。
 *
 * <p>公開するのは<b>数だけ</b>である。要件 NFR-042 が言うとおり、コーパスそのものは
 * 同梱も公開もしない。
 *
 * <h2>区分ごとに数える</h2>
 * <p>全体の合格率は 1 つの数だが、それだけでは<b>どこが弱いか</b>が分からない。CCVS85 なら
 * 検査モジュール、資産なら持ち主で分けて数える。要件 13 章の受け入れ基準が
 * 「入出力以外のモジュールの合格率」であるのも、区分ごとに見なければ意味がないからである。
 */
public record CorpusReport(List<CompileOutcome> outcomes) {

    /** 通った数。 */
    public long compiled() {
        return count(CompileOutcome.Status.COMPILED);
    }

    /** 読めないと言って断った数。 */
    public long rejected() {
        return count(CompileOutcome.Status.REJECTED);
    }

    /** 言わずに壊れた数。 */
    public long crashed() {
        return count(CompileOutcome.Status.CRASHED);
    }

    private long count(CompileOutcome.Status status) {
        return outcomes.stream().filter(o -> o.status() == status).count();
    }

    /** 合格率 (百分率)。1 本も無ければ 0 である。 */
    public double rate() {
        return outcomes.isEmpty() ? 0 : 100.0 * compiled() / outcomes.size();
    }

    /** 区分だけを選んだ数え上げ。 */
    public CorpusReport only(List<String> groups) {
        return new CorpusReport(outcomes.stream()
                .filter(o -> groups.contains(o.group()))
                .toList());
    }

    /** 区分ごとの数え上げ。並びは区分の名前の順である。 */
    public Map<String, CorpusReport> byGroup() {
        Map<String, List<CompileOutcome>> grouped = new TreeMap<>();
        for (CompileOutcome outcome : outcomes) {
            grouped.computeIfAbsent(outcome.group(), k -> new ArrayList<>()).add(outcome);
        }
        Map<String, CorpusReport> out = new LinkedHashMap<>();
        grouped.forEach((group, list) -> out.put(group, new CorpusReport(List.copyOf(list))));
        return out;
    }

    /**
     * 通らなかった理由を、多い順に。
     *
     * <p>これが<b>次に何を書くか</b>の一覧である。1 件しか止めていない構文より、
     * 100 件を止めている構文を先に書くほうがよい。
     */
    public List<Map.Entry<String, Long>> reasons(int limit) {
        Map<String, Long> counted = new LinkedHashMap<>();
        for (CompileOutcome outcome : outcomes) {
            if (outcome.status() == CompileOutcome.Status.COMPILED) {
                continue;
            }
            counted.merge(outcome.reason(), 1L, Long::sum);
        }
        return counted.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .toList();
    }

    /** 壊れたものの名前。直す順を決めるのに使う。 */
    public List<CompileOutcome> crashes() {
        return outcomes.stream()
                .filter(o -> o.status() == CompileOutcome.Status.CRASHED)
                .sorted(Comparator.comparing(CompileOutcome::name))
                .toList();
    }

    /** 人が読む形。 */
    public String text(String title) {
        StringBuilder out = new StringBuilder();
        out.append(title).append('\n');
        out.append("=".repeat(title.length())).append("\n\n");
        out.append(String.format(
                "合計 %d 本: 通った %d / 断った %d / 壊れた %d  (合格率 %.1f%%)%n",
                outcomes.size(), compiled(), rejected(), crashed(), rate()));
        out.append('\n');
        out.append(String.format("%-16s %6s %6s %6s %6s  %s%n",
                "区分", "本数", "通", "断", "壊", "合格率"));
        byGroup().forEach((group, report) -> out.append(String.format(
                "%-16s %6d %6d %6d %6d  %5.1f%%%n",
                group, report.outcomes().size(), report.compiled(), report.rejected(),
                report.crashed(), report.rate())));
        List<Map.Entry<String, Long>> reasons = reasons(15);
        if (!reasons.isEmpty()) {
            out.append("\n通らなかった理由 (多い順)\n");
            for (Map.Entry<String, Long> reason : reasons) {
                out.append(String.format("%6d  %s%n", reason.getValue(), reason.getKey()));
            }
        }
        List<CompileOutcome> crashes = crashes();
        if (!crashes.isEmpty()) {
            out.append("\n言わずに壊れたもの (処理系の欠陥)\n");
            for (CompileOutcome crash : crashes) {
                out.append(String.format("  %-10s %s%n", crash.name(), crash.failure()));
            }
        }
        return out.toString();
    }

    /**
     * 機械が読む形。
     *
     * <p>公開するのは数だけなので、名前は出さない。継続的に取って並べるためのものである。
     */
    public String csv() {
        StringBuilder out = new StringBuilder("group,total,compiled,rejected,crashed,rate\n");
        byGroup().forEach((group, report) -> out.append(String.format("%s,%d,%d,%d,%d,%.1f%n",
                group, report.outcomes().size(), report.compiled(), report.rejected(),
                report.crashed(), report.rate())));
        out.append(String.format("ALL,%d,%d,%d,%d,%.1f%n", outcomes.size(), compiled(),
                rejected(), crashed(), rate()));
        return out.toString();
    }
}
