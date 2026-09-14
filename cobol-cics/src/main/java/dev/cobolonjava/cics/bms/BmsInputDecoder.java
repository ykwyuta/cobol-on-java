package dev.cobolonjava.cics.bms;

import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Justify;
import dev.cobolonjava.cics.bms.BmsModel.Mapset;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot.FieldState;
import dev.cobolonjava.runtime.codepage.CodePage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * RECEIVE MAP の分解規則 (設計 79 §8.4)。端末入力と直前の画面から入力側の記号マップを作る。
 *
 * <p>入力は直前の画面と照合して再検証する。保護 field への入力や長さ超過をブラウザの判定に
 * 任せると、HTTP 要求の改変で COBOL の記憶域へ届く (ADR-0010)。
 */
public final class BmsInputDecoder {

    /** 入力を持たない AID。端末は field のデータを送らない。 */
    private static final Set<BmsAid> SHORT_READ = Set.of(
            BmsAid.CLEAR, BmsAid.CLEAR_PARTITION, BmsAid.PA1, BmsAid.PA2, BmsAid.PA3);

    private BmsInputDecoder() {
    }

    public sealed interface Result permits Received, MapFail {
    }

    /** 分解できた。{@code symbolic} は入力側の記号マップ、{@code screen} は入力を反映した画面。 */
    public record Received(byte[] symbolic, BmsScreenSnapshot screen) implements Result {

        public Received {
            symbolic = symbolic.clone();
        }

        @Override
        public byte[] symbolic() {
            return symbolic.clone();
        }
    }

    /** 送られた field が無い (MAPFAIL)。 */
    public record MapFail() implements Result {
    }

    public static Result receive(Mapset mapset, BmsModel.Map map, BmsScreenSnapshot screen,
                                 BmsTerminalInput input, CodePage codePage) {
        Objects.requireNonNull(mapset, "mapset");
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(codePage, "codePage");
        if (!screen.mapset().equalsIgnoreCase(mapset.name()) || !screen.map().equalsIgnoreCase(map.name())) {
            throw new IllegalStateException("map " + map.name() + " is not the map on the screen ("
                    + screen.map() + ")");
        }
        int size = screen.rows() * screen.columns();
        if (input.cursorOffset() < -1 || input.cursorOffset() >= size) {
            throw new IllegalArgumentException("cursor is outside the screen: " + input.cursorOffset());
        }
        if (SHORT_READ.contains(input.aid())) {
            return new MapFail();
        }
        List<FieldState> fields = new ArrayList<>(screen.fields());
        Map<String, String> entered = new HashMap<>();
        for (BmsTerminalInput.FieldInput field : input.fields()) {
            String key = key(field.name(), field.occurrence());
            if (entered.containsKey(key)) {
                throw new IllegalArgumentException("field is sent twice: " + key);
            }
            int index = indexOf(fields, field.name(), field.occurrence());
            FieldState state = fields.get(index);
            validate(state, field.value(), key);
            entered.put(key, field.value());
            fields.set(index, state.withData(pad(field.value(), state.length(), ' ')).withModified(true));
        }
        BmsSymbolicLayout layout = BmsSymbolicLayout.of(mapset, map);
        byte[] out = new byte[layout.length()];
        boolean any = false;
        for (BmsSymbolicLayout.Slot slot : layout.slots()) {
            FieldState state = fields.get(indexOf(fields, slot.name(), slot.occurrence()));
            if (!state.modified()) {
                // 送られなかった field は L=0、F=X'00'、データは X'00' のまま
                continue;
            }
            any = true;
            String key = key(slot.name(), slot.occurrence());
            // 画面に置かれたまま送られる FSET field は、表示のために詰めた空白を送らない
            String value = entered.containsKey(key) ? entered.get(key) : state.data().stripTrailing();
            out[slot.lengthOffset()] = (byte) (value.length() >>> 8);
            out[slot.lengthOffset() + 1] = (byte) value.length();
            if (entered.containsKey(key) && value.isEmpty()) {
                out[slot.flagOffset()] = (byte) 0x80;
                continue;
            }
            byte[] data = codePage.encode(justify(value, slot.field()));
            if (data.length != slot.dataLength()) {
                throw new IllegalArgumentException("field " + key + " does not encode to one byte per character");
            }
            System.arraycopy(data, 0, out, slot.dataOffset(), data.length);
        }
        if (!any) {
            return new MapFail();
        }
        int cursor = input.cursorOffset() >= 0 ? input.cursorOffset() : screen.cursorOffset();
        // 入力を送った端末は、次に送られるまで keyboard を閉じている
        return new Received(out, new BmsScreenSnapshot(screen.mapset(), screen.map(), screen.rows(),
                screen.columns(), fields, cursor, false, false));
    }

    private static void validate(FieldState state, String value, String key) {
        if (state.attributes().contains(BasicAttribute.PROT)
                || state.attributes().contains(BasicAttribute.ASKIP)) {
            throw new IllegalArgumentException("field " + key + " is protected");
        }
        if (value.length() > state.length()) {
            throw new IllegalArgumentException("field " + key + " is longer than " + state.length());
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("field " + key + " contains control characters");
        }
        if (state.attributes().contains(BasicAttribute.NUM)
                && !value.chars().allMatch(c -> (c >= '0' && c <= '9') || c == '.' || c == '-' || c == ' ')) {
            throw new IllegalArgumentException("numeric field " + key + " accepts only digits, '.', '-' and space");
        }
    }

    /**
     * JUSTIFY に従って field の長さへ詰める。既定は左寄せで空白、RIGHT は右寄せで 0 (暫定判断 P-119)。
     */
    private static String justify(String value, BmsModel.Field field) {
        Set<Justify> justify = field.justify();
        if (justify.contains(Justify.RIGHT)) {
            char fill = justify.contains(Justify.BLANK) ? ' ' : '0';
            return String.valueOf(fill).repeat(field.length() - value.length()) + value;
        }
        char fill = justify.contains(Justify.ZERO) ? '0' : ' ';
        return pad(value, field.length(), fill);
    }

    private static String pad(String value, int length, char fill) {
        return value + String.valueOf(fill).repeat(length - value.length());
    }

    private static int indexOf(List<FieldState> fields, String name, int occurrence) {
        for (int i = 0; i < fields.size(); i++) {
            FieldState field = fields.get(i);
            if (field.name().filter(name::equalsIgnoreCase).isPresent() && field.occurrence() == occurrence) {
                return i;
            }
        }
        throw new IllegalArgumentException("field is not on the screen: " + key(name, occurrence));
    }

    private static String key(String name, int occurrence) {
        return name.toUpperCase(Locale.ROOT) + "(" + occurrence + ")";
    }
}
