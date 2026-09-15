package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsTaskBoundaryFactory;
import dev.cobolonjava.db2.SqlExecutorPort;
import dev.cobolonjava.db2.UnitOfWorkPort;
import dev.cobolonjava.db2.jdbc.Db2NativeConnectionProvider;
import dev.cobolonjava.spring.boot4.autoconfigure.CobolDb2SpringAutoConfiguration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code cobol.cics.conversation.consistency=strict} のとき、会話と冪等キーの結果を業務の Db2 と同じ database の表に置き、
 * 同じ UOW で確定する task 境界を構成する (設計 77 §4.6、暫定判断 P-143)。
 *
 * <p>{@code cobol.db2.profile} が SPRING_MANAGED (既定) なら {@link SpringStrictTaskBoundaryFactory} を、
 * DB2_DRIVER_MANAGED_HOLD なら利用者の {@link Db2NativeConnectionProvider} の bean で
 * {@link DriverManagedStrictTaskBoundaryFactory} を構成する。provider が無ければ境界を作らず、起動を止める
 * (1 つの JVM の中の既定へ黙って戻さない)。
 *
 * <p>CICS task の自動構成より先に動くので、会話ストア・結果の置き場・task 境界の既定 (1 つの JVM の中) は後退する。
 * HTTP session を複数の JVM で分け合うには、利用者が Spring Session JDBC を構成する。表は
 * {@link JdbcConversationStore#SCHEMA} の DDL で利用者が作る。
 */
@AutoConfiguration(before = CicsTaskAutoConfiguration.class, after = CobolDb2SpringAutoConfiguration.class,
        afterName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"
        })
@ConditionalOnProperty(prefix = "cobol.cics.conversation", name = "consistency", havingValue = "strict")
@ConditionalOnSingleCandidate(DataSource.class)
// 入れ子を @Configuration にすると、同じ package を component scan する利用者の application に拾われ、
// strict を指定していなくても境界が作られる。@Import なら外側の条件が通ったときだけ読む
@Import({CicsStrictConversationAutoConfiguration.SpringManagedStrict.class,
        CicsStrictConversationAutoConfiguration.DriverManagedStrict.class})
public class CicsStrictConversationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JdbcConversationStore cobolJdbcConversationStore(DataSource dataSource,
                                                     PlatformTransactionManager transactionManager) {
        return new JdbcConversationStore(dataSource, transactionManager);
    }

    /**
     * START も同じ DataSource の表に置き、各 JVM の dispatcher が満了したものを 1 度だけ起こす (設計 83 §8)。
     * region の構成 ({@code CicsEnvironment.withStarts}) へ入れるのは利用者である。TD のキューは定義を利用者が持つので、
     * {@link JdbcCicsTransientData} を利用者が作る。
     */
    @Bean
    @ConditionalOnMissingBean(dev.cobolonjava.cics.CicsStartPort.class)
    @ConditionalOnBean(dev.cobolonjava.cics.CicsTransactionRegistry.class)
    JdbcCicsStarts cobolJdbcStartPort(DataSource dataSource, PlatformTransactionManager transactionManager,
                                      dev.cobolonjava.cics.CicsTransactionRegistry transactions,
                                      dev.cobolonjava.cics.CicsTerminalRegistryPort terminals,
                                      ObjectProvider<dev.cobolonjava.cics.CicsTaskPolicy> policy,
                                      ObjectProvider<dev.cobolonjava.cics.CicsTaskCoordinator> coordinator,
                                      @Qualifier("cobolCicsClock") ObjectProvider<java.time.Clock> clock) {
        // 端末へ出す task の端末の lease は、ブラウザの入口と同じく会話の lease の長さにする
        java.time.Duration terminalLease = policy.getIfAvailable(() -> new dev.cobolonjava.cics.CicsTaskPolicy(
                java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(5))).leaseDuration();
        return new JdbcCicsStarts(dataSource, transactionManager, clock.getIfAvailable(java.time.Clock::systemUTC),
                transId -> {
                    dev.cobolonjava.cics.CicsTransactionDefinition definition = transactions.definitions().get(transId);
                    return definition != null && definition.enabled();
                },
                terminals, terminalLease, dev.cobolonjava.cics.CicsStartPort.conversing(coordinator::getObject),
                java.time.Duration.ofSeconds(1));
    }

    /** 端末の登録も同じ DataSource の表に置き、複数の JVM から端末の lease と会話の参照を見る (設計 83 §4)。 */
    @Bean
    @ConditionalOnMissingBean(dev.cobolonjava.cics.CicsTerminalRegistryPort.class)
    JdbcTerminalRegistry cobolJdbcTerminalRegistry(DataSource dataSource,
                                                   PlatformTransactionManager transactionManager) {
        return new JdbcTerminalRegistry(dataSource, transactionManager);
    }

    @ConditionalOnProperty(prefix = "cobol.db2", name = "profile", havingValue = "SPRING_MANAGED",
            matchIfMissing = true)
    static class SpringManagedStrict {

        @Bean
        @ConditionalOnMissingBean(CicsTaskBoundaryFactory.class)
        SpringStrictTaskBoundaryFactory cobolStrictTaskBoundaryFactory(ObjectProvider<UnitOfWorkPort> unitsOfWork,
                                                                       SqlExecutorPort sqlExecutor,
                                                                       JdbcConversationStore store) {
            return new SpringStrictTaskBoundaryFactory(unitsOfWork::getObject, sqlExecutor, store);
        }
    }

    @ConditionalOnProperty(prefix = "cobol.db2", name = "profile", havingValue = "DB2_DRIVER_MANAGED_HOLD")
    @ConditionalOnClass(name = "dev.cobolonjava.db2.jdbc.DriverManagedUnitOfWorkPort")
    static class DriverManagedStrict {

        @Bean
        @ConditionalOnMissingBean(CicsTaskBoundaryFactory.class)
        DriverManagedStrictTaskBoundaryFactory cobolDriverManagedStrictTaskBoundaryFactory(
                Db2NativeConnectionProvider connections, JdbcConversationStore store) {
            return new DriverManagedStrictTaskBoundaryFactory(connections, store);
        }
    }
}
