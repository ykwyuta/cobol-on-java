package dev.cobolonjava.cics.bms;

import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Color;
import dev.cobolonjava.cics.bms.BmsModel.Highlight;
import dev.cobolonjava.cics.bms.BmsModel.Position;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 端末画面の中立な状態 (設計 79 §8.1、ADR-0010)。
 *
 * <p>HTML、DOM、Thymeleaf の型を含めない。疑似会話へ保存し、次の task の RECEIVE MAP と
 * Web 画面の描画の両方がこれを読む。データは端末に見える文字として持ち、code page の byte は
 * 持たない。
 *
 * @param cursorOffset 画面先頭からの cursor 位置。決まっていなければ -1
 */
public record BmsScreenSnapshot(
        String mapset,
        String map,
        int rows,
        int columns,
        List<BmsScreenSnapshot.FieldState> fields,
        int cursorOffset,
        boolean keyboardRestored,
        boolean alarm) {

    /**
     * 画面上の field 1 回分。
     *
     * @param name       記号マップの名前。固定文字の field は空
     * @param occurrence OCCURS の何回目か (1 始まり)
     * @param position   属性 byte の位置
     * @param modified   MDT。端末へ送り返す対象かどうか
     */
    public record FieldState(
            Optional<String> name,
            int occurrence,
            Position position,
            int length,
            Set<BasicAttribute> attributes,
            Optional<Color> color,
            Optional<Highlight> highlight,
            String data,
            boolean modified) {

        public FieldState {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(position, "position");
            attributes = attributes.isEmpty()
                    ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(attributes));
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(highlight, "highlight");
            Objects.requireNonNull(data, "data");
            if (data.length() != length) {
                throw new IllegalArgumentException("field data must fill the field length");
            }
        }

        FieldState withData(String value) {
            return new FieldState(name, occurrence, position, length, attributes, color,
                    highlight, value, modified);
        }

        FieldState withModified(boolean value) {
            return new FieldState(name, occurrence, position, length, attributes, color,
                    highlight, data, value);
        }

        FieldState withAttributes(Set<BasicAttribute> value, boolean mdt) {
            return new FieldState(name, occurrence, position, length, value, color,
                    highlight, data, mdt);
        }

        FieldState withColor(Optional<Color> value) {
            return new FieldState(name, occurrence, position, length, attributes, value,
                    highlight, data, modified);
        }

        FieldState withHighlight(Optional<Highlight> value) {
            return new FieldState(name, occurrence, position, length, attributes, color,
                    value, data, modified);
        }
    }

    public BmsScreenSnapshot {
        Objects.requireNonNull(mapset, "mapset");
        Objects.requireNonNull(map, "map");
        fields = List.copyOf(fields);
        if (cursorOffset < -1 || cursorOffset >= rows * columns) {
            throw new IllegalArgumentException("cursor is outside the screen: " + cursorOffset);
        }
    }

    public Optional<FieldState> field(String name, int occurrence) {
        return fields.stream()
                .filter(field -> field.name().filter(name::equalsIgnoreCase).isPresent()
                        && field.occurrence() == occurrence)
                .findFirst();
    }
}
