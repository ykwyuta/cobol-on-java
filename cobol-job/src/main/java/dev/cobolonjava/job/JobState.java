package dev.cobolonjava.job;

import dev.cobolonjava.runtime.abend.AbendCode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ジョブの進み具合 (要件 FR-130, FR-136)。
 *
 * <p>後続ステップの条件判定が見るのは<b>ここまでに起きたこと</b>である。
 * どのステップがいくつを返したか、異常終了したか、いちばん大きい復帰コードはいくつか。
 *
 * <h2>異常終了コードも残す</h2>
 * <p>{@code IF ABENDCC = S0C7} が比べる相手である (要件 FR-141)。異常終了は「起きたか
 * どうか」だけでなく<b>何が起きたか</b>で分かれる。データの誤りなら入力を直せばよく、
 * ロードモジュールが無いなら組み立てを直す。後始末のしかたも違う。
 */
public final class JobState {

    private final Map<String, Integer> returnCodes = new LinkedHashMap<>();
    private final Map<String, AbendCode> abendCodes = new LinkedHashMap<>();
    private boolean abended;
    private int highest;

    /** ステップが終わったことを記録する。 */
    public void completed(String step, int returnCode) {
        returnCodes.put(step, returnCode);
        highest = Math.max(highest, returnCode);
    }

    /**
     * ステップが異常終了したことを記録する。
     *
     * @param code 分かっていれば異常終了コード。分からなければ {@code null}
     */
    public void abended(String step, AbendCode code) {
        abended = true;
        returnCodes.put(step, -1);
        if (code != null) {
            abendCodes.put(step, code);
        }
    }

    /**
     * ステップの異常終了コード。
     *
     * @return 異常終了していないか、コードが分からなければ {@code null}
     */
    public AbendCode abendCode(String step) {
        return abendCodes.get(step);
    }

    /**
     * 最後に分かった異常終了コード。
     *
     * <p>ステップを言わない {@code IF ABENDCC = ...} が見る相手である。
     *
     * @return まだ無ければ {@code null}
     */
    public AbendCode abendCode() {
        AbendCode last = null;
        for (AbendCode code : abendCodes.values()) {
            last = code;
        }
        return last;
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
