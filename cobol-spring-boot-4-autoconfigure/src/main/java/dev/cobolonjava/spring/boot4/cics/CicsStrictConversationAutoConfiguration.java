package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsTaskBoundaryFactory;
import dev.cobolonjava.db2.SqlExecutorPort;
import dev.cobolonjava.db2.UnitOfWorkPort;
import dev.cobolonjava.spring.boot4.autoconfigure.CobolDb2SpringAutoConfiguration;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code cobol.cics.conversation.consistency=strict} のとき、会話と冪等キーの結果を業務の Db2 と同じ DataSource の表に置き、
 * 同じ UOW で確定する task 境界を構成する (設計 77 §4.6、暫定判断 P-143)。
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
public class CicsStrictConversationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JdbcConversationStore cobolJdbcConversationStore(DataSource dataSource,
                                                     PlatformTransactionManager transactionManager) {
        return new JdbcConversationStore(dataSource, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean(CicsTaskBoundaryFactory.class)
    SpringStrictTaskBoundaryFactory cobolStrictTaskBoundaryFactory(ObjectProvider<UnitOfWorkPort> unitsOfWork,
                                                                   SqlExecutorPort sqlExecutor,
                                                                   JdbcConversationStore store) {
        return new SpringStrictTaskBoundaryFactory(unitsOfWork::getObject, sqlExecutor, store);
    }
}
