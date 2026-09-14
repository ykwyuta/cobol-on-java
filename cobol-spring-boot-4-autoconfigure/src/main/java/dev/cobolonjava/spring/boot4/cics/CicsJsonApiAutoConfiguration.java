package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsTaskCoordinator;
import dev.cobolonjava.cics.ConversationStorePort;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * JSON client の入口 ({@code POST /api/cics/{transid}}) を構成する (暫定判断 P-135)。
 *
 * <p>Spring Security が classpath に無ければ構成しない。認証と CSRF を持たない入口を既定で開けないためである。
 */
@AutoConfiguration(after = CicsTaskAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "org.springframework.security.web.SecurityFilterChain",
        "org.springframework.web.servlet.DispatcherServlet"})
@ConditionalOnBean({CicsTaskCoordinator.class, ConversationStorePort.class})
public class CicsJsonApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CicsJsonApiController cobolCicsJsonApiController(CicsTaskCoordinator coordinator,
                                                     ConversationStorePort conversations,
                                                     @Qualifier("cobolCicsClock") ObjectProvider<Clock> clock) {
        return new CicsJsonApiController(coordinator, conversations, clock.getIfAvailable(Clock::systemUTC));
    }
}
