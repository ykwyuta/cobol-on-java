package dev.cobolonjava.cics.bms;

import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Control;
import dev.cobolonjava.cics.bms.BmsModel.ExtendedAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Field;
import dev.cobolonjava.cics.bms.BmsModel.Mapset;
import dev.cobolonjava.cics.bms.BmsModel.Position;
import dev.cobolonjava.cics.bms.BmsScreenSnapshot.FieldState;
import dev.cobolonjava.runtime.codepage.CodePage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * SEND MAP の合成規則 (設計 79 §8.3)。記号マップと物理マップから画面状態を作る。
 *
 * <p>規則は公開仕様の記述による (V1)。画面は 1 つの map を単位に持つ。別の map の上に
 * ERASE なしで重ねる形は、画面の field が map をまたいで混ざるので現状は断る。
 */
public final class BmsScreenComposer {

    private BmsScreenComposer() {
    }

    /**
     * SEND MAP の option。
     *
     * @param cursor          {@code CURSOR(n)} の位置
     * @param symbolicCursor  値を持たない {@code CURSOR}。L に -1 を置いた field へ置く
     */
    public record SendOptions(boolean erase, boolean mapOnly, boolean dataOnly, boolean freeKeyboard,
                              boolean alarm, boolean resetModified, OptionalInt cursor,
                              boolean symbolicCursor) {

        public SendOptions {
            Objects.requireNonNull(cursor, "cursor");
            if (mapOnly && dataOnly) {
                throw new IllegalArgumentException("MAPONLY and DATAONLY are mutually exclusive");
            }
            if (cursor.isPresent() && symbolicCursor) {
                throw new IllegalArgumentException("CURSOR is specified twice");
            }
        }
    }

    public static BmsScreenSnapshot send(
            Mapset mapset, BmsModel.Map map, Optional<BmsScreenSnapshot> current,
            byte[] symbolic, SendOptions options, CodePage codePage) {
        Objects.requireNonNull(mapset, "mapset");
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(codePage, "codePage");
        if (map.origin().row() != 1 || map.origin().column() != 1) {
            throw new IllegalStateException("maps placed away from line 1, column 1 are not supported yet");
        }
        boolean sameMap = current.filter(screen -> screen.mapset().equalsIgnoreCase(mapset.name())
                && screen.map().equalsIgnoreCase(map.name())).isPresent();
        List<FieldState> fields;
        if (options.dataOnly()) {
            if (!sameMap) {
                throw new IllegalStateException("DATAONLY requires map " + map.name()
                        + " to be on the screen");
            }
            fields = new ArrayList<>(current.orElseThrow().fields());
        } else {
            if (!options.erase() && current.isPresent() && !sameMap) {
                throw new IllegalStateException(
                        "sending a different map without ERASE is not supported yet");
            }
            fields = fromDefinition(map, codePage);
        }
        if (options.resetModified()) {
            fields.replaceAll(field -> field.withModified(false));
        }
        int cursor = -1;
        if (!options.mapOnly()) {
            BmsSymbolicLayout layout = BmsSymbolicLayout.of(mapset, map);
            if (symbolic == null || symbolic.length != layout.length()) {
                throw new IllegalStateException("symbolic map length "
                        + (symbolic == null ? "none" : symbolic.length)
                        + " does not match map " + map.name() + " (" + layout.length() + ")");
            }
            cursor = applySymbolic(layout, map, symbolic, fields, options.symbolicCursor(), codePage);
        }
        int size = map.rows() * map.columns();
        if (options.cursor().isPresent()) {
            cursor = options.cursor().getAsInt();
            if (cursor < 0 || cursor >= size) {
                throw new IllegalStateException("CURSOR is outside the screen: " + cursor);
            }
        } else if (cursor < 0) {
            cursor = options.dataOnly()
                    ? current.orElseThrow().cursorOffset()
                    : initialCursor(map);
        }
        Set<Control> controls = EnumSet.noneOf(Control.class);
        controls.addAll(mapset.controls());
        controls.addAll(map.controls());
        return new BmsScreenSnapshot(mapset.name(), map.name(), map.rows(), map.columns(), fields,
                cursor,
                options.freeKeyboard() || controls.contains(Control.FREEKB),
                options.alarm() || controls.contains(Control.ALARM));
    }

    /**
     * SEND CONTROL を map の画面へ効かせる (設計 79 §8.5)。画面の内容は変えない。
     */
    public static BmsScreenSnapshot control(
            BmsScreenSnapshot current, boolean freeKeyboard, boolean alarm,
            boolean resetModified, OptionalInt cursor) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(cursor, "cursor");
        List<FieldState> fields = new ArrayList<>(current.fields());
        if (resetModified) {
            fields.replaceAll(field -> field.withModified(false));
        }
        int position = cursor.orElse(current.cursorOffset());
        if (position < -1 || position >= current.rows() * current.columns()) {
            throw new IllegalStateException("CURSOR is outside the screen: " + position);
        }
        return new BmsScreenSnapshot(current.mapset(), current.map(), current.rows(),
                current.columns(), fields, position,
                current.keyboardRestored() || freeKeyboard, current.alarm() || alarm);
    }

    private static List<FieldState> fromDefinition(BmsModel.Map map, CodePage codePage) {
        List<FieldState> out = new ArrayList<>();
        for (Field field : map.fields()) {
            for (int occurrence = 1; occurrence <= field.occurs(); occurrence++) {
                // LENGTH より長い INITIAL は画面へ出すときに LENGTH で切る (暫定判断 P-112)
                String initial = field.initial().orElse("");
                String data = initial.length() >= field.length()
                        ? initial.substring(0, field.length())
                        : initial + " ".repeat(field.length() - initial.length());
                out.add(new FieldState(field.name(), occurrence,
                        positionOf(map, field, occurrence), field.length(),
                        field.attributes(), field.color(), field.highlight(), data,
                        field.attributes().contains(BasicAttribute.FSET)));
            }
        }
        return out;
    }

    /** @return 記号 cursor を置いた位置。置かなければ -1 */
    private static int applySymbolic(
            BmsSymbolicLayout layout, BmsModel.Map map, byte[] symbolic, List<FieldState> fields,
            boolean symbolicCursor, CodePage codePage) {
        int cursor = -1;
        for (BmsSymbolicLayout.Slot slot : layout.slots()) {
            int index = indexOf(fields, slot.name(), slot.occurrence());
            FieldState state = fields.get(index);
            int length = (short) ((symbolic[slot.lengthOffset()] & 0xFF) << 8
                    | symbolic[slot.lengthOffset() + 1] & 0xFF);
            if (symbolicCursor && cursor < 0 && length == -1) {
                cursor = dataOffset(map, state.position());
            }
            int attribute = symbolic[slot.flagOffset()] & 0xFF;
            if (attribute != 0) {
                BmsAttributeCodes.Basic basic = BmsAttributeCodes.basic(attribute);
                state = state.withAttributes(basic.attributes(), basic.modified());
            }
            for (ExtendedAttribute extended : layout.attributeOrder()) {
                int value = symbolic[layout.attributeOffset(slot, extended).orElseThrow()] & 0xFF;
                if (value == 0) {
                    continue;
                }
                switch (extended) {
                    case COLOR -> state = state.withColor(BmsAttributeCodes.color(value));
                    case HILIGHT -> state = state.withHighlight(BmsAttributeCodes.highlight(value));
                    default -> throw new IllegalArgumentException(String.format(
                            "extended attribute %s value X'%02X' is not supported yet",
                            extended, value));
                }
            }
            // データの先頭が X'00' なら送らない。物理マップ (または画面) の値が残る
            if (symbolic[slot.dataOffset()] != 0) {
                byte[] bytes = new byte[slot.dataLength()];
                System.arraycopy(symbolic, slot.dataOffset(), bytes, 0, bytes.length);
                String text = codePage.decode(bytes).replace(' ', ' ');
                if (text.length() != state.length()) {
                    throw new IllegalStateException("field " + slot.name()
                            + " does not decode to one character per byte");
                }
                state = state.withData(text);
            }
            fields.set(index, state);
        }
        return cursor;
    }

    private static int indexOf(List<FieldState> fields, String name, int occurrence) {
        for (int i = 0; i < fields.size(); i++) {
            FieldState field = fields.get(i);
            if (field.name().filter(name::equals).isPresent() && field.occurrence() == occurrence) {
                return i;
            }
        }
        throw new IllegalStateException("field " + name + "(" + occurrence + ") is not on the screen");
    }

    /** IC を持つ最初の field のデータ位置。無ければ画面の先頭。 */
    private static int initialCursor(BmsModel.Map map) {
        for (Field field : map.fields()) {
            if (field.attributes().contains(BasicAttribute.IC)) {
                return dataOffset(map, positionOf(map, field, 1));
            }
        }
        return 0;
    }

    /** OCCURS の n 回目は、属性 byte 1 桁とデータを 1 組として横へ並ぶ。 */
    static Position positionOf(BmsModel.Map map, Field field, int occurrence) {
        int start = (field.position().row() - 1) * map.columns() + field.position().column() - 1
                + (occurrence - 1) * (field.length() + 1);
        return new Position(start / map.columns() + 1, start % map.columns() + 1);
    }

    private static int dataOffset(BmsModel.Map map, Position attribute) {
        int size = map.rows() * map.columns();
        return ((attribute.row() - 1) * map.columns() + attribute.column()) % size;
    }
}
