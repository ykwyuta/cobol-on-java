package dev.cobolonjava.spring.boot4.demosecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskProgramPort;
import dev.cobolonjava.cics.CicsTerminalScreen;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CicsTransactionRegistry;
import dev.cobolonjava.cics.TaskCompletion;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.runtime.interop.ProgramId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * デモ環境の簡易認証をブラウザの入口で確かめる (設計 84、暫定判断 P-145)。
 *
 * <p>試験の application は他の試験の component scan に拾われないよう、別の package に置く。SecurityFilterChain と利用者は
 * {@code cobol.cics.security.mode=demo} の自動構成が作る。
 */
@SpringBootTest(classes = CicsDemoSecurityBrowserTest.DemoApplication.class, properties = {
    "cobol.cics.security.mode=demo",
    "cobol.cics.security.users[0].username=alice",
    "cobol.cics.security.users[0].password={noop}alice-pass",
    "cobol.cics.security.users[0].user-id=ALICE01",
    "cobol.cics.security.users[0].transactions[0]=SCR1",
    "cobol.cics.security.users[1].username=bob",
    "cobol.cics.security.users[1].password={noop}bob-pass",
    "cobol.cics.security.users[1].user-id=BOB01"
})
class CicsDemoSecurityBrowserTest {

    static final AtomicInteger RUNS = new AtomicInteger();

    @SpringBootApplication
    static class DemoApplication {

        @Bean
        CicsTransactionRegistry registry() {
            return new CicsTransactionRegistry(List.of(new CicsTransactionDefinition(
                    TransId.of("SCR1"), ProgramId.of("SCR1PGM"), Duration.ofSeconds(5), 16, 0, 0, 0, true)));
        }

        /** task が受けた user ID を画面に出す。 */
        @Bean
        CicsTaskProgramPort program() {
            return (definition, input, task, syncpoints) -> {
                RUNS.incrementAndGet();
                return new TaskCompletion(Optional.empty(), CicsPayload.empty())
                        .withScreen(Optional.of(new CicsTerminalScreen.TextScreen(
                                "USER " + task.userId().orElse("-"), true, false)));
            };
        }
    }

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("一覧のログイン名とパスワードでform loginでき、違うパスワードとログインしていない要求は入れない")
    void logsInWithConfiguredUsers() throws Exception {
        mvc.perform(formLogin().user("alice").password("alice-pass")).andExpect(authenticated().withUsername("alice"));
        mvc.perform(formLogin().user("alice").password("wrong")).andExpect(unauthenticated());
        MvcResult anonymous = mvc.perform(get("/cics/SCR1")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(302);
        assertThat(anonymous.getResponse().getRedirectedUrl()).contains("/login");
    }

    @Test
    @DisplayName("ログインした利用者のCICSのuser IDでtaskを起こし、起こせないtransactionと一覧に無い利用者は403でtaskを動かさない")
    void runsTasksUnderMappedUserIds() throws Exception {
        MvcResult alice = mvc.perform(post("/cics/SCR1").session(new MockHttpSession()).with(user("alice"))
                        .with(csrf()))
                .andExpect(status().isOk()).andReturn();
        assertThat(alice.getResponse().getContentAsString()).contains("USER ALICE01");

        int before = RUNS.get();
        MvcResult bob = mvc.perform(post("/cics/SCR1").session(new MockHttpSession()).with(user("bob")).with(csrf()))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(bob.getResponse().getContentAsString()).contains("not authorized").doesNotContain("BOB01");
        mvc.perform(post("/cics/SCR1").session(new MockHttpSession()).with(user("mallory")).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(RUNS.get()).isEqualTo(before);
    }
}
