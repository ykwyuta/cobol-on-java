package dev.cobolonjava.spring.boot4.autoconfigure;

import dev.cobolonjava.db2.UnitOfWorkPort;
import dev.cobolonjava.db2.SqlExecutorPort;
import dev.cobolonjava.spring.boot4.db2.SpringManagedSqlExecutor;
import dev.cobolonjava.spring.boot4.db2.SpringManagedUnitOfWorkPort;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.transaction.PlatformTransactionManager;

/** Spring Boot 4.xから通常Db2 UOWの中立portを構成する。 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration"
})
@ConditionalOnClass({DataSource.class, PlatformTransactionManager.class})
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(
        prefix = "cobol.db2", name = "profile",
        havingValue = "SPRING_MANAGED", matchIfMissing = true)
public class CobolDb2SpringAutoConfiguration {

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean(UnitOfWorkPort.class)
    @ConditionalOnSingleCandidate(PlatformTransactionManager.class)
    SpringManagedUnitOfWorkPort cobolSpringManagedUnitOfWorkPort(
            DataSource dataSource, PlatformTransactionManager transactionManager) {
        return new SpringManagedUnitOfWorkPort(dataSource, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean(SqlExecutorPort.class)
    SpringManagedSqlExecutor cobolSpringManagedSqlExecutor(DataSource dataSource) {
        return new SpringManagedSqlExecutor(dataSource);
    }
}
