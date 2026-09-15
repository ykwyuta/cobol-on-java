package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsSecurityPort;
import dev.cobolonjava.cics.TransId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * デモ環境の簡易な権限 (設計 84、暫定判断 P-145)。{@link CicsSecurityProperties} の利用者の一覧で決める。
 *
 * <ul>
 *   <li>principal (ログイン名) → 一覧の {@code user-id}。一覧に無い principal は user ID を持たず、どの transaction も起こせない</li>
 *   <li>attach → user ID の {@code transactions} に TRANSID か {@code *} がある</li>
 *   <li>代理 → 同じ user ID か、user ID の {@code surrogates} にある</li>
 * </ul>
 *
 * <p>構成の誤りは起動の時点で断る (user ID の形、重複、encoder の接頭の無いパスワード、TRANSID の形)。
 * 例外の文面にパスワードを出さない。
 */
public final class DemoCicsSecurity implements CicsSecurityPort {

    private static final Pattern USER_ID = Pattern.compile("[A-Z0-9@#$]{1,8}");
    private static final Pattern ENCODED = Pattern.compile("\\{[A-Za-z0-9_-]+}.+");

    private final List<CicsSecurityProperties.User> users;
    private final Map<String, CicsSecurityProperties.User> byPrincipal = new HashMap<>();
    private final Map<String, CicsSecurityProperties.User> byUserId = new HashMap<>();

    public DemoCicsSecurity(List<CicsSecurityProperties.User> users) {
        this.users = List.copyOf(Objects.requireNonNull(users, "users"));
        for (int index = 0; index < this.users.size(); index++) {
            CicsSecurityProperties.User user = this.users.get(index);
            String where = "cobol.cics.security.users[" + index + "]";
            if (user.userId() == null || !USER_ID.matcher(user.userId()).matches()) {
                throw new IllegalArgumentException(where + ".user-id must be 1 to 8 upper-case letters, digits or @#$");
            }
            if (byUserId.put(user.userId(), user) != null) {
                throw new IllegalArgumentException(where + ".user-id is defined more than once: " + user.userId());
            }
            if (user.username() != null) {
                if (user.username().isBlank()) {
                    throw new IllegalArgumentException(where + ".username must not be blank");
                }
                if (byPrincipal.put(user.username(), user) != null) {
                    throw new IllegalArgumentException(where + ".username is defined more than once: "
                            + user.username());
                }
                if (user.password() == null || !ENCODED.matcher(user.password()).matches()) {
                    // 平文のパスワードを推測で受けない。{noop} も明示させる
                    throw new IllegalArgumentException(where + ".password must start with an encoder prefix"
                            + " such as {bcrypt}");
                }
            } else if (user.password() != null) {
                throw new IllegalArgumentException(where + ".password requires username");
            }
            for (String transaction : user.transactions()) {
                if (!transaction.equals("*")) {
                    TransId.of(transaction);
                }
            }
            for (String surrogate : user.surrogates()) {
                if (surrogate == null || !USER_ID.matcher(surrogate).matches()) {
                    throw new IllegalArgumentException(where + ".surrogates must be CICS user IDs");
                }
            }
        }
    }

    /** ログインできる利用者 (ログイン名とパスワードを持つ)。 */
    public List<CicsSecurityProperties.User> loginUsers() {
        return users.stream().filter(user -> user.username() != null).toList();
    }

    @Override
    public Optional<String> userIdOf(String principal) {
        return Optional.ofNullable(byPrincipal.get(Objects.requireNonNull(principal, "principal")))
                .map(CicsSecurityProperties.User::userId);
    }

    @Override
    public boolean mayAttach(Optional<String> userId, TransId transaction) {
        Objects.requireNonNull(transaction, "transaction");
        return userId.map(byUserId::get)
                .map(user -> user.transactions().contains("*") || user.transactions().contains(transaction.value()))
                .orElse(false);
    }

    @Override
    public boolean maySurrogate(Optional<String> userId, String surrogateUserId) {
        Objects.requireNonNull(surrogateUserId, "surrogateUserId");
        return userId.map(value -> value.equals(surrogateUserId)
                        || Optional.ofNullable(byUserId.get(value))
                                .map(user -> user.surrogates().contains(surrogateUserId)).orElse(false))
                .orElse(false);
    }
}
