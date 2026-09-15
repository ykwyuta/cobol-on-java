package dev.cobolonjava.spring.boot4.bms;

import dev.cobolonjava.cics.CicsTaskCoordinator;
import dev.cobolonjava.cics.CicsTaskPolicy;
import dev.cobolonjava.cics.CicsTerminalRegistryPort;
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
 * ブラウザから CICS の疑似会話を動かす入口を構成する (設計 81 §5、暫定判断 P-134)。
 *
 * <p>Spring Security が無ければ構成しない。CSRF と認証を持たない入口を既定で開けないためである。
 * coordinator と会話ストアは base の自動構成 ({@code CicsTaskAutoConfiguration}) が作るものを使う。
 */
@AutoConfiguration(after = BmsThymeleafAutoConfiguration.class,
        afterName = "dev.cobolonjava.spring.boot4.cics.CicsTaskAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "org.springframework.security.web.SecurityFilterChain",
        "org.springframework.web.servlet.DispatcherServlet",
        "org.thymeleaf.TemplateEngine"})
@ConditionalOnBean({CicsTaskCoordinator.class, ConversationStorePort.class, CicsTerminalRegistryPort.class,
        CicsTaskPolicy.class})
public class CicsBrowserAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    CicsBrowserController cobolCicsBrowserController(CicsTaskCoordinator coordinator,
                                                     ConversationStorePort conversations,
                                                     CicsTerminalRegistryPort terminals, CicsTaskPolicy policy,
                                                     BmsScreenViewFactory views, BmsTerminalInputBinder binder,
                                                     @Qualifier("cobolCicsClock") ObjectProvider<Clock> clock,
                                                     CicsBrowserTerminalNames names) {
        return new CicsBrowserController(coordinator, conversations, terminals, views, binder,
                clock.getIfAvailable(Clock::systemUTC), policy, names);
    }

    /** 固定の端末名 (設計 83 §4.1)。既定はどの利用者にも与えず、session ごとに乱数の端末名を振る。 */
    @Bean
    @ConditionalOnMissingBean
    CicsBrowserTerminalNames cobolCicsBrowserTerminalNames() {
        return CicsBrowserTerminalNames.dynamic();
    }

    @Bean
    @ConditionalOnMissingBean
    CicsBrowserSessionListener cobolCicsBrowserSessionListener(ConversationStorePort conversations,
                                                               CicsTerminalRegistryPort terminals,
                                                               @Qualifier("cobolCicsClock") ObjectProvider<Clock> clock) {
        return new CicsBrowserSessionListener(conversations, terminals, clock.getIfAvailable(Clock::systemUTC));
    }
}
