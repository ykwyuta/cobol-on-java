package dev.cobolonjava.ims.jms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** JMS のキューの差し込みと、接続の組み立て (暫定判断 P-165)。 */
@Tag("V1")
class JmsMessageQueueProviderTest {

    /** ブローカの製品に依存しないまま setter で値を流し込めるかを見るための作り物。 */
    public static final class FakeFactory implements ConnectionFactory {

        private String host;
        private int port;
        private boolean ssl;

        public void setHost(String value) {
            host = value;
        }

        public void setPort(int value) {
            port = value;
        }

        public void setSsl(boolean value) {
            ssl = value;
        }

        @Override
        public jakarta.jms.Connection createConnection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.jms.Connection createConnection(String user, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JMSContext createContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public JMSContext createContext(String user, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JMSContext createContext(String user, String password, int sessionMode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JMSContext createContext(int sessionMode) {
            throw new UnsupportedOperationException();
        }
    }

    private static final List<String> PROPERTIES = List.of(
            JmsMessageQueueProvider.FACTORY,
            JmsMessageQueueProvider.FACTORY + ".host",
            JmsMessageQueueProvider.FACTORY + ".port",
            JmsMessageQueueProvider.FACTORY + ".ssl",
            JmsMessageQueueProvider.FACTORY + ".nosuch");

    @AfterEach
    void clearProperties() {
        PROPERTIES.forEach(System::clearProperty);
    }

    @Test
    @DisplayName("cobol.ims.jms.factory が無ければ差し込まない")
    void withoutTheFactoryPropertyNothingIsPlugged() {
        assertNull(new JmsMessageQueueProvider().open("IBLOGIN1"));
    }

    @Test
    @DisplayName("クラス名から接続を組み立て、cobol.ims.jms.factory.<欄> を setter で流し込む")
    void theFactoryIsBuiltFromItsClassNameAndProperties() {
        System.setProperty(JmsMessageQueueProvider.FACTORY + ".host", "broker.example");
        System.setProperty(JmsMessageQueueProvider.FACTORY + ".port", "5672");
        System.setProperty(JmsMessageQueueProvider.FACTORY + ".ssl", "true");

        ConnectionFactory built = JmsMessageQueueProvider.factory(FakeFactory.class.getName());

        FakeFactory fake = (FakeFactory) built;
        assertEquals("broker.example", fake.host);
        // int と boolean の欄も、書かれた文字から変換する
        assertEquals(5672, fake.port);
        assertTrue(fake.ssl);
    }

    @Test
    @DisplayName("無いクラス、ConnectionFactory でないクラス、無い欄は断る")
    void anUnusableFactoryIsRefused() {
        assertThrows(JmsQueueException.class, () -> JmsMessageQueueProvider.factory("no.such.Factory"));
        assertThrows(JmsQueueException.class, () -> JmsMessageQueueProvider.factory(String.class.getName()));

        System.setProperty(JmsMessageQueueProvider.FACTORY + ".nosuch", "x");
        assertThrows(JmsQueueException.class, () -> JmsMessageQueueProvider.factory(FakeFactory.class.getName()));
    }
}
