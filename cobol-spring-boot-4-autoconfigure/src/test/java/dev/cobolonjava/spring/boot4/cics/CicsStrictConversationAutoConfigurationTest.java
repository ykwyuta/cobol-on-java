package dev.cobolonjava.spring.boot4.cics;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.cobolonjava.cics.CicsOutcomeStorePort;
import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskBoundaryFactory;
import dev.cobolonjava.cics.CicsTaskProgramPort;
import dev.cobolonjava.cics.CicsTransactionRegistry;
import dev.cobolonjava.cics.ConversationStorePort;
import dev.cobolonjava.cics.NonRecoverableTaskBoundaryFactory;
import dev.cobolonjava.cics.TaskCompletion;
import dev.cobolonjava.db2.jdbc.Db2NativeConnectionProvider;
import dev.cobolonjava.db2.jdbc.DriverManagerDb2NativeConnectionProvider;
import dev.cobolonjava.spring.boot4.autoconfigure.CobolDb2SpringAutoConfiguration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** STRICT の会話ストアの自動構成 (暫定判断 P-143)。 */
@Tag("V1")
class CicsStrictConversationAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class, CobolDb2SpringAutoConfiguration.class,
                    CicsStrictConversationAutoConfiguration.class, CicsTaskAutoConfiguration.class))
            .withPropertyValues("spring.datasource.url=jdbc:h2:mem:strict-auto;DB_CLOSE_DELAY=-1")
            .withBean(CicsTransactionRegistry.class, () -> new CicsTransactionRegistry(List.of()))
            .withBean(CicsTaskProgramPort.class, () -> (definition, input, task, syncpoints) ->
                    new TaskCompletion(Optional.empty(), CicsPayload.empty()));

    @Test
    @DisplayName("consistency=strictでは会話ストア・結果の置き場・task境界をJDBCのSTRICTに替える")
    void configuresStrictConsistency() {
        runner.withPropertyValues("cobol.cics.conversation.consistency=strict").run(context -> {
            JdbcConversationStore store = context.getBean(JdbcConversationStore.class);
            assertSame(store, context.getBean(ConversationStorePort.class));
            assertSame(store, context.getBean(CicsOutcomeStorePort.class));
            assertInstanceOf(SpringStrictTaskBoundaryFactory.class, context.getBean(CicsTaskBoundaryFactory.class));
        });
    }

    @Test
    @DisplayName("DB2_DRIVER_MANAGED_HOLDではnative leaseの上のSTRICT境界にし、providerが無ければ起動しない")
    void configuresDriverManagedStrictConsistency() {
        ApplicationContextRunner nativeProfile = runner.withPropertyValues(
                "cobol.cics.conversation.consistency=strict", "cobol.db2.profile=DB2_DRIVER_MANAGED_HOLD");
        nativeProfile.withBean(Db2NativeConnectionProvider.class, () -> new DriverManagerDb2NativeConnectionProvider(
                        "jdbc:h2:mem:strict-auto;DB_CLOSE_DELAY=-1", new Properties()))
                .run(context -> assertInstanceOf(DriverManagedStrictTaskBoundaryFactory.class,
                        context.getBean(CicsTaskBoundaryFactory.class)));
        // 1 つの JVM の中の既定の境界へ黙って戻さない
        nativeProfile.run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    @DisplayName("指定しなければ1つのJVMの中の既定のまま")
    void keepsInMemoryDefaults() {
        runner.run(context -> assertInstanceOf(NonRecoverableTaskBoundaryFactory.class,
                context.getBean(CicsTaskBoundaryFactory.class)));
    }
}
