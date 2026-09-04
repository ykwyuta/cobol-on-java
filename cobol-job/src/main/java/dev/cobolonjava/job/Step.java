package dev.cobolonjava.job;

import java.util.List;

/**
 * ジョブステップ 1 個 (要件 FR-130)。
 *
 * <p>ステップは<b>プログラム 1 本の実行</b>である。どのプログラムを、どんな引数で、
 * どのデータへ向けて動かすか。
 *
 * @param name      ステップ名。条件判定が名指す
 * @param program   動かすプログラムの名前
 * @param parm      {@code PARM=} の中身。指定がなければ {@code null}
 * @param dd        DD 割当。書かれた順に並ぶ
 * @param condition 動かすかどうかの条件
 */
public record Step(String name, String program, String parm, List<DdAssignment> dd,
                   StepCondition condition) {

    public Step {
        dd = List.copyOf(dd);
    }

    /** 条件のない、DD 割当だけのステップ。 */
    public static Step of(String name, String program, List<DdAssignment> dd) {
        return new Step(name, program, null, dd, new StepCondition.Always());
    }
}
