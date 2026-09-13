package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("V1")
class CicsPayloadTest {

    @Test
    @DisplayName("COMMAREAとcontainerを入出力の両側でcopy分離する")
    void defensivelyCopiesPayload() {
        byte[] commarea = {1, 2};
        byte[] container = {3, 4};
        CicsPayload payload = new CicsPayload(commarea, Map.of("data", container));

        commarea[0] = 9;
        container[0] = 9;
        byte[] returnedCommarea = payload.commarea();
        byte[] returnedContainer = payload.containers().get("DATA");
        returnedCommarea[1] = 9;
        returnedContainer[1] = 9;

        assertArrayEquals(new byte[] {1, 2}, payload.commarea());
        assertArrayEquals(new byte[] {3, 4}, payload.containers().get("DATA"));
        assertEquals(2, payload.commareaLength());
        assertEquals(1, payload.containerCount());
        assertEquals(2, payload.containerTotalLength());
    }

    @Test
    @DisplayName("正規化後に重複するcontainer名と未許可名を拒否する")
    void rejectsAmbiguousContainerNames() {
        Map<String, byte[]> duplicate = new LinkedHashMap<>();
        duplicate.put("data", new byte[0]);
        duplicate.put("DATA", new byte[0]);

        assertThrows(IllegalArgumentException.class,
                () -> new CicsPayload(new byte[0], duplicate));
        assertThrows(IllegalArgumentException.class,
                () -> new CicsPayload(new byte[0], Map.of("../DATA", new byte[0])));
    }
}
