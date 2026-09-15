package dev.cobolonjava.cics;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ブラウザ等の端末の登録 (設計 83 §4、暫定判断 P-144)。
 *
 * <p>端末が「定義されているか」「task が動いているか」「疑似会話の途中か」を、HTTP session を持つ JVM の外からも
 * 見られるようにする。端末で task を動かす要求は、先に端末を {@link #lease} し、終わったら {@link #release} する。
 * そのため 1 つの端末で task は同時に 1 つになる。疑似会話の参照は lease を持つ要求だけが {@link #setConversation} で書き換える。
 *
 * <p>端末の名前は {@code W} と 36 進 3 桁 (4 文字、EIBTRMID の長さ) で、乱数で選んで重なれば選び直す。
 * 同時に持てる端末は 46656 までである。
 */
public interface CicsTerminalRegistryPort {

    /** 名前を選び直す回数の上限。越えれば端末の名前が尽きたとして失敗させる。 */
    int REGISTER_ATTEMPTS = 64;

    /** 端末の疑似会話。次の要求はこの版の会話を、この TRANSID で続ける。 */
    record TerminalConversation(ConversationId id, long version, TransId nextTransaction) {

        public TerminalConversation {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(nextTransaction, "nextTransaction");
            if (version < 0) {
                throw new IllegalArgumentException("conversation version must not be negative");
            }
        }
    }

    /**
     * 登録された端末。
     *
     * @param leased task が動いている (lease の期限が過ぎていない)
     */
    record Terminal(String id, String owner, Instant expiresAt, Optional<TerminalConversation> conversation,
                    boolean leased) {

        public Terminal {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(conversation, "conversation");
        }
    }

    /** 端末で task を動かす権利。期限はミリ秒に揃える (JDBC の列と等しく比べるため)。 */
    record TerminalLease(String terminalId, ConversationLeaseToken token, Instant leasedUntil) {

        public TerminalLease {
            Objects.requireNonNull(terminalId, "terminalId");
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(leasedUntil, "leasedUntil");
        }
    }

    /**
     * 新しい端末を登録する。
     *
     * @return 振った端末の名前
     * @throws IllegalStateException {@link #REGISTER_ATTEMPTS} 回選んでも空いた名前が無い
     */
    String register(String owner, Instant expiresAt, Instant now);

    /**
     * 名前を決めた端末を登録する (利用者ごとの固定の端末名、設計 83 §4.1)。
     *
     * <p>期限の過ぎていない同じ名前の端末が同じ owner のものなら、期限を延ばしてそれを使う (同じ利用者の別の HTTP session も
     * 同じ端末になる)。別の owner のものなら何もせず false を返す。
     */
    boolean registerNamed(String terminalId, String owner, Instant expiresAt, Instant now);

    /** 端末の名前の形 (1〜4 文字)。 */
    static String requireTerminalId(String terminalId) {
        Objects.requireNonNull(terminalId, "terminalId");
        if (!terminalId.matches("[A-Z0-9@#$]{1,4}")) {
            throw new IllegalArgumentException("terminal ID must be 1 to 4 characters: " + terminalId);
        }
        return terminalId;
    }

    /** 登録されていて期限の過ぎていない端末。 */
    Optional<Terminal> find(String terminalId, Instant now);

    /** 同じ owner の期限の過ぎていない端末の期限を延ばす。できなければ false (端末が無い、期限切れ、owner が違う)。 */
    boolean touch(String terminalId, String owner, Instant expiresAt, Instant now);

    /** 端末を lease する。端末が無い、期限切れ、owner が違う、別の lease がある、のどれかなら空。 */
    Optional<TerminalLease> lease(String terminalId, String owner, Duration duration, Instant now);

    /** lease を持つ要求が端末の疑似会話を書き換える。lease が合わないか期限切れなら false で、何も変えない。 */
    boolean setConversation(TerminalLease lease, Optional<TerminalConversation> conversation, Instant now);

    /** lease を返す。合わなければ false。 */
    boolean release(TerminalLease lease, Instant now);

    /**
     * 端末を消す (HTTP session の破棄)。task が動いている端末は消さずに false を返し、端末は自分の期限で消える。
     */
    boolean remove(String terminalId, Instant now);

    /** 期限の過ぎた、lease の無い端末を消す。 */
    int purgeExpired(Instant now);

    /** 1 つの JVM の中の登録。 */
    static CicsTerminalRegistryPort inMemory() {
        return new InMemoryCicsTerminalRegistry();
    }

    /** {@code W} と 36 進 3 桁の端末の名前を乱数で選ぶ。 */
    static String randomTerminalId() {
        String digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int number = ThreadLocalRandom.current().nextInt(36 * 36 * 36);
        return "W" + digits.charAt(number / 1296) + digits.charAt(number / 36 % 36) + digits.charAt(number % 36);
    }

    /** lease の期限。ミリ秒に揃える。 */
    static Instant leaseUntil(Instant now, Duration duration) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("lease duration must be positive");
        }
        return Instant.ofEpochMilli(now.plus(duration).toEpochMilli());
    }
}
