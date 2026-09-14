package dev.cobolonjava.spring.boot4.bms;

import dev.cobolonjava.cics.CicsTaskBoundaryFactory;
import dev.cobolonjava.cics.CicsTaskCoordinator;
import dev.cobolonjava.cics.CicsTaskPolicy;
import dev.cobolonjava.cics.CicsTaskProgramPort;
import dev.cobolonjava.cics.CicsTransactionRegistry;
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.ConversationIdFactory;
import dev.cobolonjava.cics.ConversationStorePort;
import dev.cobolonjava.cics.InMemoryConversationStore;
import dev.cobolonjava.cics.NonRecoverableTaskBoundaryFactory;
import java.time.Clock;
import java.time.Duration;
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
 * 利用者が transaction の登録 ({@link CicsTransactionRegistry}) と program の実行 ({@link CicsTaskProgramPort}) を
 * bean にしたときだけ動く。会話ストアの既定は 1 つの JVM の中だけで効く {@link InMemoryConversationStore} で、
 * 複数の JVM で動かすなら利用者が {@link ConversationStorePort} の bean を置く。
 */
@AutoConfiguration(after = BmsThymeleafAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "org.springframework.security.web.SecurityFilterChain",
        "org.springframework.web.servlet.DispatcherServlet",
        "org.thymeleaf.TemplateEngine"})
@ConditionalOnBean({CicsTransactionRegistry.class, CicsTaskProgramPort.class})
public class CicsBrowserAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ConversationStorePort cobolConversationStore() {
        return new InMemoryConversationStore();
    }

    @Bean
    @ConditionalOnMissingBean
    CicsTaskBoundaryFactory cobolTaskBoundaryFactory(ConversationStorePort conversations) {
        return new NonRecoverableTaskBoundaryFactory(conversations);
    }

    @Bean
    @ConditionalOnMissingBean
    ConversationIdFactory cobolConversationIdFactory() {
        return ConversationId::create;
    }

    @Bean
    @ConditionalOnMissingBean
    CicsTaskPolicy cobolTaskPolicy() {
        // 会話は 30 分で期限切れ。lease は transaction の task 期限より長くなければならない
        return new CicsTaskPolicy(Duration.ofMinutes(30), Duration.ofMinutes(5));
    }

    @Bean
    @ConditionalOnMissingBean(name = "cobolCicsClock")
    Clock cobolCicsClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    CicsTaskCoordinator cobolTaskCoordinator(CicsTransactionRegistry transactions,
                                             ConversationStorePort conversations,
                                             CicsTaskBoundaryFactory boundaries, CicsTaskProgramPort programs,
                                             CicsTaskPolicy policy, ConversationIdFactory conversationIds,
                                             Clock cobolCicsClock) {
        return new CicsTaskCoordinator(transactions, conversations, boundaries, programs, policy,
                conversationIds, cobolCicsClock);
    }

    @Bean
    @ConditionalOnMissingBean
    CicsBrowserController cobolCicsBrowserController(CicsTaskCoordinator coordinator,
                                                     ConversationStorePort conversations,
                                                     BmsScreenViewFactory views, BmsTerminalInputBinder binder,
                                                     Clock cobolCicsClock) {
        return new CicsBrowserController(coordinator, conversations, views, binder, cobolCicsClock);
    }
}
