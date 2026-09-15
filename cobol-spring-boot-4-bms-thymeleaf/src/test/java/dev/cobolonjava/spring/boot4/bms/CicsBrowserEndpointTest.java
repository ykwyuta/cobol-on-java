package dev.cobolonjava.spring.boot4.bms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskProgramPort;
import dev.cobolonjava.cics.CicsTerminalScreen;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CicsTransactionRegistry;
import dev.cobolonjava.cics.ConversationId;
import dev.cobolonjava.cics.ConversationStorePort;
import dev.cobolonjava.cics.TaskCompletion;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.cics.bms.BmsModel;
import dev.cobolonjava.cics.bms.BmsParser;
import dev.cobolonjava.cics.bms.BmsScreenComposer;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.ProgramId;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
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

/** ブラウザから疑似会話を動かす入口 (設計 81 §5、暫定判断 P-134)。 */
@SpringBootTest(classes = CicsBrowserEndpointTest.TestApplication.class)
class CicsBrowserEndpointTest {

    private static String card(String body, boolean continued) {
        return continued ? String.format("%-71s*", body) : body;
    }

    private static final String BMS = String.join("\n",
            card("SCRSET   DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,STORAGE=AUTO,", true),
            card("               TIOAPFX=YES,EXTATT=YES,DSATTS=(COLOR,HILIGHT)", false),
            card("SCRMP    DFHMDI SIZE=(24,80)", false),
            card("CUSTNO   DFHMDF POS=(5,17),LENGTH=10,ATTRB=(NORM,UNPROT,IC)", false),
            card("         DFHMSD TYPE=FINAL", false),
            card("         END", false)) + "\n";

    /** program が動いた回数。二重送信で task が動かないことを見る。 */
    static final java.util.concurrent.atomic.AtomicInteger RUNS = new java.util.concurrent.atomic.AtomicInteger();

    @SpringBootApplication
    static class TestApplication {

        @Bean
        CicsTransactionRegistry registry() {
            return new CicsTransactionRegistry(List.of(new CicsTransactionDefinition(
                    TransId.of("SCR1"), ProgramId.of("SCR1PGM"), Duration.ofSeconds(5), 16, 0, 0, 0, true)));
        }

        /** 端末入力が無ければ map を送って自分へ RETURN し、入力があれば受けた値と COMMAREA を文字で返す。 */
        @Bean
        CicsTaskProgramPort program() {
            BmsModel.Mapset mapset = BmsParser.parse(BMS);
            BmsModel.Map map = mapset.map("SCRMP").orElseThrow();
            BmsScreenSnapshot snapshot = BmsScreenComposer.send(mapset, map, Optional.empty(), new byte[0],
                    new BmsScreenComposer.SendOptions(true, true, false, true, false, false, OptionalInt.empty(), false),
                    CodePages.DEFAULT);
            return (definition, input, task, syncpoints) -> {
                RUNS.incrementAndGet();
                if (task.terminalInput().isEmpty()) {
                    return new TaskCompletion(Optional.of(TransId.of("SCR1")), CicsPayload.ofCommarea(new byte[] {7}))
                            .withScreen(Optional.of(new CicsTerminalScreen.MapScreen(snapshot)));
                }
                String value = task.terminalInput().orElseThrow().fields().stream()
                        .map(field -> field.name() + "=" + field.value()).findFirst().orElse("none");
                String text = "RECEIVED " + value + " COMMAREA " + input.commarea()[0]
                        + " TERMINAL " + task.terminalId().orElse("-") + " USER " + task.userId().orElse("-");
                return new TaskCompletion(Optional.empty(), CicsPayload.empty())
                        .withScreen(Optional.of(new CicsTerminalScreen.TextScreen(text, true, false)));
            };
        }
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    ConversationStorePort conversations;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("認証が無ければ入らず、CSRFのtokenが無いPOSTはtaskを動かさない")
    void requiresAuthenticationAndCsrf() throws Exception {
        assertThat(mvc.perform(get("/cics/SCR1")).andReturn().getResponse().getStatus()).isNotEqualTo(200);
        mvc.perform(post("/cics/SCR1").with(user("alice"))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("開始するとmapを描き、Enterで送った値とserverに残したCOMMAREAを次のtaskが受ける")
    void runsPseudoConversation() throws Exception {
        MvcResult started = mvc.perform(post("/cics/SCR1").with(user("alice")).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        String html = started.getResponse().getContentAsString();
        assertThat(html).contains("name=\"bms.CUSTNO.1\"", "action=\"/cics/SCR1\"");
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession();
        assertThat(session.getAttribute(CicsBrowserController.CONVERSATION)).isNotNull();

        MvcResult entered = mvc.perform(post("/cics/SCR1").session(session).with(user("alice")).with(csrf())
                        .param("aid", "ENTER").param("cursor", "337").param("bms.CUSTNO.1", "042"))
                .andExpect(status().isOk()).andReturn();

        assertThat(entered.getResponse().getContentAsString())
                .contains("RECEIVED CUSTNO=042 COMMAREA 7", "TERMINAL W", "USER ALICE");
        assertThat(session.getAttribute(CicsBrowserController.CONVERSATION)).isNull();
    }

    @Test
    @DisplayName("画面の冪等キーで同じ送信を二度しても、taskは一度だけ動いて同じ画面を返し、同じキーで違う値は409")
    void replaysDoubleSubmittedScreens() throws Exception {
        int before = RUNS.get();
        MockHttpSession session = new MockHttpSession();
        MvcResult shell = mvc.perform(get("/cics/SCR1").session(session).with(user("alice")))
                .andExpect(status().isOk()).andReturn();
        String startKey = hidden(shell, "idempotencyKey");

        MvcResult started = mvc.perform(post("/cics/SCR1").session(session).with(user("alice")).with(csrf())
                        .param("idempotencyKey", startKey))
                .andExpect(status().isOk()).andReturn();
        MvcResult startedAgain = mvc.perform(post("/cics/SCR1").session(session).with(user("alice")).with(csrf())
                        .param("idempotencyKey", startKey))
                .andExpect(status().isOk()).andReturn();
        assertThat(startedAgain.getResponse().getContentAsString()).contains("name=\"bms.CUSTNO.1\"");
        assertThat(RUNS.get() - before).isEqualTo(1);

        String key = hidden(started, "idempotencyKey");
        String id = hidden(started, "conversationId");
        String version = hidden(started, "conversationVersion");
        for (int attempt = 0; attempt < 2; attempt++) {
            MvcResult entered = mvc.perform(post("/cics/SCR1").session(session).with(user("alice")).with(csrf())
                            .param("idempotencyKey", key).param("conversationId", id)
                            .param("conversationVersion", version)
                            .param("aid", "ENTER").param("cursor", "337").param("bms.CUSTNO.1", "042"))
                    .andExpect(status().isOk()).andReturn();
            assertThat(entered.getResponse().getContentAsString()).contains("RECEIVED CUSTNO=042 COMMAREA 7");
        }
        assertThat(RUNS.get() - before).isEqualTo(2);

        mvc.perform(post("/cics/SCR1").session(session).with(user("alice")).with(csrf())
                        .param("idempotencyKey", key).param("conversationId", id)
                        .param("conversationVersion", version)
                        .param("aid", "ENTER").param("cursor", "337").param("bms.CUSTNO.1", "043"))
                .andExpect(status().isConflict());
        assertThat(RUNS.get() - before).isEqualTo(2);
    }

    @Test
    @DisplayName("HTTP sessionが消えると、その会話を会話ストアから捨てる")
    void discardsConversationWhenSessionIsDestroyed() throws Exception {
        MvcResult started = mvc.perform(post("/cics/SCR1").with(user("alice")).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession();
        ConversationId id = new ConversationId(((CicsBrowserController.BrowserConversation)
                session.getAttribute(CicsBrowserController.CONVERSATION)).id());
        assertThat(conversations.load(id, Instant.now())).isPresent();

        // MockHttpSession の invalidate は listener を呼ばないので、container の代わりに event を渡す
        assertThat(context.getBeansOfType(HttpSessionListener.class).values())
                .hasAtLeastOneElementOfType(CicsBrowserSessionListener.class);
        context.getBean(CicsBrowserSessionListener.class).sessionDestroyed(new HttpSessionEvent(session));

        assertThat(conversations.load(id, Instant.now())).isEmpty();
    }

    private static String hidden(MvcResult result, String name) throws Exception {
        java.util.regex.Matcher value = java.util.regex.Pattern.compile("name=\"" + name + "\" value=\"([^\"]*)\"")
                .matcher(result.getResponse().getContentAsString());
        assertThat(value.find()).isTrue();
        return value.group(1);
    }

    @Test
    @DisplayName("sessionの会話の版が会話ストアと違えば古い画面として409を返し、taskを動かさない")
    void rejectsStaleConversation() throws Exception {
        MvcResult started = mvc.perform(post("/cics/SCR1").with(user("alice")).with(csrf()))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession();
        CicsBrowserController.BrowserConversation current =
                (CicsBrowserController.BrowserConversation) session.getAttribute(CicsBrowserController.CONVERSATION);
        session.setAttribute(CicsBrowserController.CONVERSATION,
                new CicsBrowserController.BrowserConversation(current.id(), current.version() + 5, "SCR1"));

        MvcResult stale = mvc.perform(post("/cics/SCR1").session(session).with(user("alice")).with(csrf())
                        .param("aid", "ENTER").param("bms.CUSTNO.1", "042"))
                .andExpect(status().isConflict()).andReturn();

        assertThat(stale.getResponse().getContentAsString()).contains("out of date").doesNotContain("RECEIVED");
        assertThat(session.getAttribute(CicsBrowserController.CONVERSATION)).isNull();
    }
}
