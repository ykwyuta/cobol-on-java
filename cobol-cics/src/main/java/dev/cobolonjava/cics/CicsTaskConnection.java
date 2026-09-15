package dev.cobolonjava.cics;

import java.util.function.Function;

/**
 * task の業務の UOW の上の資源で更新をする入口 (設計 85 §7.2、暫定判断 P-148)。
 *
 * <p>STRICT の task 境界が task の services に置く。回復可能な一時データのキューは、この資源 (JDBC の connection) で更新して
 * 業務の更新と一緒に commit / rollback する。この module は framework と JDBC の型を持たないので、資源の型は呼び手が
 * {@code type} で示す。資源は閉じず、autoCommit も commit も触らない。
 */
public interface CicsTaskConnection {

    /** type の資源で action を行う。境界がその型の資源を持たなければ失敗させる。 */
    <R, T> T withResource(Class<R> type, Function<R, T> action);
}
