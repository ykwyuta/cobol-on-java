package dev.cobolonjava.job;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ジョブの進み具合 (要件 FR-130, FR-136)。
 *
 * <p>後続ステップの条件判定が見るのは<b>ここまでに起きたこと</b>である。
 * どのステップがいくつを返したか、異常終了したか、いちばん大きい復帰コードはいくつか。
 */
public final class JobState {

    private final Map<String, Integer> returnCodes = new LinkedHashMap<>();
    private boolean abended;
    private int highest;

    /** ステップが終わったことを記録する。 */
    public void completed(String step, int returnCode) {
        returnCodes.put(step, returnCode);
        highest = Math.max(highest, returnCode);
    }

    /** ステップが異常終了したことを記録する。 */
    public void abended(String step) {
        abended = true;
        returnCodes.put(step, -1);
    }

    /**
     * ステップの復帰コード。
     *
     * @return 動いていなければ {@code null}。飛ばされたステップも動いていない
     */
    public Integer returnCode(String step) {
        return returnCodes.get(step);
    }

    /** これまでのいちばん大きい復帰コード。ジョブ全体の終了コードになる。 */
    public int highest() {
        return highest;
    }

    /** どれかのステップが異常終了したか。 */
    public boolean abended() {
        return abended;
    }

    /** 記録されている復帰コード。ステップの順に並ぶ。 */
    public Map<String, Integer> returnCodes() {
        return Map.copyOf(returnCodes);
    }
}
