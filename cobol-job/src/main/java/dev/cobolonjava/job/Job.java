package dev.cobolonjava.job;

import java.util.List;

/**
 * ジョブ 1 個 (要件 FR-130)。
 *
 * <p>これが<b>内部ジョブモデル</b>である。記述形式に依らない。JCL からも宣言的形式からも
 * ここへ落ち、実行機構はこれだけを解釈する。したがって<b>どちらの形式で書いても
 * 結果は同じ</b>になる (要件 FR-132)。
 */
public record Job(String name, List<Step> steps) {

    public Job {
        steps = List.copyOf(steps);
    }
}
