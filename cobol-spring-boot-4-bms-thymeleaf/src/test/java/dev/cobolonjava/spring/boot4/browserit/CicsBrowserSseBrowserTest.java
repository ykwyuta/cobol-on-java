package dev.cobolonjava.spring.boot4.browserit;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import dev.cobolonjava.cics.CicsPayload;
import dev.cobolonjava.cics.CicsTaskProgramPort;
import dev.cobolonjava.cics.CicsTerminalRegistryPort;
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
import dev.cobolonjava.spring.boot4.bms.CicsBrowserTerminalNames;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 実際のブラウザで、端末へ出す task の画面が SSE で届くことを確かめる (設計 83 §7、暫定判断 P-144)。
 *
 * <p>MockMvc は EventSource も terminal.js も動かさないので、そこで確かめられるのは server の応答の形までである。
 * ここでは Playwright で導入済みのブラウザ (既定は Edge、{@code BROWSER_CHANNEL} で替える) を動かし、利用者の操作なしに
 * 画面が読み直されるかを見る。ブラウザを取得しないので {@code BROWSER_IT_ENABLED=true} のときだけ動く。
 *
 * <p>試験の application は他の試験の component scan に拾われないよう、別の package に置く。
 */
@Tag("BROWSER_IT")
@EnabledIfEnvironmentVariable(named = "BROWSER_IT_ENABLED", matches = "(?i)true")
@SpringBootTest(classes = CicsBrowserSseBrowserTest.BrowserApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CicsBrowserSseBrowserTest {

    private static final String USER = "alice";
    private static final String PASSWORD = "alice-browser-test";
    private static final String TERMINAL = "PRT1";

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

    @SpringBootApplication
    static class BrowserApplication {

        @Bean
        SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                    .formLogin(Customizer.withDefaults())
                    .build();
        }

        @Bean
        UserDetailsService users() {
            return new InMemoryUserDetailsManager(User.withUsername(USER).password("{noop}" + PASSWORD)
                    .roles("USER").build());
        }

        /** 試験から端末へ画面を置けるよう、端末名を決めておく。 */
        @Bean
        CicsBrowserTerminalNames terminalNames() {
            return principal -> principal.equals(USER) ? Optional.of(TERMINAL) : Optional.empty();
        }

        @Bean
        CicsTransactionRegistry registry() {
            return new CicsTransactionRegistry(List.of(new CicsTransactionDefinition(
                    TransId.of("SCR1"), ProgramId.of("SCR1PGM"), Duration.ofSeconds(5), 16, 0, 0, 0, true)));
        }

        /** 端末入力が無ければ map を送って自分へ RETURN し、入力があれば受けた値を文字で返す。 */
        @Bean
        CicsTaskProgramPort program() {
            BmsModel.Mapset mapset = BmsParser.parse(BMS);
            BmsModel.Map map = mapset.map("SCRMP").orElseThrow();
            BmsScreenSnapshot snapshot = BmsScreenComposer.send(mapset, map, Optional.empty(), new byte[0],
                    new BmsScreenComposer.SendOptions(true, true, false, true, false, false, OptionalInt.empty(), false),
                    CodePages.DEFAULT);
            return (definition, input, task, syncpoints) -> {
                if (task.terminalInput().isEmpty()) {
                    return new TaskCompletion(Optional.of(TransId.of("SCR1")), CicsPayload.ofCommarea(new byte[] {7}))
                            .withScreen(Optional.of(new CicsTerminalScreen.MapScreen(snapshot)));
                }
                String value = task.terminalInput().orElseThrow().fields().stream()
                        .map(field -> field.name() + "=" + field.value().strip()).findFirst().orElse("none");
                return new TaskCompletion(Optional.empty(), CicsPayload.empty())
                        .withScreen(Optional.of(new CicsTerminalScreen.TextScreen("RECEIVED " + value, true, false)));
            };
        }
    }

    private static Playwright playwright;
    private static Browser browser;

    @LocalServerPort
    int port;

    @Autowired
    CicsTerminalRegistryPort terminals;

    @BeforeAll
    static void launchBrowser() {
        // 導入済みのブラウザを使うので、Playwright にブラウザを取得させない
        playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
        // 導入済みの版が Playwright の求める版と違うことがある。そのときは BROWSER_EXECUTABLE で
        // 実行ファイルを直に指す (channel では版の違う実行ファイルを探しに行って失敗する)
        String executable = System.getenv("BROWSER_EXECUTABLE");
        if (executable != null && !executable.isBlank()) {
            options.setExecutablePath(java.nio.file.Path.of(executable));
        } else {
            options.setChannel(Optional.ofNullable(System.getenv("BROWSER_CHANNEL"))
                    .filter(value -> !value.isBlank()).orElse("msedge"));
        }
        browser = playwright.chromium().launch(options);
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /** 開始の画面を開き、form login を通って transaction を開始し、map の画面を出す。 */
    private void startTransaction(Page page) {
        page.navigate(url("/cics/SCR1"));
        page.locator("input[name='username']").fill(USER);
        page.locator("input[name='password']").fill(PASSWORD);
        page.locator("button[type='submit']").click();
        // terminal.js が SSE を開くのを待ってから次へ進む
        page.waitForRequest(request -> request.url().contains("/cics/terminal/events"),
                () -> page.locator("button[type='submit']").click());
        assertThat(page.locator("input[name='bms.CUSTNO.1']")).isVisible();
    }

    /** START TERMID や ATI の task が端末へ画面を送った状況を作る。 */
    private void sendFromTerminalTask(String text) {
        CicsTerminalRegistryPort.TerminalLease lease = terminals.lease(TERMINAL, USER, Duration.ofSeconds(30),
                Instant.now()).orElseThrow();
        try {
            terminals.setScreen(lease, new CicsTerminalScreen.TextScreen(text, true, false), Instant.now());
            terminals.setConversation(lease, Optional.empty(), Instant.now());
        } finally {
            terminals.release(lease, Instant.now());
        }
    }

    @Test
    @DisplayName("端末へ出すtaskが画面を置くと、開いている画面はSSEで知って利用者の操作なしに現在の画面を読み直す")
    void reloadsScreenWhenTerminalTaskSendsOne() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            startTransaction(page);

            sendFromTerminalTask("URGENT MESSAGE FROM ATI");

            page.waitForURL("**/cics/terminal", new Page.WaitForURLOptions().setTimeout(15_000));
            assertThat(page.locator("pre.bms-text")).hasText("URGENT MESSAGE FROM ATI");
        }
    }

    @Test
    @DisplayName("自分の送信の応答では画面の版が進まず、SSEで読み直さない")
    void keepsOwnReplyOnScreen() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            startTransaction(page);

            page.locator("input[name='bms.CUSTNO.1']").fill("042");
            page.getByText("Enter", new Page.GetByTextOptions().setExact(true)).click();
            assertThat(page.locator("pre.bms-text")).hasText("RECEIVED CUSTNO=042");

            // SSE の見回り (1 秒ごと) を何度か越えても、画面は応答のまま
            page.waitForTimeout(3_000);
            assertTrue(page.url().endsWith("/cics/SCR1"), page.url());
            assertThat(page.locator("pre.bms-text")).hasText("RECEIVED CUSTNO=042");
        }
    }
    @Test
    @DisplayName("コードページに無い文字は入力欄に入らない。打鍵も、値ごと入る経路も落とす")
    void refusesCharactersOutsideTheCodePage() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            startTransaction(page);
            // server から文字の一覧が届くまで待つ。届く前は見積りで動くので、弾く判定はまだ確かでない
            page.waitForSelector("form.bms-terminal[data-codepage='IBM-1047']");
            Locator field = page.locator("input[name='bms.CUSTNO.1']");
            field.click();

            field.pressSequentially("A\u00E9\u5C71B");

            // IBM-1047 は Latin-1 を持つので é は入る。日本語は持たないので 山 は打てない
            assertThat(field).hasValue("A\u00E9B");

            // 貼り付けや IME の確定のように、値ごと変わる経路も落とす
            page.evaluate("""
                    const input = document.querySelector("input[name='bms.CUSTNO.1']");
                    input.value = '\u5C71\u7530';
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    """);

            assertThat(field).hasValue("");
        }
    }

    @Test
    @DisplayName("画面を開いてそのまま打てる。詰めた空白が残っているとmaxlengthで1文字も入らない")
    void acceptsTypingWithoutClearingThePadding() {
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            startTransaction(page);
            Locator field = page.locator("input[name='bms.CUSTNO.1']");

            // 画面から来たままの入力欄に、消さずに打つ
            assertThat(field).hasValue("");
            field.click();
            field.pressSequentially("0000000042");

            assertThat(field).hasValue("0000000042");

            // 打った値が task まで届く
            page.locator("button[type='submit']").first().click();
            assertThat(page.locator("pre.bms-text")).hasText("RECEIVED CUSTNO=0000000042");
        }
    }

}
