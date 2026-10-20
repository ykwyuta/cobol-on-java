package dev.cobolonjava.mq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cobolonjava.compiler.source.MqSystemCopyBookResolver;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Java の側の定数と、COBOL の側の写し句 CMQV が同じ値を持つ。片方だけ直すと、資産が比べる値と MQI が返す値がずれる。 */
@Tag("V1")
class MqConstantsTest {

    @Test
    @DisplayName("MqConstants のどの定数も CMQV に同じ名前・同じ値である")
    void javaConstantsMatchTheCopybook() throws IllegalAccessException {
        String text = new MqSystemCopyBookResolver().resolve("CMQV", null).orElseThrow().text();
        Map<String, Integer> cobol = new HashMap<>();
        Matcher m = Pattern.compile("10 (MQ[A-Z0-9-]+)\\s+PIC S9\\(9\\) BINARY VALUE (-?\\d+)\\.").matcher(text);
        while (m.find()) {
            cobol.put(m.group(1), Integer.parseInt(m.group(2)));
        }
        assertTrue(cobol.size() > 100, "parsed " + cobol.size() + " constants");
        for (Field f : MqConstants.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                continue;
            }
            String name = f.getName().replace('_', '-');
            assertTrue(cobol.containsKey(name), "CMQV lacks " + name);
            assertEquals(cobol.get(name), f.getInt(null), name);
        }
    }
}
