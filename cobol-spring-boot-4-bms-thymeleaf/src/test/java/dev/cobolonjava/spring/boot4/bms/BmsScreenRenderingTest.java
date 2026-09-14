package dev.cobolonjava.spring.boot4.bms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.cobolonjava.cics.bms.BmsModel;
import dev.cobolonjava.cics.bms.BmsParser;
import dev.cobolonjava.cics.bms.BmsScreenComposer;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import dev.cobolonjava.runtime.codepage.CodePages;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** BMS 画面の表示モデルと Thymeleaf template (設計 81)。 */
class BmsScreenRenderingTest {

    private static String card(String body, boolean continued) {
        return continued ? String.format("%-71s*", body) : body;
    }

    private static final String BMS = String.join("\n",
            card("SCRSET   DFHMSD TYPE=&SYSPARM,MODE=INOUT,LANG=COBOL,STORAGE=AUTO,", true),
            card("               TIOAPFX=YES,EXTATT=YES,DSATTS=(COLOR,HILIGHT)", false),
            card("SCRMP    DFHMDI SIZE=(24,80)", false),
            // BMS の & は assembler の変数記号になるので、escape の確かめには < > " を使う
            card("         DFHMDF POS=(1,1),LENGTH=12,INITIAL='A<b>\"C\"',ATTRB=(PROT,NORM)", false),
            card("CUSTNO   DFHMDF POS=(5,17),LENGTH=10,ATTRB=(NORM,NUM,IC),COLOR=GREEN,", true),
            card("               HILIGHT=UNDERLINE", false),
            card("PASSWD   DFHMDF POS=(6,17),LENGTH=8,ATTRB=(UNPROT,DRK),INITIAL='SECRET'", false),
            card("HIDDEN   DFHMDF POS=(6,30),LENGTH=5,ATTRB=(PROT,DRK),INITIAL='TOPSC'", false),
            card("AFTER    DFHMDF POS=(6,40),LENGTH=5,ATTRB=(PROT,NORM),INITIAL='AFTER'", false),
            card("MESSAGE  DFHMDF POS=(23,1),LENGTH=20,ATTRB=(BRT,PROT),INITIAL='HELLO'", false),
            card("         DFHMSD TYPE=FINAL", false),
            card("         END", false)) + "\n";

    private static BmsScreenSnapshot snapshot(String bms) {
        BmsModel.Mapset mapset = BmsParser.parse(bms);
        BmsModel.Map map = mapset.map("SCRMP").orElseThrow();
        return BmsScreenComposer.send(mapset, map, Optional.empty(), new byte[0],
                new BmsScreenComposer.SendOptions(true, true, false, true, false, false, OptionalInt.empty(), false),
                CodePages.DEFAULT);
    }

    @Test
    @DisplayName("属性は列挙済みclassへ写し、DRKの値は表示モデルに残さない")
    void buildsViewSegments() {
        BmsScreenView view = new BmsScreenViewFactory().create(snapshot(BMS));

        BmsScreenView.Segment title = view.segments().get(0);
        assertThat(title.input()).isFalse();
        assertThat(title.row()).isEqualTo(1);
        assertThat(title.column()).isEqualTo(2);
        assertThat(title.cssClass()).isEqualTo("bms-field bms-protected bms-normal");

        BmsScreenView.Segment custno = view.segments().stream()
                .filter(segment -> "bms.CUSTNO.1".equals(segment.parameterName())).findFirst().orElseThrow();
        assertThat(custno.row()).isEqualTo(5);
        assertThat(custno.column()).isEqualTo(18);
        assertThat(custno.length()).isEqualTo(10);
        assertThat(custno.numeric()).isTrue();
        assertThat(custno.cssClass()).contains("bms-color-green", "bms-hl-underline");

        BmsScreenView.Segment password = view.segments().stream()
                .filter(segment -> "bms.PASSWD.1".equals(segment.parameterName())).findFirst().orElseThrow();
        assertThat(password.dark()).isTrue();
        assertThat(password.text()).isEmpty();
        assertThat(view.cursorRow()).isEqualTo(5);
        assertThat(view.cursorColumn()).isEqualTo(18);

        // 行は画面の幅ぶんの空白と field の並びで、属性 byte の桁は空白になる
        assertThat(view.lines()).hasSize(24);
        BmsScreenView.Line line5 = view.lines().get(4);
        assertThat(line5.items().get(0).blank()).hasSize(17);
        assertThat(line5.items().get(1).segment()).isSameAs(custno);
        assertThat(line5.items()).allSatisfy(item -> assertThat(item.segment() == null
                ? item.blank().length() : item.segment().length()).isPositive());
        assertThat(line5.items().stream().mapToInt(item -> item.segment() == null
                ? item.blank().length() : item.segment().length()).sum()).isEqualTo(80);
        assertThat(custno.cssClass()).contains("bms-len-10");

        // DRK の出力 field は値を持たず、長さぶんの空白で cell を保つ。同じ行のあとの field がずれない
        BmsScreenView.Line line6 = view.lines().get(5);
        BmsScreenView.Segment hidden = line6.items().stream().map(BmsScreenView.Item::segment)
                .filter(segment -> segment != null && segment.label().equals("HIDDEN")).findFirst().orElseThrow();
        assertThat(hidden.dark()).isTrue();
        assertThat(hidden.input()).isFalse();
        assertThat(hidden.text()).isEqualTo("     ");
        assertThat(line6.items().stream().mapToInt(item -> item.segment() == null
                ? item.blank().length() : item.segment().length()).sum()).isEqualTo(80);
        int columnOfAfter = 1;
        for (BmsScreenView.Item item : line6.items()) {
            if (item.segment() != null && item.segment().label().equals("AFTER")) {
                break;
            }
            columnOfAfter += item.segment() == null ? item.blank().length() : item.segment().length();
        }
        assertThat(columnOfAfter).isEqualTo(41);
    }

    @Test
    @DisplayName("templateは値をescapeし、入力fieldの名前と長さを出し、DRKの値をHTMLへ出さない")
    void rendersEscapedHtml() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        Context context = new Context();
        context.setVariable("screen", new BmsScreenViewFactory().create(snapshot(BMS)));
        context.setVariable("action", "/cics/OCAC");
        context.setVariable("bmsAssets", "/cobol/bms");

        String html = engine.process("cobol/bms/screen", context);

        assertThat(html).contains("A&lt;b&gt;&quot;C&quot;");
        assertThat(html).doesNotContain("<b>");
        assertThat(html).contains("name=\"bms.CUSTNO.1\"", "maxlength=\"10\"", "data-row=\"5\"", "data-column=\"18\"");
        assertThat(html).contains("type=\"password\"").doesNotContain("SECRET", "TOPSC");
        assertThat(html).contains("action=\"/cics/OCAC\"", "src=\"/cobol/bms/terminal.js\"");
        assertThat(html).doesNotContain("th:", "style=");
        assertThat(html).contains("class=\"bms-row\"", "bms-len-10");
    }

    @Test
    @DisplayName("画面端をまたぐfieldは位置をずらして描かず断る")
    void rejectsFieldsWrappingPastTheEdge() {
        String wrapping = BMS.replace("POS=(23,1),LENGTH=20", "POS=(23,70),LENGTH=20");
        assertThatThrownBy(() -> new BmsScreenViewFactory().create(snapshot(wrapping)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wraps past the screen edge");
    }

    @Test
    @DisplayName("formはAID名とCOBOL名を受け、field名の形とCLEARのshort readを確かめる")
    void bindsTerminalInput() {
        BmsTerminalInputBinder binder = new BmsTerminalInputBinder();

        var input = binder.bind("PF3", "401", Map.of("bms.custno.1", "042", "_csrf", "token"));
        assertThat(input.aid().cobolName()).isEqualTo("DFHPF3");
        assertThat(input.cursorOffset()).isEqualTo(401);
        assertThat(input.fields()).singleElement().satisfies(field -> {
            assertThat(field.name()).isEqualTo("CUSTNO");
            assertThat(field.value()).isEqualTo("042");
        });
        assertThat(binder.bind("DFHCLEAR", null, Map.of("bms.CUSTNO.1", "042")).fields()).isEmpty();
        assertThatThrownBy(() -> binder.bind("PF25", "0", Map.of())).hasMessageContaining("unsupported AID");
        assertThatThrownBy(() -> binder.bind("ENTER", "0", Map.of("bms.CUST NO.1", "x")))
                .hasMessageContaining("malformed BMS field parameter");
    }
}
