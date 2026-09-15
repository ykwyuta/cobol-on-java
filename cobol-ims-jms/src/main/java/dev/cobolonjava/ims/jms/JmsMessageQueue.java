package dev.cobolonjava.ims.jms;

import dev.cobolonjava.ims.dli.InputMessage;
import dev.cobolonjava.ims.dli.MessageQueue;
import dev.cobolonjava.ims.dli.OutputMessage;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * IMS TM の電文のキューを JMS 3.0 で運ぶ (設計 78 §4.1、ADR-0014、暫定判断 P-162)。
 *
 * <p>取引コードごとに 1 つのキューを読む。セグメントは {@link MessageSegments} の形 (LL / ZZ 付き) で
 * {@link BytesMessage} に入れる。応答は宛先の論理端末ごとのキュー ({@code <接頭辞><端末名>}) へ送る。
 *
 * <h2>確定</h2>
 * <p>セッションは transacted である。領域の同期点で {@link #commit} が呼ばれ、そこまでに取り出した電文の ACK と、
 * 送った応答の送出が 1 つの JMS のトランザクションで確定する。データベースの置き場を確定したあとに呼ばれるので、
 * 置き場の確定が失敗すれば電文は ACK されず、ブローカへ戻って再配信される (ADR-0014 の at-least-once)。
 *
 * <h2>まだ持たないもの</h2>
 * <p>処理済みの電文を見分ける inbox (冪等化) は無い。再配信されれば業務は 2 度動く (P-104)。XA も無い。
 * 消費者は 1 つで、取引コードの中の順を保つ (P-105)。CHNG による宛先の差し替えと代替 PCB、SPA は無い。
 */
public final class JmsMessageQueue implements MessageQueue, AutoCloseable {

    /** 応答の宛先のキューの名前の既定の接頭辞。 */
    public static final String DEFAULT_REPLY_PREFIX = "IMS.LTERM.";

    private final Connection connection;
    private final Session session;
    private final MessageConsumer consumer;
    private final MessageProducer producer;
    private final String replyPrefix;
    private final long timeoutMillis;
    /** 応答の宛先の論理端末。JMS の宛先は電文ごとに決まる。 */
    private final List<OutputMessage> pending = new ArrayList<>();

    /**
     * @param factory         ブローカへの接続。呼ぶ側が構成する (RabbitMQ なら {@code RMQConnectionFactory})
     * @param transactionCode 読むキューの名前。取引コード 1 つに 1 つのキューである
     * @param replyPrefix     応答のキューの名前の接頭辞
     * @param timeout         電文を待つ長さ。尽きれば I/O PCB の GU は QC になる
     */
    public JmsMessageQueue(ConnectionFactory factory, String transactionCode, String replyPrefix, Duration timeout) {
        Objects.requireNonNull(factory, "factory");
        this.replyPrefix = Objects.requireNonNull(replyPrefix, "replyPrefix");
        this.timeoutMillis = Math.max(0, timeout.toMillis());
        try {
            connection = factory.createConnection();
            // 取り出しと応答を 1 つの同期点で確定するので transacted である
            session = connection.createSession(true, Session.SESSION_TRANSACTED);
            Queue queue = session.createQueue(transactionCode);
            consumer = session.createConsumer(queue);
            producer = session.createProducer(null);
            connection.start();
        } catch (JMSException e) {
            throw new JmsQueueException("cannot open the queue of transaction code " + transactionCode, e);
        }
    }

    @Override
    public InputMessage next() {
        try {
            Message message = timeoutMillis == 0 ? consumer.receiveNoWait() : consumer.receive(timeoutMillis);
            if (message == null) {
                return null;
            }
            if (!(message instanceof BytesMessage bytes)) {
                throw new JmsQueueException("an IMS message must be a BytesMessage, but a "
                        + message.getClass().getName() + " arrived", null);
            }
            byte[] body = new byte[(int) bytes.getBodyLength()];
            bytes.readBytes(body);
            String terminal = message.getStringProperty("IMS_LTERM");
            return new InputMessage(terminal == null ? "" : terminal, MessageSegments.decode(body));
        } catch (JMSException e) {
            throw new JmsQueueException("cannot read the next message", e);
        }
    }

    @Override
    public void send(OutputMessage message) {
        // 送るのは同期点である。ここでは積むだけにして、commit で 1 つのトランザクションにまとめる
        pending.add(message);
    }

    @Override
    public void commit() {
        try {
            for (OutputMessage message : pending) {
                BytesMessage bytes = session.createBytesMessage();
                bytes.writeBytes(MessageSegments.encode(message.segments()));
                bytes.setStringProperty("IMS_LTERM", message.destination());
                producer.send(session.createQueue(replyPrefix + message.destination()), bytes);
            }
            pending.clear();
            session.commit();
        } catch (JMSException e) {
            throw new JmsQueueException("cannot commit the message queue", e);
        }
    }

    @Override
    public void rollback() {
        pending.clear();
        try {
            session.rollback();
        } catch (JMSException e) {
            throw new JmsQueueException("cannot roll back the message queue", e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (JMSException e) {
            throw new JmsQueueException("cannot close the message queue", e);
        }
    }
}
