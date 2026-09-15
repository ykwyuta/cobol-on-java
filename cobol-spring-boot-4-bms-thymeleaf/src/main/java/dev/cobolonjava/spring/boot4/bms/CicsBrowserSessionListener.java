package dev.cobolonjava.spring.boot4.bms;

import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.ConversationStorePort;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.time.Clock;
import java.util.Objects;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * HTTP session が消えたとき (logout の invalidate、失効)、その session が指していた会話を会話ストアから捨てる
 * (設計 77 §4.6、設計 81 §5、暫定判断 P-143)。
 *
 * <p>Spring Boot は HttpSessionListener の bean を servlet container に登録する。Spring Session を使う場合は、session の
 * 削除・失効の event を出す実装 (Redis 等) なら同じ listener に届く。Spring Session JDBC は event を出さないので届かず、
 * 会話は自分の期限で消える。
 */
public final class CicsBrowserSessionListener implements HttpSessionListener {

    private static final Log LOG = LogFactory.getLog(CicsBrowserSessionListener.class);

    private final ConversationStorePort conversations;
    private final Clock clock;

    public CicsBrowserSessionListener(ConversationStorePort conversations, Clock clock) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        HttpSession session = event.getSession();
        if (session == null) {
            return;
        }
        Object value;
        try {
            value = session.getAttribute(CicsBrowserController.CONVERSATION);
        } catch (IllegalStateException alreadyInvalidated) {
            return;
        }
        if (!(value instanceof CicsBrowserController.BrowserConversation conversation)) {
            return;
        }
        try {
            conversations.discard(new ConversationId(conversation.id()), clock.instant());
        } catch (RuntimeException failure) {
            // session の破棄は container の処理なので止めない。会話は自分の期限で消える。会話の ID は記録に出さない
            LOG.warn("failed to discard the CICS conversation of a destroyed HTTP session: "
                    + failure.getClass().getName());
        }
    }
}
