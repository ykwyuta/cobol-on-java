package dev.cobolonjava.job;

import java.util.List;

/**
 * ステップを動かすかどうかの条件 (要件 FR-130, FR-136)。
 *
 * <p>JCL の {@code COND} は<b>「真なら飛ばす」</b>という向きで書く。逆さまで読みにくく、
 * 誤りのもとでもある。内部モデルでは<b>「真なら動かす」</b>の向きに揃え、
 * 向きの反転はフロントエンドの仕事とした。
 *
 * <p>異常終了の扱いを条件の一部として持たせている。ホストでは先行ステップが異常終了すると
 * 以降は飛ばされるが、{@code COND=EVEN} と {@code COND=ONLY} がそれを覆す。
 * 「飛ばす／飛ばさない」を決めるものは 1 か所にあるほうがよい。
 */
public sealed interface StepCondition {

    /** この条件でステップを動かすか。 */
    boolean allows(JobState state);

    /** 異常終了があったときも動かすか。 */
    default boolean survivesAbend() {
        return false;
    }

    /** いつでも動かす。先行ステップが異常終了していれば動かない。 */
    record Always() implements StepCondition {

        @Override
        public boolean allows(JobState state) {
            return true;
        }
    }

    /**
     * 復帰コードを比べる。
     *
     * @param step 比べる相手のステップ。{@code null} なら<b>これまでのいちばん大きい値</b>
     */
    record ReturnCode(String step, Comparison comparison, int value) implements StepCondition {

        @Override
        public boolean allows(JobState state) {
            if (step == null) {
                return comparison.holds(Integer.compare(state.highest(), value));
            }
            Integer actual = state.returnCode(step);
            if (actual == null) {
                // 動いていないステップの復帰コードは比べられない。条件は成り立たない
                return false;
            }
            return comparison.holds(Integer.compare(actual, value));
        }
    }

    /** 否定。 */
    record Not(StepCondition inner) implements StepCondition {

        @Override
        public boolean allows(JobState state) {
            return !inner.allows(state);
        }

        @Override
        public boolean survivesAbend() {
            return inner.survivesAbend();
        }
    }

    /** すべて成り立つとき。 */
    record All(List<StepCondition> parts) implements StepCondition {

        public All {
            parts = List.copyOf(parts);
        }

        @Override
        public boolean allows(JobState state) {
            return parts.stream().allMatch(part -> part.allows(state));
        }

        @Override
        public boolean survivesAbend() {
            return parts.stream().anyMatch(StepCondition::survivesAbend);
        }
    }

    /** どれか成り立つとき。 */
    record Any(List<StepCondition> parts) implements StepCondition {

        public Any {
            parts = List.copyOf(parts);
        }

        @Override
        public boolean allows(JobState state) {
            return parts.stream().anyMatch(part -> part.allows(state));
        }

        @Override
        public boolean survivesAbend() {
            return parts.stream().anyMatch(StepCondition::survivesAbend);
        }
    }

    /** {@code COND=EVEN}。異常終了していても動かす。 */
    record EvenIfAbend() implements StepCondition {

        @Override
        public boolean allows(JobState state) {
            return true;
        }

        @Override
        public boolean survivesAbend() {
            return true;
        }
    }

    /** {@code COND=ONLY}。異常終了したときだけ動かす。後始末の処理がこれを使う。 */
    record OnlyIfAbend() implements StepCondition {

        @Override
        public boolean allows(JobState state) {
            return state.abended();
        }

        @Override
        public boolean survivesAbend() {
            return true;
        }
    }

    /** 比べ方。 */
    enum Comparison {
        EQ, NE, LT, LE, GT, GE;

        /** 比較の符号がこの関係を満たすか。 */
        public boolean holds(int order) {
            return switch (this) {
                case EQ -> order == 0;
                case NE -> order != 0;
                case LT -> order < 0;
                case LE -> order <= 0;
                case GT -> order > 0;
                case GE -> order >= 0;
            };
        }

        /** 逆さまの関係。{@code COND} の向きを裏返すときに使う。 */
        public Comparison negate() {
            return switch (this) {
                case EQ -> NE;
                case NE -> EQ;
                case LT -> GE;
                case LE -> GT;
                case GT -> LE;
                case GE -> LT;
            };
        }
    }
}
