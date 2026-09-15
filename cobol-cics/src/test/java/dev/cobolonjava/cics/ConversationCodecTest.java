package dev.cobolonjava.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Color;
import dev.cobolonjava.cics.bms.BmsModel.Highlight;
import dev.cobolonjava.cics.bms.BmsModel.Position;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 会話の envelope と task の応答の byte 列 (暫定判断 P-143)。 */
@Tag("V1")
class ConversationCodecTest {

    private static final BmsScreenSnapshot SCREEN = new BmsScreenSnapshot("SCRSET", "SCRMP", 24, 80, List.of(
            new BmsScreenSnapshot.FieldState(Optional.of("CUSTNO"), 1, new Position(5, 17), 10,
                    EnumSet.of(BasicAttribute.UNPROT, BasicAttribute.IC), Optional.of(Color.GREEN),
                    Optional.of(Highlight.UNDERLINE), "0000000042", true),
            new BmsScreenSnapshot.FieldState(Optional.empty(), 1, new Position(1, 1), 5,
                    EnumSet.noneOf(BasicAttribute.class), Optional.empty(), Optional.empty(), "Titlé", false)),
            337, true, false);

    private static ConversationEnvelope envelope() {
        return new ConversationEnvelope(new ConversationId("conversation_codec"), 7, "carol", TransId.of("NXT1"),
                new CicsPayload(new byte[] {1, 2, 3}, Map.of("CIPA", new byte[] {9}, "CIPB", new byte[0]),
                        "CIPCREDCHANN"),
                Instant.parse("2026-09-15T12:34:56.789Z"), new IdempotencyKey("client-key-0001"),
                Optional.of("task_1"), Optional.of(SCREEN));
    }

    private static void assertSamePayload(CicsPayload expected, CicsPayload actual) {
        assertArrayEquals(expected.commarea(), actual.commarea());
        assertEquals(expected.channelName(), actual.channelName());
        assertEquals(expected.containers().keySet(), actual.containers().keySet());
        expected.containers().forEach((name, value) -> assertArrayEquals(value, actual.containers().get(name)));
    }

    private static void assertSameEnvelope(ConversationEnvelope expected, ConversationEnvelope actual) {
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.version(), actual.version());
        assertEquals(expected.owner(), actual.owner());
        assertEquals(expected.nextTransaction(), actual.nextTransaction());
        assertSamePayload(expected.payload(), actual.payload());
        assertEquals(expected.expiresAt(), actual.expiresAt());
        assertEquals(expected.idempotencyKey(), actual.idempotencyKey());
        assertEquals(expected.lastOutcome(), actual.lastOutcome());
        assertEquals(expected.screen(), actual.screen());
    }

    @Test
    @DisplayName("envelopeはCOMMAREA・container・channelの名前・画面・期限をそのまま戻す")
    void roundTripsEnvelopes() {
        ConversationEnvelope original = envelope();
        assertSameEnvelope(original, ConversationCodec.decodeEnvelope(ConversationCodec.encodeEnvelope(original)));
    }

    @Test
    @DisplayName("応答は次の会話と文字の画面を戻す")
    void roundTripsReplies() {
        CicsTaskReply original = new CicsTaskReply(new CicsTaskId("task_codec"), TransId.of("TX01"),
                CicsPayload.ofCommarea(new byte[] {4}), Optional.of(envelope()), true,
                Optional.of(new CicsTerminalScreen.TextScreen("DONE", true, true)));
        CicsTaskReply decoded = ConversationCodec.decodeReply(ConversationCodec.encodeReply(original));
        assertEquals(original.taskId(), decoded.taskId());
        assertEquals(original.transactionId(), decoded.transactionId());
        assertSamePayload(original.payload(), decoded.payload());
        assertSameEnvelope(original.nextConversation().orElseThrow(), decoded.nextConversation().orElseThrow());
        assertEquals(original.immediateNext(), decoded.immediateNext());
        assertEquals(original.screen(), decoded.screen());

        CicsTaskReply mapReply = new CicsTaskReply(new CicsTaskId("task_codec"), TransId.of("TX01"),
                CicsPayload.empty(), Optional.empty(), false, Optional.of(new CicsTerminalScreen.MapScreen(SCREEN)));
        assertEquals(mapReply.screen(), ConversationCodec.decodeReply(ConversationCodec.encodeReply(mapReply)).screen());
    }

    @Test
    @DisplayName("知らない版、途中で切れた値、余ったbyteは壊れた値として断る")
    void rejectsCorruptData() {
        byte[] encoded = ConversationCodec.encodeEnvelope(envelope());
        byte[] wrongFormat = encoded.clone();
        wrongFormat[0] = 9;
        assertThrows(IllegalArgumentException.class, () -> ConversationCodec.decodeEnvelope(wrongFormat));
        assertThrows(IllegalArgumentException.class,
                () -> ConversationCodec.decodeEnvelope(Arrays.copyOf(encoded, encoded.length - 3)));
        assertThrows(IllegalArgumentException.class,
                () -> ConversationCodec.decodeEnvelope(Arrays.copyOf(encoded, encoded.length + 1)));
    }
}
