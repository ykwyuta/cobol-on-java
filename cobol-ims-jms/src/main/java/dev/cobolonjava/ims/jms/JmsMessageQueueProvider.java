package dev.cobolonjava.ims.jms;

import dev.cobolonjava.ims.dli.MessageQueue;
import dev.cobolonjava.ims.dli.MessageQueueProvider;
import jakarta.jms.ConnectionFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * システムプロパティ {@code cobol.ims.jms.factory} を指定したときだけ、JMS のキューを差し込む (P-165)。
 *
 * <pre>
 * -Dcobol.ims.jms.factory=com.rabbitmq.jms.admin.RMQConnectionFactory
 * -Dcobol.ims.jms.factory.host=localhost -Dcobol.ims.jms.factory.port=5672
 * -Dcobol.ims.jms.factory.username=cobol -Dcobol.ims.jms.factory.password=...
 * -Dcobol.ims.jms.queue=IBLOGIN1          取引コード。省けば呼ぶ側が渡した名前
 * -Dcobol.ims.jms.reply-prefix=IMS.LTERM. 応答のキューの接頭辞
 * -Dcobol.ims.jms.timeout-seconds=5       電文を待つ長さ
 * </pre>
 *
 * <p>JDBC の URL にあたる中立の接続の書き方が JMS には無い。ブローカの製品に依存しないまま
 * ({@code jakarta.jms} だけに依存したまま、設計 78 §2.2) 接続を組み立てるため、{@code ConnectionFactory} の
 * クラス名を受け取り、{@code cobol.ims.jms.factory.<欄>} を JavaBean の setter で流し込む。
 */
public final class JmsMessageQueueProvider implements MessageQueueProvider {

    public static final String FACTORY = "cobol.ims.jms.factory";
    public static final String QUEUE = "cobol.ims.jms.queue";
    public static final String REPLY_PREFIX = "cobol.ims.jms.reply-prefix";
    public static final String TIMEOUT = "cobol.ims.jms.timeout-seconds";

    @Override
    public MessageQueue open(String transactionCode) {
        String className = System.getProperty(FACTORY);
        if (className == null || className.isBlank()) {
            return null;
        }
        String queue = System.getProperty(QUEUE, transactionCode);
        if (queue == null || queue.isBlank()) {
            throw new JmsQueueException("the transaction code of the JMS queue is not known;"
                    + " set " + QUEUE, null);
        }
        Duration timeout = Duration.ofSeconds(Long.parseLong(System.getProperty(TIMEOUT, "5")));
        return new JmsMessageQueue(factory(className), queue,
                System.getProperty(REPLY_PREFIX, JmsMessageQueue.DEFAULT_REPLY_PREFIX), timeout);
    }

    /** クラス名から {@code ConnectionFactory} を作り、{@code cobol.ims.jms.factory.<欄>} を setter で流し込む。 */
    static ConnectionFactory factory(String className) {
        Object instance;
        try {
            instance = Class.forName(className).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException
                 | IllegalAccessException | InvocationTargetException e) {
            throw new JmsQueueException("cannot create the JMS connection factory " + className, null);
        }
        if (!(instance instanceof ConnectionFactory connectionFactory)) {
            throw new JmsQueueException(className + " is not a jakarta.jms.ConnectionFactory", null);
        }
        for (Map.Entry<String, String> property : properties().entrySet()) {
            apply(connectionFactory, property.getKey(), property.getValue());
        }
        return connectionFactory;
    }

    /** {@code cobol.ims.jms.factory.} で始まるシステムプロパティを、欄の名前で並べる。 */
    private static Map<String, String> properties() {
        String prefix = FACTORY + ".";
        Map<String, String> out = new TreeMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(prefix) && name.length() > prefix.length()) {
                out.put(name.substring(prefix.length()), System.getProperty(name));
            }
        }
        return out;
    }

    /** {@code host} なら {@code setHost} を呼ぶ。引数が String でなければ、書かれた値を変換する。 */
    private static void apply(ConnectionFactory factory, String field, String value) {
        String setter = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        for (Method method : factory.getClass().getMethods()) {
            if (!method.getName().equals(setter) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> type = method.getParameterTypes()[0];
            try {
                method.invoke(factory, convert(type, value, field));
                return;
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new JmsQueueException("cannot set " + field + " on " + factory.getClass().getName(), null);
            }
        }
        throw new JmsQueueException("the JMS connection factory " + factory.getClass().getName()
                + " has no " + setter + "(..) for " + FACTORY + "." + field, null);
    }

    private static Object convert(Class<?> type, String value, String field) {
        if (type == String.class) {
            return value;
        }
        if (type == int.class || type == Integer.class) {
            return Integer.valueOf(value);
        }
        if (type == long.class || type == Long.class) {
            return Long.valueOf(value);
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.valueOf(value.toLowerCase(Locale.ROOT));
        }
        throw new JmsQueueException("the field " + field + " of the JMS connection factory takes a "
                + type.getName() + ", which cannot be written as a system property", null);
    }
}
