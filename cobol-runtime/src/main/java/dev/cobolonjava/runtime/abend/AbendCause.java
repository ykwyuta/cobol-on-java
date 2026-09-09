package dev.cobolonjava.runtime.abend;

/**
 * 異常終了になる条件 (要件 FR-141)。
 *
 * <p>実行を打ち切る例外はこれを実装し、<b>自分がどのコードで終わるかを名乗る</b>。
 * 呼び出し側は例外の種類を並べた対応表を持たずに済み、条件を足しても表を直し忘れない。
 */
public interface AbendCause {

    /** この条件で終わるときのコード。 */
    AbendCode abendCode();
}
