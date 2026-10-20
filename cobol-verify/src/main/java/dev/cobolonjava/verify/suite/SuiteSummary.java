package dev.cobolonjava.verify.suite;

import dev.cobolonjava.verify.corpus.Reasons;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 検査スイートの結末を、人が読む表にする (設計 87)。
 *
 * <p>数は門にしない (CLAUDE.md §7)。並べて記録するためのものである。通らなかったものは、理由の文面を
 * 数字を伏せて数え (CLAUDE.md §4)、止まった行は本ごとに残す。理由が細かく散ったときに行を見るためである。
 */
public final class SuiteSummary {

    private SuiteSummary() {
    }

    public static String text(String title, List<SuiteOutcome> outcomes) {
        StringBuilder out = new StringBuilder();
        out.append(title).append('\n');
        int programs = outcomes.size();
        long passed = outcomes.stream().filter(SuiteOutcome::succeeded).count();
        List<SuiteOutcome> runs = outcomes.stream()
                .filter(outcome -> outcome.expect() == SuiteManifest.Expect.RUN).toList();
        int expected = runs.stream().mapToInt(SuiteOutcome::expected).sum();
        int pass = runs.stream().mapToInt(SuiteOutcome::passed).sum();
        int fail = runs.stream().mapToInt(SuiteOutcome::failed).sum();
        int missing = runs.stream().mapToInt(SuiteOutcome::missing).sum();
        List<SuiteOutcome> negatives = outcomes.stream()
                .filter(outcome -> outcome.expect() == SuiteManifest.Expect.COMPILE_ERROR).toList();
        long refused = negatives.stream().filter(SuiteOutcome::succeeded).count();
        long worded = negatives.stream().filter(SuiteOutcome::zeusWording).count();
        List<SuiteOutcome> controls = outcomes.stream()
                .filter(outcome -> outcome.expect() == SuiteManifest.Expect.COMPILE_ONLY).toList();
        out.append(String.format("主プログラム %d 本: 期待どおり %d 本%n", programs, passed));
        out.append(String.format("実行 %d 本: 全検査が通った %d 本。検査は %d 件のうち 通った %d / 落ちた %d / 出なかった %d%n",
                runs.size(), runs.stream().filter(SuiteOutcome::succeeded).count(), expected, pass, fail, missing));
        out.append(String.format("翻訳で断るべきもの %d 本: 断った %d 本 (うち Zeus の診断と同じ語を含んだもの %d 本。参考)%n",
                negatives.size(), refused, worded));
        out.append(String.format("翻訳が通るべき対照 %d 本: 通った %d 本%n", controls.size(),
                controls.stream().filter(SuiteOutcome::succeeded).count()));
        out.append('\n');
        out.append(String.format("%-8s %5s %5s %7s %7s %7s %7s%n", "区分", "本数", "期待", "検査", "通", "落", "出ず"));
        Map<String, List<SuiteOutcome>> modules = new LinkedHashMap<>();
        for (SuiteOutcome outcome : outcomes) {
            modules.computeIfAbsent(outcome.module(), key -> new java.util.ArrayList<>()).add(outcome);
        }
        modules.forEach((module, group) -> out.append(String.format("%-8s %5d %5d %7d %7d %7d %7d%n", module,
                group.size(), group.stream().filter(SuiteOutcome::succeeded).count(),
                group.stream().filter(o -> o.expect() == SuiteManifest.Expect.RUN).mapToInt(SuiteOutcome::expected).sum(),
                group.stream().filter(o -> o.expect() == SuiteManifest.Expect.RUN).mapToInt(SuiteOutcome::passed).sum(),
                group.stream().filter(o -> o.expect() == SuiteManifest.Expect.RUN).mapToInt(SuiteOutcome::failed).sum(),
                group.stream().filter(o -> o.expect() == SuiteManifest.Expect.RUN).mapToInt(SuiteOutcome::missing).sum())));
        out.append('\n');
        out.append("期待どおりでなかった理由 (多い順。数字は伏せた)\n");
        Map<String, Integer> reasons = new TreeMap<>();
        for (SuiteOutcome outcome : outcomes) {
            if (!outcome.succeeded()) {
                reasons.merge(outcome.status() + ": " + Reasons.normalized(stripPosition(outcome.reason())), 1,
                        Integer::sum);
            }
        }
        reasons.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> out.append(String.format("%6d  %s%n", entry.getValue(), entry.getKey())));
        out.append('\n');
        out.append("本ごと (期待どおりでなかったもの)\n");
        for (SuiteOutcome outcome : outcomes) {
            if (!outcome.succeeded()) {
                out.append(String.format("  %-8s %-16s %s%n", outcome.program(), outcome.status(), outcome.reason()));
            }
        }
        return out.toString();
    }

    /** 「ZA101A.CBL:12:8: 」の頭を外す。同じ原因が行の番号で散らないようにする。 */
    private static String stripPosition(String reason) {
        return reason == null ? "" : reason.replaceFirst("^[^:\\s]+:\\d+:\\d+: ", "");
    }
}
