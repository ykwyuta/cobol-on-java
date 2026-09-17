package dev.cobolonjava.spring.boot4.autoconfigure;

import dev.cobolonjava.ims.rdb.JdbcDatabaseStoreProvider;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot 4.x の {@link DataSource} から IMS のデータベースの置き場を構成する (暫定判断 P-169、P-160)。
 *
 * <p>{@code cobol-ims-rdb} を classpath に置いた利用者だけが効く。切るには
 * {@code cobol.ims.spring-data-source=false} を書く。
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass({DataSource.class, JdbcDatabaseStoreProvider.class})
@ConditionalOnSingleCandidate(DataSource.class)
@ConditionalOnProperty(prefix = "cobol.ims", name = "spring-data-source", matchIfMissing = true)
public class CobolImsSpringAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ImsDataSourceRegistrar.class)
    ImsDataSourceRegistrar cobolImsDataSourceRegistrar(DataSource dataSource) {
        return new ImsDataSourceRegistrar(dataSource);
    }
}
