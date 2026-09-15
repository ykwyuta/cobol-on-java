package dev.cobolonjava.spring.boot4.bms;

import java.util.Optional;

/**
 * 利用者ごとに固定の端末名を与える構成 (設計 83 §4.1)。
 *
 * <p>ATIFACILITY(TERMINAL) の FACILITYID や START TERMID のように、資産が端末の名前を決め打ちしているとき、その名前を
 * 特定の利用者のブラウザ端末に割り当てる。名前を返さない利用者には、HTTP session ごとに乱数で端末名を振る。
 * 同じ名前を別の利用者が使っている間は、その利用者の要求は 409 になる。
 */
@FunctionalInterface
public interface CicsBrowserTerminalNames {

    /** principal 名に割り当てた端末名。無ければ空。 */
    Optional<String> terminalFor(String principal);

    /** どの利用者にも固定の名前を与えない。 */
    static CicsBrowserTerminalNames dynamic() {
        return principal -> Optional.empty();
    }
}
