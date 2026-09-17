package dev.cobolonjava.ims.jms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.rabbitmq.jms.admin.RMQConnectionFactory;
import dev.cobolonjava.ims.dli.InputMessage;
import dev.cobolonjava.ims.dli.OutputMessage;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.codepage.CodePages;
import jakarta.jms.BytesMessage;
import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Queue;
import jakarta.jms.Session;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 実ブローカ (RabbitMQ) を使う試験 (ADR-0014、暫定判断 P-162)。
 *
 * <p>{@code infra/rabbitmq} の compose で起動し、{@code RABBITMQ_IT_ENABLED=true} を設定したときだけ流れる。
 * Db2 の実機試験と同じ構えである。設定が無ければスキップし、失敗にはしない。
 */
@Tag("V1")
@EnabledIfEnvironmentVariable(named = "RABBITMQ_IT_ENABLED", matches = "true")
class JmsMessageQueueIntegrationTest {

    private static final CodePage EBCDIC = CodePages.DEFAULT;

    private final String transactionCode = "IMSIT." + UUID.randomUUID();
    private final String replyPrefix = "IMSIT.LTERM." + UUID.randomUUID() + ".";

    private static ConnectionFactory factory() {
        RMQConnectionFactory factory = new RMQConnectionFactory();
        factory.setHost(System.getenv().getOrDefault("RABBITMQ_HOST", "localhost"));
        factory.setPort(Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672")));
        factory.setUsername(System.getenv().getOrDefault("RABBITMQ_USER", "cobol"));
        factory.setPassword(System.getenv().getOrDefault("RABBITMQ_PASSWORD", "change-me"));
        return factory;
    }

    /** 端末から電文を入れる側。 */
    private void offer(String terminal, String... segments) throws JMSException {
        try (Connection connection = factory().createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(transactionCode);
            BytesMessage message = session.createBytesMessage();
            message.writeBytes(MessageSegments.encode(List.of(segments).stream().map(EBCDIC::encode).toList()));
            message.setStringProperty("IMS_LTERM", terminal);
            try (MessageProducer producer = session.createProducer(queue)) {
                producer.send(message);
            }
        }
    }

    /** 応答を受け取る側。 */
    private String reply(String terminal) throws JMSException {
        try (Connection connection = factory().createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            connection.start();
            Queue queue = session.createQueue(replyPrefix + terminal);
            try (MessageConsumer consumer = session.createConsumer(queue)) {
                BytesMessage message = (BytesMessage) consumer.receive(5_000);
                if (message == null) {
                    return null;
                }
                byte[] body = new byte[(int) message.getBodyLength()];
                message.readBytes(body);
                return EBCDIC.decode(MessageSegments.decode(body).get(0));
            }
        }
    }

    @Test
    @DisplayName("電文を取り出して応答を送り、同期点で確定する")
    void aMessageIsTakenAndAnsweredAtTheSyncPoint() throws JMSException {
        offer("LTERM001", "IBLOGIN1000000001", "PASSWORD");

        try (JmsMessageQueue queue = new JmsMessageQueue(factory(), transactionCode, replyPrefix,
                Duration.ofSeconds(5))) {
            InputMessage message = queue.next();
            assertNotNull(message);
            assertEquals("LTERM001", message.logicalTerminal());
            assertEquals(List.of("IBLOGIN1000000001", "PASSWORD"),
                    message.segments().stream().map(EBCDIC::decode).toList());

            queue.send(new OutputMessage("LTERM001", List.of(EBCDIC.encode("LOGIN SUCCESSFUL"))));
            // 同期点の前に応答は出ない
            assertNull(reply("LTERM001"));
            queue.commit();

            assertEquals("LOGIN SUCCESSFUL", reply("LTERM001"));
            assertNull(queue.next());
        }
    }

    @Test
    @DisplayName("巻き戻せば電文はブローカへ戻り、送っていない応答は捨てられる")
    void aRollbackReturnsTheMessageAndDropsTheReply() throws JMSException {
        offer("LTERM002", "IBLOGIN1000000002");

        try (JmsMessageQueue queue = new JmsMessageQueue(factory(), transactionCode, replyPrefix,
                Duration.ofSeconds(5))) {
            assertNotNull(queue.next());
            queue.send(new OutputMessage("LTERM002", List.of(EBCDIC.encode("NEVER"))));
            queue.rollback();

            assertNull(reply("LTERM002"));
        }
        try (JmsMessageQueue queue = new JmsMessageQueue(factory(), transactionCode, replyPrefix,
                Duration.ofSeconds(5))) {
            // 戻した電文は再配信される (at-least-once、ADR-0014)
            InputMessage redelivered = queue.next();
            assertNotNull(redelivered);
            assertEquals("IBLOGIN1000000002", EBCDIC.decode(redelivered.segments().get(0)));
            queue.commit();
        }
    }
}
