package dev.cobolonjava.cics.bms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Color;
import dev.cobolonjava.cics.bms.BmsModel.Highlight;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** DFHBMSCA の値が、公開文書の意味どおりの 3270 属性として読めること。 */
@Tag("V1")
class DfhbmscaCopybookTest {

    private static int value(String name) {
        return java.util.stream.Stream.concat(CicsSystemCopybooks.ATTRIBUTE_CONSTANTS.stream(),
                        CicsSystemCopybooks.EXTENDED_CONSTANTS.stream())
                .filter(entry -> entry.getKey().equals(name)).findFirst().orElseThrow().getValue();
    }

    @Test
    @DisplayName("基本属性の名前は、文書の意味 (保護・自動skip・明るさ・MDT) の属性 byte になる")
    void basicAttributesMatchTheDocumentedMeaning() {
        Map<String, BmsAttributeCodes.Basic> expected = Map.of(
                "DFHBMUNP", basic(false, BasicAttribute.UNPROT, BasicAttribute.NORM),
                "DFHBMUNN", basic(false, BasicAttribute.UNPROT, BasicAttribute.NUM, BasicAttribute.NORM),
                "DFHBMPRO", basic(false, BasicAttribute.PROT, BasicAttribute.NORM),
                "DFHBMASK", basic(false, BasicAttribute.ASKIP, BasicAttribute.NORM),
                "DFHBMBRY", basic(false, BasicAttribute.UNPROT, BasicAttribute.BRT),
                "DFHBMDAR", basic(false, BasicAttribute.UNPROT, BasicAttribute.DRK),
                "DFHBMFSE", basic(true, BasicAttribute.UNPROT, BasicAttribute.NORM),
                "DFHBMPRF", basic(true, BasicAttribute.PROT, BasicAttribute.NORM),
                "DFHBMASF", basic(true, BasicAttribute.ASKIP, BasicAttribute.NORM),
                "DFHBMASB", basic(false, BasicAttribute.ASKIP, BasicAttribute.BRT));
        expected.forEach((name, meaning) ->
                assertEquals(meaning, BmsAttributeCodes.basic(value(name)), name));

        assertEquals(Optional.of(Color.GREEN), BmsAttributeCodes.color(value("DFHGREEN")));
        assertEquals(Optional.of(Color.NEUTRAL), BmsAttributeCodes.color(value("DFHNEUTR")));
        assertEquals(Optional.of(Highlight.UNDERLINE), BmsAttributeCodes.highlight(value("DFHUNDLN")));
        assertTrue(CicsSystemCopybooks.text("dfhbmsca").orElseThrow()
                .contains("02  DFHBMPRF PIC X VALUE X'61'."));
    }

    private static BmsAttributeCodes.Basic basic(boolean modified, BasicAttribute... attributes) {
        return new BmsAttributeCodes.Basic(Set.of(attributes), modified);
    }
}
