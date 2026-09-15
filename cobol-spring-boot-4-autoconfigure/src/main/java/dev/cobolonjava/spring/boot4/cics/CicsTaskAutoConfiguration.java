package dev.cobolonjava.spring.boot4.cics;

import dev.cobolonjava.cics.CicsAsyncPort;
import dev.cobolonjava.cics.CicsOutcomeStorePort;
import dev.cobolonjava.cics.CicsStartPort;
import dev.cobolonjava.cics.CicsTaskBoundaryFactory;
import dev.cobolonjava.cics.CicsTaskCoordinator;
import dev.cobolonjava.cics.CicsTaskPolicy;
import dev.cobolonjava.cics.CicsTaskProgramPort;
import dev.cobolonjava.cics.CicsTerminalRegistryPort;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CicsTransactionRegistry;
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.ConversationIdFactory;
import dev.cobolonjava.cics.ConversationStorePort;
import dev.cobolonjava.cics.InMemoryConversationStore;
import dev.cobolonjava.cics.NonRecoverableTaskBoundaryFactory;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * CICS task の coordinator と、その既定の部品を構成する (設計 77 §3.1、暫定判断 P-134)。
 *
 * <p>ブラウザの入口 (BMS Thymeleaf adapter) と JSON の入口の両方がこの coordinator を使う。
 * 利用者が transaction の登録と program の実行を bean にしたときだけ動く。会話ストアの既定は
 * 1 つの JVM の中だけで効き、task の境界の既定は回復可能な資源を持たない。どちらも bean で替えられる。
 */
@AutoConfiguration
@ConditionalOnBean({CicsTransactionRegistry.class, CicsTaskProgramPort.class})
public class CicsTaskAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ConversationStorePort cobolConversationStore() {
        return new InMemoryConversationStore();
    }

    /** 端末の登録 (設計 83 §4)。既定は 1 つの JVM の中。STRICT の構成は表に置く登録へ替える。 */
    @Bean
    @ConditionalOnMissingBean
    CicsTerminalRegistryPort cobolTerminalRegistry() {
        return CicsTerminalRegistryPort.inMemory();
    }

    @Bean
    @ConditionalOnMissingBean
    CicsTaskBoundaryFactory cobolTaskBoundaryFactory(ConversationStorePort conversations,
                                                     CicsOutcomeStorePort outcomes) {
        return new NonRecoverableTaskBoundaryFactory(conversations, outcomes);
    }

    /**
     * 冪等キーの結果の置き場 (暫定判断 P-142)。既定は 1 つの JVM の中。task 境界を替えるときは、境界が
     * {@code commit(TaskCommit, Instant)} で結果を業務の UOW と一緒に確定できなければならない。
     */
    @Bean
    @ConditionalOnMissingBean
    CicsOutcomeStorePort cobolOutcomeStore() {
        return CicsOutcomeStorePort.inMemory();
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

    /**
     * START / CANCEL の間隔制御 (暫定判断 P-138)。1 つの JVM の中で満了を待ち、この coordinator で task を起こす。
     *
     * <p>coordinator は program の実行 (その中の region の構成) から作られるので、task を起こすときに取り出す。
     * region の構成 ({@code CicsEnvironment.withStarts}) へ入れるのは利用者である。
     */
    @Bean
    @ConditionalOnMissingBean
    CicsStartPort cobolStartPort(CicsTransactionRegistry transactions,
                                 ObjectProvider<CicsTaskCoordinator> coordinator, Clock cobolCicsClock) {
        return CicsStartPort.inMemory(cobolCicsClock, transId -> {
            CicsTransactionDefinition definition = transactions.definitions().get(transId);
            return definition != null && definition.enabled();
        }, CicsStartPort.launching(coordinator::getObject));
    }

    /**
     * 非同期 API の子の task (暫定判断 P-140)。1 つの JVM の中で、この coordinator で子を起こす。
     * region の構成 ({@code CicsEnvironment.withAsync}) へ入れるのは利用者である。
     */
    @Bean
    @ConditionalOnMissingBean
    CicsAsyncPort cobolAsyncPort(CicsTransactionRegistry transactions,
                                 ObjectProvider<CicsTaskCoordinator> coordinator) {
        return CicsAsyncPort.inMemory(transactions, CicsAsyncPort.launching(coordinator::getObject));
    }

    @Bean
    @ConditionalOnMissingBean
    CicsTaskCoordinator cobolTaskCoordinator(CicsTransactionRegistry transactions,
                                             ConversationStorePort conversations,
                                             CicsTaskBoundaryFactory boundaries, CicsTaskProgramPort programs,
                                             CicsTaskPolicy policy, ConversationIdFactory conversationIds,
                                             Clock cobolCicsClock, CicsOutcomeStorePort outcomes,
                                             dev.cobolonjava.cics.CicsSecurityPort security) {
        return new CicsTaskCoordinator(transactions, conversations, boundaries, programs, policy,
                conversationIds, cobolCicsClock, outcomes, security);
    }

    /**
     * principal と CICS の user ID の対応と transaction の権限 (設計 84)。既定は principal 名を user ID にし、どの transaction も
     * 許す。{@code cobol.cics.security.mode=demo} は利用者の一覧から決める。START の USERID / TRANSID の権限にも使うので、
     * region の構成 ({@code CicsEnvironment.withSecurity}) にも同じ bean を入れる。
     */
    @Bean
    @ConditionalOnMissingBean
    dev.cobolonjava.cics.CicsSecurityPort cobolCicsSecurity() {
        return dev.cobolonjava.cics.CicsSecurityPort.derived();
    }
}
