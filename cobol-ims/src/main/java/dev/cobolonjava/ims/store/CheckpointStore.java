package dev.cobolonjava.ims.store;

import java.util.List;

/**
 * 記号 CHKP が退避した域の置き場 (設計 78 §1.1、暫定判断 P-164、P-110 の解消)。
 *
 * <p>退避した域は、業務の更新と同じ確定で書く。確定が失敗すれば検査点も残らないので、
 * 再始動したときの域とデータベースの状態が揃う。
 */
public interface CheckpointStore {

    /** 次の確定で、この検査点を書く。同じ PSB と ID の検査点があれば置き換える。 */
    void record(String psb, String checkpointId, List<byte[]> areas);

    /**
     * 検査点の域を読む。
     *
     * @return 無ければ {@code null}
     */
    List<byte[]> load(String psb, String checkpointId);
}
