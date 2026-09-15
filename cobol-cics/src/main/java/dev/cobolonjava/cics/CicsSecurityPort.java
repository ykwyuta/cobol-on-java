package dev.cobolonjava.cics;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 利用者 (principal) と CICS の user ID の対応と、権限の確かめ方 (設計 84、暫定判断 P-145)。
 *
 * <p>確かめるのは次の 3 つだけである。
 * <ul>
 *   <li>principal からの user ID ({@link #userIdOf})。coordinator は要求が user ID を持たないときに使う</li>
 *   <li>transaction の attach ({@link #mayAttach})。coordinator がどの入口の task も起こす前に確かめる。
 *       START は命令の時点でも、起こす task の user ID で確かめる (NOTAUTH RESP2 7)</li>
 *   <li>START の USERID の代理 ({@link #maySurrogate})。START を出した task の user ID が USERID の user ID で
 *       task を起こしてよいか (NOTAUTH RESP2 9)</li>
 * </ul>
 * file、一時記憶、program などの資源ごとの権限は持たない。
 */
public interface CicsSecurityPort {

    /** principal 名の CICS の user ID。対応が無ければ空。 */
    Optional<String> userIdOf(String principal);

    /** user ID がこの transaction を起こしてよいか。user ID が空なら、構成によっては断る。 */
    boolean mayAttach(Optional<String> userId, TransId transaction);

    /** user ID の task が、別の user ID ({@code surrogateUserId}) の task を起こしてよいか。 */
    boolean maySurrogate(Optional<String> userId, String surrogateUserId);

    /**
     * 権限を構成していない region。principal 名が CICS の user ID の形 (1〜8 文字の英大文字・数字・国別文字) に
     * 収まれば大文字にして user ID とし (推測で切り詰めない)、どの transaction も起こせる。代理は同じ user ID に限る
     * (構成が無いのに他人の user ID で task を起こさせない)。
     */
    static CicsSecurityPort derived() {
        return new CicsSecurityPort() {
            @Override
            public Optional<String> userIdOf(String principal) {
                String upper = Objects.requireNonNull(principal, "principal").toUpperCase(Locale.ROOT);
                return upper.matches("[A-Z0-9@#$]{1,8}") ? Optional.of(upper) : Optional.empty();
            }

            @Override
            public boolean mayAttach(Optional<String> userId, TransId transaction) {
                return true;
            }

            @Override
            public boolean maySurrogate(Optional<String> userId, String surrogateUserId) {
                return userId.isPresent() && userId.orElseThrow().equals(surrogateUserId);
            }
        };
    }
}
