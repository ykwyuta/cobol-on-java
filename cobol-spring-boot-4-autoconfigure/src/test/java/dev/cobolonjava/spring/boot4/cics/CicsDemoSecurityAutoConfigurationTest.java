package dev.cobolonjava.spring.boot4.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsSecurityPort;
import dev.cobolonjava.cics.CicsTaskProgramPort;
import dev.cobolonjava.cics.CicsTransactionRegistry;
import dev.cobolonjava.cics.TaskCompletion;
import dev.cobolonjava.cics.TransId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** デモ環境の簡易認証の構成 (設計 84、暫定判断 P-145)。 */
@Tag("V1")
class CicsDemoSecurityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CicsDemoSecurityAutoConfiguration.class,
                    CicsTaskAutoConfiguration.class))
            .withBean(CicsTransactionRegistry.class, () -> new CicsTransactionRegistry(List.of()))
            .withBean(CicsTaskProgramPort.class, () -> (definition, input, task, syncpoints) ->
                    new TaskCompletion(Optional.empty(), CicsPayload.empty()));

    private static final String[] DEMO = {
        "cobol.cics.security.mode=demo",
        "cobol.cics.security.users[0].username=alice",
        "cobol.cics.security.users[0].password={noop}alice-pass",
        "cobol.cics.security.users[0].user-id=ALICE01",
        "cobol.cics.security.users[0].transactions[0]=BNK1",
        "cobol.cics.security.users[0].surrogates[0]=BATCH01",
        "cobol.cics.security.users[1].username=admin",
        "cobol.cics.security.users[1].password={noop}admin-pass",
        "cobol.cics.security.users[1].user-id=ADMIN01",
        "cobol.cics.security.users[1].transactions[0]=*",
        "cobol.cics.security.users[2].user-id=BATCH01",
        "cobol.cics.security.users[2].transactions[0]=BTCH"
    };

    @Test
    @DisplayName("mode=demoは利用者の一覧でprincipalをuser IDにし、transactionと代理を確かめ、ログイン名とパスワードの利用者を作る")
    void configuresDemoSecurity() {
        runner.withPropertyValues(DEMO).run(context -> {
            CicsSecurityPort security = context.getBean(CicsSecurityPort.class);
            assertInstanceOf(DemoCicsSecurity.class, security);
            assertEquals(Optional.of("ALICE01"), security.userIdOf("alice"));
            assertEquals(Optional.empty(), security.userIdOf("mallory"));
            assertTrue(security.mayAttach(Optional.of("ALICE01"), TransId.of("BNK1")));
            assertFalse(security.mayAttach(Optional.of("ALICE01"), TransId.of("BTCH")));
            assertTrue(security.mayAttach(Optional.of("ADMIN01"), TransId.of("BTCH")));
            assertTrue(security.mayAttach(Optional.of("BATCH01"), TransId.of("BTCH")));
            assertFalse(security.mayAttach(Optional.empty(), TransId.of("BNK1")));
            assertTrue(security.maySurrogate(Optional.of("ALICE01"), "BATCH01"));
            assertFalse(security.maySurrogate(Optional.of("ADMIN01"), "BATCH01"));

            UserDetailsService users = context.getBean(UserDetailsService.class);
            assertEquals("{noop}alice-pass", users.loadUserByUsername("alice").getPassword());
            // ログイン名を持たない user ID はログインできない
            assertThrows(UsernameNotFoundException.class, () -> users.loadUserByUsername("BATCH01"));
            assertNotNull(context.getBean(dev.cobolonjava.cics.CicsTaskCoordinator.class));
        });
    }

    @Test
    @DisplayName("user IDの形、user IDの重複、encoderの接頭の無いパスワードは起動の時点で断る")
    void rejectsInvalidDemoConfiguration() {
        runner.withPropertyValues("cobol.cics.security.mode=demo",
                        "cobol.cics.security.users[0].username=alice",
                        "cobol.cics.security.users[0].password={noop}alice-pass",
                        "cobol.cics.security.users[0].user-id=alice01")
                .run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("cobol.cics.security.mode=demo",
                        "cobol.cics.security.users[0].user-id=ALICE01",
                        "cobol.cics.security.users[1].user-id=ALICE01")
                .run(context -> assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("cobol.cics.security.mode=demo",
                        "cobol.cics.security.users[0].username=alice",
                        "cobol.cics.security.users[0].password=alice-pass",
                        "cobol.cics.security.users[0].user-id=ALICE01")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    // 断るときもパスワードを文面に出さない
                    assertFalse(String.valueOf(context.getStartupFailure()).contains("alice-pass"));
                });
    }

    @Test
    @DisplayName("modeを書かなければprincipal名をuser IDにする既定のまま")
    void keepsDerivedSecurityByDefault() {
        runner.run(context -> {
            CicsSecurityPort security = context.getBean(CicsSecurityPort.class);
            assertFalse(security instanceof DemoCicsSecurity);
            assertEquals(Optional.of("ALICE"), security.userIdOf("alice"));
        });
    }
}
