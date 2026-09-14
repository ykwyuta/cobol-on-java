package dev.cobolonjava.spring.boot4.cics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskProgramPort;
import dev.cobolonjava.cics.CicsTerminalScreen;
import dev.cobolonjava.cics.CicsTransactionDefinition;
import dev.cobolonjava.cics.CicsTransactionRegistry;
import dev.cobolonjava.cics.TaskCompletion;
import dev.cobolonjava.cics.TransId;
import dev.cobolonjava.cics.bms.BmsModel;
import dev.cobolonjava.cics.bms.BmsParser;
import dev.cobolonjava.cics.bms.BmsScreenComposer;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.interop.ProgramId;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** JSON client の入口 (暫定判断 P-135)。 */
@SpringBootTest(classes = CicsJsonApiTest.TestApplication.class)
class CicsJsonApiTest {

    private static String card(String body, boolean continued) {
        return continued ? String.format("%-71s*", body) : body;
    }

    private static final String BMS = String.join("\n",
            card("SCRSET   DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,STORAGE=AUTO,", true),
            card("               TIOAPFX=YES,EXTATT=YES,DSATTS=(COLOR,HILIGHT)", false),
            card("SCRMP    DFHMDI SIZE=(24,80)", false),
            card("CUSTNO   DFHMDF POS=(5,17),LENGTH=10,ATTRB=(NORM,UNPROT,IC)", false),
            card("PIN      DFHMDF POS=(6,17),LENGTH=4,ATTRB=(UNPROT,DRK),INITIAL='9999'", false),
            card("         DFHMSD TYPE=FINAL", false),
            card("         END", false)) + "\n";

    @SpringBootApplication
    static class TestApplication {

        @Bean
        CicsTransactionRegistry registry() {
            return new CicsTransactionRegistry(List.of(new CicsTransactionDefinition(
                    TransId.of("API1"), ProgramId.of("API1PGM"), Duration.ofSeconds(5), 16, 0, 0, 0, true)));
        }

        /** 端末入力が無ければ map を送り、受けた COMMAREA の先頭に 1 を足して自分へ RETURN する。 */
        @Bean
        CicsTaskProgramPort program() {
            BmsModel.Mapset mapset = BmsParser.parse(BMS);
            BmsModel.Map map = mapset.map("SCRMP").orElseThrow();
            BmsScreenSnapshot snapshot = BmsScreenComposer.send(mapset, map, Optional.empty(), new byte[0],
                    new BmsScreenComposer.SendOptions(true, true, false, true, false, false, OptionalInt.empty(), false),
                    CodePages.DEFAULT);
            return (definition, input, task, syncpoints) -> {
                if (task.terminalInput().isEmpty()) {
                    byte first = input.commarea().length == 0 ? 0 : input.commarea()[0];
                    return new TaskCompletion(Optional.of(TransId.of("API1")),
                            CicsPayload.ofCommarea(new byte[] {(byte) (first + 1)}))
                            .withScreen(Optional.of(new CicsTerminalScreen.MapScreen(snapshot)));
                }
                String value = task.terminalInput().orElseThrow().fields().stream()
                        .map(field -> field.name() + "=" + field.value()).findFirst().orElse("none");
                return new TaskCompletion(Optional.empty(), CicsPayload.ofCommarea(input.commarea()))
                        .withScreen(Optional.of(new CicsTerminalScreen.TextScreen("RECEIVED " + value, true, false)));
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
    @DisplayName("認証とCSRFのtokenが無い要求はtaskを動かさない")
    void requiresAuthenticationAndCsrf() throws Exception {
        assertThat(mvc.perform(post("/api/cics/API1").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn().getResponse().getStatus()).isIn(401, 403);
        mvc.perform(post("/api/cics/API1").with(user("bob")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("COMMAREAをbase64で渡してmapを受け、会話を続ける要求ではserverのCOMMAREAと端末入力をtaskへ渡す")
    void runsConversationOverJson() throws Exception {
        String started = mvc.perform(post("/api/cics/API1").with(user("bob")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"commarea\":\"BQ==\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commarea").value("Bg=="))
                .andExpect(jsonPath("$.screen.type").value("map"))
                .andExpect(jsonPath("$.screen.fields[0].name").value("CUSTNO"))
                .andExpect(jsonPath("$.screen.fields[1].data").doesNotExist())
                .andExpect(jsonPath("$.conversation.nextTransaction").value("API1"))
                .andReturn().getResponse().getContentAsString();
        assertThat(started).doesNotContain("9999");
        Matcher id = Pattern.compile("\"id\":\"([A-Za-z0-9_-]+)\"").matcher(started);
        assertThat(id.find()).isTrue();

        mvc.perform(post("/api/cics/API1").with(user("bob")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation\":{\"id\":\"" + id.group(1) + "\",\"version\":0},"
                                + "\"idempotencyKey\":\"client-key-0001\","
                                + "\"terminal\":{\"aid\":\"ENTER\",\"cursor\":337,"
                                + "\"fields\":[{\"name\":\"custno\",\"occurrence\":1,\"value\":\"042\"}]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screen.type").value("text"))
                .andExpect(jsonPath("$.screen.text").value("RECEIVED CUSTNO=042"))
                .andExpect(jsonPath("$.commarea").value("Bg=="))
                .andExpect(jsonPath("$.conversation").doesNotExist());
    }

    @Test
    @DisplayName("続ける会話にCOMMAREAを付けた要求、不正なbase64、古い版、未定義のTRANSIDは内容を出さずに断る")
    void rejectsInvalidRequests() throws Exception {
        mvc.perform(post("/api/cics/API1").with(user("bob")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation\":{\"id\":\"AAAAAAAAAAAAAAAAAAAA\",\"version\":0},\"commarea\":\"AQ==\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The request is not valid."));
        mvc.perform(post("/api/cics/API1").with(user("bob")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commarea\":\"not base64!\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/cics/API1").with(user("bob")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation\":{\"id\":\"AAAAAAAAAAAAAAAAAAAA\",\"version\":3}}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/cics/NOPE").with(user("bob")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
