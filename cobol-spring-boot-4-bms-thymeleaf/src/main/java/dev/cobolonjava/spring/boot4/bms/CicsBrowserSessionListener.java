package dev.cobolonjava.spring.boot4.bms;

import dev.cobolonjava.cics.CicsTerminalRegistryPort;
import dev.cobolonjava.cics.ConversationStorePort;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * HTTP session が消えたとき (logout の invalidate、失効)、その session の端末と、端末が指していた会話を捨てる
 * (設計 77 §4.6、設計 81 §5、設計 83 §4、暫定判断 P-143・P-144)。
 *
 * <p>Spring Boot は HttpSessionListener の bean を servlet container に登録する。Spring Session を使う場合は、session の
 * 削除・失効の event を出す実装 (Redis 等) なら同じ listener に届く。Spring Session JDBC は event を出さないので届かず、
 * 端末と会話は自分の期限で消える。task が動いている端末と会話は消さない。
 */
public final class CicsBrowserSessionListener implements HttpSessionListener {

    private static final Log LOG = LogFactory.getLog(CicsBrowserSessionListener.class);

    private final ConversationStorePort conversations;
    private final CicsTerminalRegistryPort terminals;
    private final Clock clock;

    public CicsBrowserSessionListener(ConversationStorePort conversations, CicsTerminalRegistryPort terminals,
                                      Clock clock) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.terminals = Objects.requireNonNull(terminals, "terminals");
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
            value = session.getAttribute(CicsBrowserController.TERMINAL);
        } catch (IllegalStateException alreadyInvalidated) {
            return;
        }
        if (!(value instanceof String terminalId)) {
            return;
        }
        try {
            Instant now = clock.instant();
            terminals.find(terminalId, now).flatMap(CicsTerminalRegistryPort.Terminal::conversation)
                    .ifPresent(conversation -> conversations.discard(conversation.id(), now));
            terminals.remove(terminalId, now);
        } catch (RuntimeException failure) {
            // session の破棄は container の処理なので止めない。端末と会話は自分の期限で消える。名前は記録に出さない
            LOG.warn("failed to discard the CICS terminal of a destroyed HTTP session: "
                    + failure.getClass().getName());
        }
    }
}
