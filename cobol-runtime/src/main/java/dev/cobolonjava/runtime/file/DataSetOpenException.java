package dev.cobolonjava.runtime.file;

import dev.cobolonjava.runtime.abend.AbendCause;
import dev.cobolonjava.runtime.abend.AbendCode;
import java.nio.file.Path;

/**
 * データセットを開けなかった (要件 FR-113, FR-141)。
 *
 * <p>ファイル状態コードにならないのが要点である。{@code 35} は「この {@code OPEN} 文が
 * 失敗した」であり、プログラムは {@code FILE STATUS} で受け止めて先へ進める。こちらは
 * <b>開くという操作そのものが成り立たなかった</b>ということで、プログラムへ制御は戻らない。
 * ホストで {@code S013} になるのがこの位置付けである。
 *
 * <p>割当てが通ったのに開けない、というのがこの誤りの立つところである。データセットは
 * あった (だから割当ては通った) が、その中の<b>メンバが無い</b>、あるいは区分データセットを
 * メンバを言わずに開こうとした。割当ての段では分からず、開く段で初めて分かる。
 *
 * <p>区別が効くのは、割当ての失敗が<b>ステップを動かさない</b> (JCL エラー) のに対し、
 * こちらは<b>ステップが動いてから止まる</b>からである。それまでに書いたものは残り、
 * {@code IF (STEP.ABENDCC = S013)} で受けられる。
 */
public final class DataSetOpenException extends RuntimeException implements AbendCause {

    private static final long serialVersionUID = 1L;

    public DataSetOpenException(String why, Path path) {
        super(why + ": " + path);
    }

    @Override
    public AbendCode abendCode() {
        return AbendCode.S013;
    }
}
