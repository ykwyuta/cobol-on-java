package dev.cobolonjava.cics;

import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Color;
import dev.cobolonjava.cics.bms.BmsModel.Highlight;
import dev.cobolonjava.cics.bms.BmsModel.Position;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 会話の envelope と task の応答を、表の列に置ける byte 列にする (暫定判断 P-143)。
 *
 * <p>Java の直列化は使わない。読むときに任意の class を作らせないためである。形は先頭の版の byte で見分け、
 * 知らない版や余った byte は壊れた値として断る。列挙の値は順番ではなく名前で持ち、定義の並び替えで意味が変わらないようにする。
 */
public final class ConversationCodec {

    private static final int FORMAT = 1;
    private static final int MAX_LENGTH = 16 * 1024 * 1024;

    private ConversationCodec() {
    }

    public static byte[] encodeEnvelope(ConversationEnvelope envelope) {
        return write(out -> envelope(out, envelope));
    }

    public static ConversationEnvelope decodeEnvelope(byte[] bytes) {
        return read(bytes, ConversationCodec::envelope);
    }

    public static byte[] encodeReply(CicsTaskReply reply) {
        return write(out -> {
            string(out, reply.taskId().value());
            string(out, reply.transactionId().value());
            payload(out, reply.payload());
            out.writeBoolean(reply.nextConversation().isPresent());
            if (reply.nextConversation().isPresent()) {
                envelope(out, reply.nextConversation().orElseThrow());
            }
            out.writeBoolean(reply.immediateNext());
            out.writeBoolean(reply.screen().isPresent());
            if (reply.screen().isPresent()) {
                terminalScreen(out, reply.screen().orElseThrow());
            }
        });
    }

    public static CicsTaskReply decodeReply(byte[] bytes) {
        return read(bytes, in -> {
            CicsTaskId taskId = new CicsTaskId(string(in));
            TransId transaction = TransId.of(string(in));
            CicsPayload payload = payload(in);
            Optional<ConversationEnvelope> next = in.readBoolean() ? Optional.of(envelope(in)) : Optional.empty();
            boolean immediate = in.readBoolean();
            Optional<CicsTerminalScreen> screen = in.readBoolean()
                    ? Optional.of(terminalScreen(in)) : Optional.empty();
            return new CicsTaskReply(taskId, transaction, payload, next, immediate, screen);
        });
    }

    /** 端末の現在の画面 (設計 83 §7)。 */
    public static byte[] encodeScreen(CicsTerminalScreen screen) {
        return write(out -> terminalScreen(out, screen));
    }

    public static CicsTerminalScreen decodeScreen(byte[] bytes) {
        return read(bytes, ConversationCodec::terminalScreen);
    }

    // ---- 部分 ----

    private static void envelope(DataOutputStream out, ConversationEnvelope envelope) throws IOException {
        string(out, envelope.id().value());
        out.writeLong(envelope.version());
        string(out, envelope.owner());
        string(out, envelope.nextTransaction().value());
        payload(out, envelope.payload());
        out.writeLong(envelope.expiresAt().getEpochSecond());
        out.writeInt(envelope.expiresAt().getNano());
        string(out, envelope.idempotencyKey().value());
        optionalString(out, envelope.lastOutcome());
        out.writeBoolean(envelope.screen().isPresent());
        if (envelope.screen().isPresent()) {
            snapshot(out, envelope.screen().orElseThrow());
        }
    }

    private static ConversationEnvelope envelope(DataInputStream in) throws IOException {
        ConversationId id = new ConversationId(string(in));
        long version = in.readLong();
        String owner = string(in);
        TransId next = TransId.of(string(in));
        CicsPayload payload = payload(in);
        Instant expiresAt = Instant.ofEpochSecond(in.readLong(), in.readInt());
        IdempotencyKey key = new IdempotencyKey(string(in));
        Optional<String> outcome = optionalString(in);
        Optional<BmsScreenSnapshot> screen = in.readBoolean() ? Optional.of(snapshot(in)) : Optional.empty();
        return new ConversationEnvelope(id, version, owner, next, payload, expiresAt, key, outcome, screen);
    }

    private static void payload(DataOutputStream out, CicsPayload payload) throws IOException {
        bytes(out, payload.commarea());
        Map<String, byte[]> containers = payload.containers();
        out.writeInt(containers.size());
        for (Map.Entry<String, byte[]> entry : new java.util.TreeMap<>(containers).entrySet()) {
            string(out, entry.getKey());
            bytes(out, entry.getValue());
        }
        optionalString(out, payload.channelName());
    }

    private static CicsPayload payload(DataInputStream in) throws IOException {
        byte[] commarea = bytes(in);
        int count = count(in);
        Map<String, byte[]> containers = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            containers.put(string(in), bytes(in));
        }
        return new CicsPayload(commarea, containers, optionalString(in).orElse(null));
    }

    private static void terminalScreen(DataOutputStream out, CicsTerminalScreen screen) throws IOException {
        switch (screen) {
            case CicsTerminalScreen.MapScreen map -> {
                out.writeByte(0);
                snapshot(out, map.snapshot());
            }
            case CicsTerminalScreen.TextScreen text -> {
                out.writeByte(1);
                string(out, text.text());
                out.writeBoolean(text.keyboardRestored());
                out.writeBoolean(text.alarm());
            }
        }
    }

    private static CicsTerminalScreen terminalScreen(DataInputStream in) throws IOException {
        return switch (in.readByte()) {
            case 0 -> new CicsTerminalScreen.MapScreen(snapshot(in));
            case 1 -> new CicsTerminalScreen.TextScreen(string(in), in.readBoolean(), in.readBoolean());
            default -> throw new IllegalArgumentException("unknown terminal screen kind");
        };
    }

    private static void snapshot(DataOutputStream out, BmsScreenSnapshot snapshot) throws IOException {
        string(out, snapshot.mapset());
        string(out, snapshot.map());
        out.writeInt(snapshot.rows());
        out.writeInt(snapshot.columns());
        out.writeInt(snapshot.fields().size());
        for (BmsScreenSnapshot.FieldState field : snapshot.fields()) {
            optionalString(out, field.name());
            out.writeInt(field.occurrence());
            out.writeInt(field.position().row());
            out.writeInt(field.position().column());
            out.writeInt(field.length());
            out.writeInt(field.attributes().size());
            for (BasicAttribute attribute : field.attributes()) {
                string(out, attribute.name());
            }
            optionalString(out, field.color().map(Enum::name));
            optionalString(out, field.highlight().map(Enum::name));
            string(out, field.data());
            out.writeBoolean(field.modified());
        }
        out.writeInt(snapshot.cursorOffset());
        out.writeBoolean(snapshot.keyboardRestored());
        out.writeBoolean(snapshot.alarm());
    }

    private static BmsScreenSnapshot snapshot(DataInputStream in) throws IOException {
        String mapset = string(in);
        String map = string(in);
        int rows = in.readInt();
        int columns = in.readInt();
        int count = count(in);
        List<BmsScreenSnapshot.FieldState> fields = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Optional<String> name = optionalString(in);
            int occurrence = in.readInt();
            Position position = new Position(in.readInt(), in.readInt());
            int length = in.readInt();
            int attributeCount = count(in);
            Set<BasicAttribute> attributes = EnumSet.noneOf(BasicAttribute.class);
            for (int a = 0; a < attributeCount; a++) {
                attributes.add(BasicAttribute.valueOf(string(in)));
            }
            Optional<Color> color = optionalString(in).map(Color::valueOf);
            Optional<Highlight> highlight = optionalString(in).map(Highlight::valueOf);
            String data = string(in);
            boolean modified = in.readBoolean();
            fields.add(new BmsScreenSnapshot.FieldState(name, occurrence, position, length, attributes, color,
                    highlight, data, modified));
        }
        return new BmsScreenSnapshot(mapset, map, rows, columns, fields, in.readInt(), in.readBoolean(),
                in.readBoolean());
    }

    // ---- 値 ----

    private static void string(DataOutputStream out, String value) throws IOException {
        bytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String string(DataInputStream in) throws IOException {
        return new String(bytes(in), StandardCharsets.UTF_8);
    }

    private static void optionalString(DataOutputStream out, Optional<String> value) throws IOException {
        out.writeBoolean(value.isPresent());
        if (value.isPresent()) {
            string(out, value.orElseThrow());
        }
    }

    private static Optional<String> optionalString(DataInputStream in) throws IOException {
        return in.readBoolean() ? Optional.of(string(in)) : Optional.empty();
    }

    private static void bytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] bytes(DataInputStream in) throws IOException {
        return in.readNBytes(checkedLength(in.readInt(), in));
    }

    private static int count(DataInputStream in) throws IOException {
        return checkedLength(in.readInt(), in);
    }

    /** 長さが負か、残りの byte より多ければ壊れた値である。 */
    private static int checkedLength(int length, DataInputStream in) throws IOException {
        if (length < 0 || length > MAX_LENGTH || length > in.available()) {
            throw new IllegalArgumentException("encoded conversation data is corrupt");
        }
        return length;
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(DataInputStream in) throws IOException;
    }

    private static byte[] write(Writer writer) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(FORMAT);
            writer.write(out);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return buffer.toByteArray();
    }

    private static <T> T read(byte[] bytes, Reader<T> reader) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readByte() != FORMAT) {
                throw new IllegalArgumentException("unsupported encoded conversation format");
            }
            T value = reader.read(in);
            if (in.available() != 0) {
                throw new IllegalArgumentException("encoded conversation data has trailing bytes");
            }
            return value;
        } catch (IOException truncated) {
            throw new IllegalArgumentException("encoded conversation data is truncated", truncated);
        }
    }
}
