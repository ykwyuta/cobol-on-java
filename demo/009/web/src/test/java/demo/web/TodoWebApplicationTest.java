package demo.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * ブラウザの入口から、翻訳した TODOAPP を H2 に対して動かす。
 *
 * <p>画面が運ぶ hidden (冪等キー、会話の ID と版、画面の版) を次の送信へ載せ直すのは、ブラウザがする
 * ことと同じである。画面の文面と表の中身の両方を見て、画面が変わるたびに業務の更新が確定したことを確かめる。
 */
@SpringBootTest
class TodoWebApplicationTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;
    MockHttpSession session;
    String screen;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        session = new MockHttpSession();
    }

    @Test
    @DisplayName("開始から ADD・DONE・DEL・CLEAR・PF3 まで、BMS の画面と H2 の表が一致したまま進む")
    void runsTheTodoConversation() throws Exception {
        String shell = mvc.perform(get("/cics/TODO").session(session).with(user("demo")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        screen = send(post("/cics/TODO").param("idempotencyKey", hidden(shell, "idempotencyKey")));
        assertThat(screen).contains("Welcome.", "read the BMS map TODOSET.bms", "name=\"bms.CMD.1\"");

        screen = enter("ADD write the design note");
        assertThat(screen).contains("Added entry 3.", "write the design note");
        // 固定長の PIC X(60) のホスト変数を VARCHAR に入れるので、後ろの空白ごと入る (Db2 と同じ)
        assertThat(jdbc.queryForObject("select TODO_TEXT from TODO where TODO_ID = 3", String.class))
                .isEqualTo(String.format("%-60s", "write the design note"));

        screen = enter("DONE 1");
        assertThat(screen).contains("Closed entry 1.");
        assertThat(jdbc.queryForObject("select DONE_FLAG from TODO where TODO_ID = 1", String.class))
                .isEqualTo("Y");

        screen = enter("DEL 2");
        assertThat(screen).contains("Deleted entry 2.").doesNotContain("build the project with mvn");
        assertThat(jdbc.queryForObject("select count(*) from TODO", Integer.class)).isEqualTo(2);

        // CLEAR は入力を送らないので RECEIVE MAP は MAPFAIL になる
        screen = key("CLEAR", null);
        assertThat(screen).contains("Nothing was entered.");

        screen = key("PF3", null);
        assertThat(screen).contains("TODO ended.");
    }

    private String enter(String command) throws Exception {
        return key("ENTER", command);
    }

    /** 直前の画面の hidden を載せ直して、AID キーと COMMAND 欄を送る。 */
    private String key(String aid, String command) throws Exception {
        MockHttpServletRequestBuilder request = post("/cics/TODO").param("aid", aid).param("cursor", "-1")
                .param("idempotencyKey", hidden(screen, "idempotencyKey"))
                .param("conversationId", hidden(screen, "conversationId"))
                .param("conversationVersion", hidden(screen, "conversationVersion"))
                .param("screenVersion", hidden(screen, "screenVersion"));
        if (command != null) {
            request.param("bms.CMD.1", command);
        }
        return send(request);
    }

    private String send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.session(session).with(user("demo")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String hidden(String html, String name) {
        Matcher value = Pattern.compile("name=\"" + name + "\" value=\"([^\"]*)\"").matcher(html);
        assertThat(value.find()).as("hidden %s", name).isTrue();
        return value.group(1);
    }
}
