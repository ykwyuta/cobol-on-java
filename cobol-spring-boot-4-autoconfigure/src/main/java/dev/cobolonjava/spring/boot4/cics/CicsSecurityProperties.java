package dev.cobolonjava.spring.boot4.cics;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CICS の利用者と権限の構成 (設計 84、暫定判断 P-145)。
 *
 * <p>{@code mode=demo} のときだけ使う。デモ環境のための簡易な構成であり、本番の利用者管理 (RACF 等) の代わりにはならない。
 *
 * <pre>
 * cobol:
 *   cics:
 *     security:
 *       mode: demo
 *       users:
 *         - username: alice             # Spring Security のログイン名 (principal)
 *           password: "{bcrypt}$2a$..."  # {bcrypt} / {noop} などの接頭が要る
 *           user-id: ALICE01             # CICS の user ID (1〜8 文字の英大文字・数字・国別文字)
 *           transactions: [BNK1, INQ1]   # 起こせる TRANSID。"*" はすべて
 *           surrogates: [BATCH01]        # START USERID で代理できる user ID
 *         - user-id: BATCH01             # ログインしない user ID (START USERID や ATI の USERID で使う)
 *           transactions: [BTCH]
 * </pre>
 *
 * @param mode  {@code demo} で有効。書かなければ principal 名を user ID にし、どの transaction も許す
 * @param users 利用者の一覧
 */
@ConfigurationProperties(prefix = "cobol.cics.security")
public record CicsSecurityProperties(String mode, List<User> users) {

    public CicsSecurityProperties {
        users = users == null ? List.of() : List.copyOf(users);
    }

    /**
     * 1 人の利用者。
     *
     * @param username     ログイン名。書かなければログインできない user ID になる
     * @param password     パスワード。{@code {bcrypt}} などの encoder の接頭が要る。ログイン名があるときは必須
     * @param userId       CICS の user ID
     * @param transactions 起こせる TRANSID。{@code *} はすべて
     * @param surrogates   START USERID で代理できる user ID
     */
    public record User(String username, String password, String userId, List<String> transactions,
                       List<String> surrogates) {

        public User {
            transactions = transactions == null ? List.of() : List.copyOf(transactions);
            surrogates = surrogates == null ? List.of() : List.copyOf(surrogates);
        }
    }
}
