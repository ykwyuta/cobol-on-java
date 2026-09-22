package dev.cobolonjava.spring.boot4.bms;

import static org.assertj.core.api.Assertions.assertThat;

import dev.cobolonjava.runtime.codepage.CodePages;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ブラウザへ渡す「入る文字」の一覧 (設計 81 §5.1)。 */
class BmsTerminalRepertoireTest {

    private static final Pattern FIELD = Pattern.compile("\"(single|double)\":\"([^\"]*)\"");

    private static boolean contains(String json, String field, int codePoint) {
        Matcher matcher = FIELD.matcher(json);
        while (matcher.find()) {
            if (!matcher.group(1).equals(field)) {
                continue;
            }
            for (String range : matcher.group(2).split(",")) {
                if (range.isEmpty()) {
                    continue;
                }
                String[] ends = range.split("-");
                int from = Integer.parseInt(ends[0], 16);
                int to = ends.length > 1 ? Integer.parseInt(ends[1], 16) : from;
                if (codePoint >= from && codePoint <= to) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("SBCSのコードページは1桁の範囲だけを出し、シフト符号を使わない")
    void describesASingleByteCodePage() {
        String json = new BmsTerminalRepertoire(CodePages.IBM_1047).json();

        assertThat(json).isEqualTo(
                "{\"codePage\":\"IBM-1047\",\"shifted\":false,\"single\":\"0-ff\",\"double\":\"\"}");
    }

    @Test
    @DisplayName("混在コードページはDBCSを2桁の側に出し、半角カタカナを1桁の側に出す")
    void describesAMixedCodePage() {
        String json = new BmsTerminalRepertoire(CodePages.IBM_930).json();

        assertThat(json).startsWith("{\"codePage\":\"IBM-930\",\"shifted\":true,");
        assertThat(contains(json, "single", 'A')).isTrue();
        assertThat(contains(json, "single", 0xFF71)).as("半角カタカナ ｱ").isTrue();
        assertThat(contains(json, "double", 0x5C71)).as("山").isTrue();
        assertThat(contains(json, "double", 0xFF21)).as("全角 Ａ").isTrue();
        // IBM-930 に無い文字は、どちらの側にも出さない。ブラウザはこれを弾く
        assertThat(contains(json, "single", 0x00E9) || contains(json, "double", 0x00E9))
                .as("é").isFalse();
        assertThat(contains(json, "single", 0x1F600) || contains(json, "double", 0x1F600))
                .as("絵文字").isFalse();
    }

    @Test
    @DisplayName("JSONは1度だけ作る")
    void buildsTheJsonOnce() {
        BmsTerminalRepertoire repertoire = new BmsTerminalRepertoire(CodePages.IBM_939);

        assertThat(repertoire.json()).isSameAs(repertoire.json());
    }
}
