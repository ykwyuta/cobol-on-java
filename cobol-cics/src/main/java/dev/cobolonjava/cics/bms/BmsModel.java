package dev.cobolonjava.cics.bms;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * BMSマクロから作るフレームワーク非依存の画面定義 (要件 FR-162, NFR-036)。
 *
 * <p>HTML、DOM、Thymeleafの型をここへ持ち込まない。描画技術を替えてもCOBOLの記号マップと
 * fieldの意味論は変わらないようにするためである。
 */
public final class BmsModel {

    private BmsModel() {
    }

    /** DFHMSD TYPE。 */
    public enum GenerationType {
        /** {@code &SYSPARM}。記号マップと物理マップのどちらを作るかは組立て時に決まる。 */
        SYSPARM,
        DSECT,
        MAP
    }

    public enum Mode {
        IN, OUT, INOUT
    }

    /** DFHMSD / DFHMDI CTRL。 */
    public enum Control {
        PRINT, FREEKB, ALARM, FRSET, L40, L64, L80, HONEOM
    }

    /** DFHMSD EXTATT。 */
    public enum ExtendedAttributeSupport {
        NO, YES, MAPONLY
    }

    /**
     * 拡張属性。記号マップへ属性byteを並べる順に宣言する。
     *
     * <p>順序はBank-of-Zに同梱された、組立て済みの記号マップ写し句で観測した
     * {@code C P H V U M} に合わせた。{@code T} (TRANSP) を末尾に置くのは公開仕様からの推測で、
     * 実物とは突き合わせていない (暫定判断 P-112)。
     */
    public enum ExtendedAttribute {
        COLOR('C'), PS('P'), HILIGHT('H'), VALIDN('V'), OUTLINE('U'), SOSI('M'), TRANSP('T');

        private final char suffix;

        ExtendedAttribute(char suffix) {
            this.suffix = suffix;
        }

        /** 記号マップの項目名に付ける1文字。 */
        public char suffix() {
            return suffix;
        }
    }

    /** DFHMDF ATTRB の基本属性。 */
    public enum BasicAttribute {
        ASKIP, PROT, UNPROT, NUM, BRT, NORM, DRK, IC, FSET
    }

    public enum Color {
        DEFAULT, BLUE, RED, PINK, GREEN, TURQUOISE, YELLOW, NEUTRAL
    }

    public enum Highlight {
        OFF, BLINK, REVERSE, UNDERLINE
    }

    public enum Justify {
        LEFT, RIGHT, BLANK, ZERO
    }

    /**
     * DFHMDF SOSI。field が SBCS と DBCS の混在データを持つかどうかの宣言である。
     *
     * <p>{@code YES} は field がシフトアウト / シフトインで囲んだ DBCS を持てることを示す。
     * {@code NO} は SBCS だけを持つ field であり、DBCS の入力を断る根拠になる。
     * 書かれていない field の扱いは暫定判断 P-179 を見ること。
     */
    public enum Sosi {
        YES, NO
    }

    /** 画面上の位置。行と桁は1始まりである。 */
    public record Position(int row, int column) {
        public Position {
            if (row < 1 || column < 1) {
                throw new IllegalArgumentException("BMS position is 1-based: " + row + "," + column);
            }
        }
    }

    /**
     * 1つのfield。
     *
     * @param name       記号マップに出る名前。名前の無いfieldは固定文字であり、記号マップに出ない
     * @param position   POSに書かれた属性byteの位置。field本体はその次の桁から始まる
     * @param length     field本体の長さ。文字数ではなく画面位置 (cell) の数である
     * @param occurs     1以上。2以上なら同じ定義を横に並べる
     * @param sosi       SOSIに書かれた値。書かれていなければempty
     */
    public record Field(
            int line,
            Optional<String> name,
            Position position,
            int length,
            Set<BasicAttribute> attributes,
            Optional<Color> color,
            Optional<Highlight> highlight,
            Optional<String> initial,
            Optional<String> pictureIn,
            Optional<String> pictureOut,
            Set<Justify> justify,
            int occurs,
            Optional<Sosi> sosi) {

        public Field {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(position, "position");
            attributes = attributes.isEmpty()
                    ? Set.of() : java.util.Collections.unmodifiableSet(EnumSet.copyOf(attributes));
            justify = justify.isEmpty()
                    ? Set.of() : java.util.Collections.unmodifiableSet(EnumSet.copyOf(justify));
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(highlight, "highlight");
            Objects.requireNonNull(initial, "initial");
            Objects.requireNonNull(pictureIn, "pictureIn");
            Objects.requireNonNull(pictureOut, "pictureOut");
            Objects.requireNonNull(sosi, "sosi");
            if (length < 0) {
                throw new IllegalArgumentException("BMS field length must not be negative");
            }
            if (occurs < 1) {
                throw new IllegalArgumentException("BMS OCCURS must be positive");
            }
        }
    }

    /**
     * 1つのmap (DFHMDI)。
     *
     * @param dataAttributes 記号マップへ属性byteを並べる拡張属性。mapsetから継いだ値を含む
     */
    public record Map(
            int line,
            String name,
            int rows,
            int columns,
            Position origin,
            Set<Control> controls,
            Set<ExtendedAttribute> mapAttributes,
            Set<ExtendedAttribute> dataAttributes,
            List<Field> fields) {

        public Map {
            Objects.requireNonNull(name, "name");
            controls = copy(controls, Control.class);
            mapAttributes = copy(mapAttributes, ExtendedAttribute.class);
            dataAttributes = copy(dataAttributes, ExtendedAttribute.class);
            fields = List.copyOf(fields);
        }

        /** 記号マップに出るfield。 */
        public List<Field> namedFields() {
            return fields.stream().filter(field -> field.name().isPresent()).toList();
        }
    }

    /** 1つのmapset (DFHMSD)。 */
    public record Mapset(
            String name,
            GenerationType type,
            Mode mode,
            boolean storageAuto,
            Set<Control> controls,
            ExtendedAttributeSupport extendedAttributes,
            Optional<String> terminal,
            boolean tioaPrefix,
            List<Map> maps) {

        public Mapset {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(mode, "mode");
            controls = copy(controls, Control.class);
            Objects.requireNonNull(extendedAttributes, "extendedAttributes");
            Objects.requireNonNull(terminal, "terminal");
            maps = List.copyOf(maps);
        }

        public Optional<Map> map(String mapName) {
            return maps.stream().filter(map -> map.name().equalsIgnoreCase(mapName)).findFirst();
        }
    }

    private static <E extends Enum<E>> Set<E> copy(Set<E> values, Class<E> type) {
        return values.isEmpty()
                ? Set.of() : java.util.Collections.unmodifiableSet(EnumSet.copyOf(values));
    }
}
