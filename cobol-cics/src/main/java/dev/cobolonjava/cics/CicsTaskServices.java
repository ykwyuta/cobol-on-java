package dev.cobolonjava.cics;

import dev.cobolonjava.runtime.interop.CobolSession;
import dev.cobolonjava.runtime.interop.RuntimeServices;

/**
 * task の境界が COBOL の session に見せる service (暫定判断 P-143)。
 *
 * <p>Db2 の UOW を持つ境界は、生成 COBOL の EXEC SQL が同じ UOW へ届くよう {@code Db2Execution} を足す。
 * task program は境界がこれを実装していれば、session を開く前に {@link #contribute}、開いたあとに {@link #bind} を呼ぶ。
 */
public interface CicsTaskServices {

    void contribute(RuntimeServices.Builder services);

    default void bind(CobolSession session) {
    }
}
