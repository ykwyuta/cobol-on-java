package dev.cobolonjava.cics.bms;

import dev.cobolonjava.cics.bms.BmsMacroReader.Quoted;
import dev.cobolonjava.cics.bms.BmsMacroReader.Statement;
import dev.cobolonjava.cics.bms.BmsMacroReader.Sublist;
import dev.cobolonjava.cics.bms.BmsMacroReader.Value;
import dev.cobolonjava.cics.bms.BmsMacroReader.Word;
import dev.cobolonjava.cics.bms.BmsModel.BasicAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Color;
import dev.cobolonjava.cics.bms.BmsModel.Control;
import dev.cobolonjava.cics.bms.BmsModel.ExtendedAttribute;
import dev.cobolonjava.cics.bms.BmsModel.ExtendedAttributeSupport;
import dev.cobolonjava.cics.bms.BmsModel.Field;
import dev.cobolonjava.cics.bms.BmsModel.GenerationType;
import dev.cobolonjava.cics.bms.BmsModel.Highlight;
import dev.cobolonjava.cics.bms.BmsModel.Justify;
import dev.cobolonjava.cics.bms.BmsModel.Mapset;
import dev.cobolonjava.cics.bms.BmsModel.Mode;
import dev.cobolonjava.cics.bms.BmsModel.Position;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * BMSマクロ (DFHMSD / DFHMDI / DFHMDF) を中立モデルへ変換する (要件 FR-162)。
 *
 * <p>対象はCOBOL向けmapsetの初期subsetである。知らないoperandや値は黙って捨てず、
 * 行番号つきで断る。捨てた属性は画面の振る舞いから静かに消え、後で見つけられないからである。
 */
public final class BmsParser {

    private static final Pattern MAP_NAME = Pattern.compile("[A-Z][A-Z0-9]{0,6}");
    /** 記号マップでは1文字の接尾辞が付くので、COBOLの語の上限30から1を引く。 */
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Z][A-Z0-9]{0,28}");
    private static final Pattern PICTURE = Pattern.compile("[0-9A-Z().,$+*/-]{1,30}");

    /**
     * EXTATT=YESでMAPATTS / DSATTSを書かなかったときの拡張属性。
     *
     * <p>公開仕様の記述による。実機の組立て結果とは突き合わせていない (暫定判断 P-112)。
     */
    private static final Set<ExtendedAttribute> EXTATT_YES = EnumSet.of(
            ExtendedAttribute.COLOR, ExtendedAttribute.HILIGHT,
            ExtendedAttribute.PS, ExtendedAttribute.VALIDN);

    private BmsParser() {
    }

    public static Mapset parse(String source) {
        Objects.requireNonNull(source, "source");
        return new Builder().build(BmsMacroReader.read(source));
    }

    /** DFHMSDとDFHMDIが共有する、fieldへ継ぐ既定値。 */
    private record Defaults(Optional<Color> color, Optional<Highlight> highlight,
                            Set<ExtendedAttribute> mapAttributes,
                            Set<ExtendedAttribute> dataAttributes) {
    }

    private static final class Builder {

        private String mapsetName;
        private GenerationType type;
        private Mode mode;
        private boolean storageAuto;
        private Set<Control> mapsetControls = Set.of();
        private ExtendedAttributeSupport extendedAttributes;
        private Optional<String> terminal = Optional.empty();
        private boolean tioaPrefix;
        private Defaults mapsetDefaults;
        private final List<BmsModel.Map> maps = new ArrayList<>();

        private Statement mapStatement;
        private Defaults mapDefaults;
        private Set<Control> mapControls;
        private int rows;
        private int columns;
        private Position origin;
        private final List<Field> fields = new ArrayList<>();
        private boolean finalSeen;
        private boolean endSeen;

        Mapset build(List<Statement> statements) {
            for (Statement statement : statements) {
                if (endSeen) {
                    throw fail(statement, "statement after END");
                }
                switch (statement.operation()) {
                    case "DFHMSD" -> mapset(statement);
                    case "DFHMDI" -> map(statement);
                    case "DFHMDF" -> field(statement);
                    case "END" -> endSeen = true;
                    // 印刷の制御だけで、画面の意味を持たない
                    case "PRINT", "TITLE", "EJECT", "SPACE" -> { }
                    default -> throw fail(statement,
                            "unsupported BMS statement " + statement.operation());
                }
            }
            if (mapsetName == null) {
                throw new BmsDefinitionException(1, "DFHMSD is missing");
            }
            if (!finalSeen) {
                throw new BmsDefinitionException(1, "DFHMSD TYPE=FINAL is missing");
            }
            if (maps.isEmpty()) {
                throw new BmsDefinitionException(1, "mapset " + mapsetName + " has no DFHMDI");
            }
            return new Mapset(mapsetName, type, mode, storageAuto, mapsetControls,
                    extendedAttributes, terminal, tioaPrefix, maps);
        }

        private void mapset(Statement statement) {
            String typeValue = word(statement, "TYPE");
            if ("FINAL".equals(typeValue)) {
                only(statement, Set.of("TYPE"));
                if (mapsetName == null || finalSeen) {
                    throw fail(statement, "TYPE=FINAL without an open mapset");
                }
                closeMap();
                finalSeen = true;
                return;
            }
            if (mapsetName != null) {
                throw fail(statement, "only one mapset per source is supported");
            }
            only(statement, Set.of("TYPE", "MODE", "LANG", "STORAGE", "CTRL", "EXTATT",
                    "TERM", "TIOAPFX", "MAPATTS", "DSATTS", "COLOR", "HILIGHT"));
            mapsetName = name(statement, MAP_NAME, "mapset");
            if (typeValue == null) {
                throw fail(statement, "DFHMSD requires TYPE");
            }
            type = switch (typeValue) {
                case "&SYSPARM" -> GenerationType.SYSPARM;
                case "DSECT" -> GenerationType.DSECT;
                case "MAP" -> GenerationType.MAP;
                default -> throw fail(statement, "unsupported TYPE=" + typeValue);
            };
            mode = enumValue(statement, "MODE", Mode.class).orElse(Mode.OUT);
            String language = Optional.ofNullable(word(statement, "LANG")).orElse("ASM");
            if (!language.equals("COBOL")) {
                // 記号マップをCOBOLの写し句として作るので、他言語の配置規則は持たない
                throw fail(statement, "only LANG=COBOL is supported, got LANG=" + language);
            }
            String storage = word(statement, "STORAGE");
            if (storage != null && !storage.equals("AUTO")) {
                throw fail(statement, "unsupported STORAGE=" + storage);
            }
            storageAuto = storage != null;
            mapsetControls = enumList(statement, "CTRL", Control.class);
            extendedAttributes = enumValue(statement, "EXTATT", ExtendedAttributeSupport.class)
                    .orElse(ExtendedAttributeSupport.NO);
            terminal = Optional.ofNullable(word(statement, "TERM"));
            String prefix = word(statement, "TIOAPFX");
            if (prefix == null) {
                // 省略時の既定をhostで確かめていない。12byteの有無で全fieldの位置がずれるので推測しない
                throw fail(statement, "TIOAPFX must be specified explicitly");
            }
            tioaPrefix = switch (prefix) {
                case "YES" -> true;
                case "NO" -> false;
                default -> throw fail(statement, "unsupported TIOAPFX=" + prefix);
            };
            mapsetDefaults = defaults(statement, new Defaults(Optional.empty(), Optional.empty(),
                    extendedAttributes == ExtendedAttributeSupport.NO ? Set.of() : EXTATT_YES,
                    extendedAttributes == ExtendedAttributeSupport.YES ? EXTATT_YES : Set.of()));
        }

        private void map(Statement statement) {
            requireOpenMapset(statement);
            closeMap();
            only(statement, Set.of("SIZE", "LINE", "COLUMN", "CTRL", "MAPATTS", "DSATTS",
                    "COLOR", "HILIGHT"));
            name(statement, MAP_NAME, "map");
            int[] size = pair(statement, "SIZE");
            if (size == null) {
                throw fail(statement, "DFHMDI requires SIZE=(rows,columns)");
            }
            rows = size[0];
            columns = size[1];
            origin = new Position(number(statement, "LINE", 1), number(statement, "COLUMN", 1));
            mapControls = enumList(statement, "CTRL", Control.class);
            mapDefaults = defaults(statement, mapsetDefaults);
            mapStatement = statement;
        }

        private void field(Statement statement) {
            if (mapStatement == null) {
                throw fail(statement, "DFHMDF outside DFHMDI");
            }
            only(statement, Set.of("POS", "LENGTH", "ATTRB", "COLOR", "HILIGHT", "INITIAL",
                    "PICIN", "PICOUT", "JUSTIFY", "OCCURS"));
            Optional<String> fieldName = statement.label() == null
                    ? Optional.empty() : Optional.of(name(statement, FIELD_NAME, "field"));
            int[] pos = pair(statement, "POS");
            if (pos == null) {
                throw fail(statement, "DFHMDF requires POS=(line,column)");
            }
            if (pos[0] > rows || pos[1] > columns) {
                throw fail(statement, "POS=(" + pos[0] + "," + pos[1]
                        + ") is outside the map size " + rows + "x" + columns);
            }
            Position position = new Position(pos[0], pos[1]);
            if (!statement.keywords().containsKey("LENGTH")) {
                throw fail(statement, "DFHMDF requires LENGTH");
            }
            int length = number(statement, "LENGTH", 0);
            int occurs = number(statement, "OCCURS", 1);
            if (occurs > 1 && fieldName.isEmpty()) {
                throw fail(statement, "OCCURS requires a field name");
            }
            // 属性byteも1桁を占める。画面の終わりを越えるfieldは組み立てられない
            int start = (pos[0] - 1) * columns + (pos[1] - 1);
            if (start + (long) (length + 1) * occurs > (long) rows * columns) {
                throw fail(statement, "field extends beyond the end of the map");
            }
            Set<BasicAttribute> attributes = attributes(statement);
            // LENGTHより長いINITIALも、hostで組み立てて動いている資産にある (BNK1MAI)。
            // 断らずに書かれたとおり保持し、画面へ出すときにLENGTHで切る (暫定判断 P-112)
            Optional<String> initial = quoted(statement, "INITIAL");
            Optional<String> pictureIn = picture(statement, "PICIN");
            Optional<String> pictureOut = picture(statement, "PICOUT");
            Optional<Color> color = enumValue(statement, "COLOR", Color.class)
                    .or(mapDefaults::color);
            Optional<Highlight> highlight = enumValue(statement, "HILIGHT", Highlight.class)
                    .or(mapDefaults::highlight);
            Set<Justify> justify = enumList(statement, "JUSTIFY", Justify.class);
            fields.add(new Field(statement.line(), fieldName, position, length, attributes,
                    color, highlight, initial, pictureIn, pictureOut, justify, occurs));
        }

        private Defaults defaults(Statement statement, Defaults inherited) {
            Optional<Set<ExtendedAttribute>> mapAttributes =
                    optionalEnumList(statement, "MAPATTS", ExtendedAttribute.class);
            Optional<Set<ExtendedAttribute>> dataAttributes =
                    optionalEnumList(statement, "DSATTS", ExtendedAttribute.class);
            Set<ExtendedAttribute> effectiveData = dataAttributes.orElse(inherited.dataAttributes());
            // DSATTSだけを書いたときは、同じ属性を物理マップにも持たせる
            Set<ExtendedAttribute> effectiveMap = mapAttributes.orElseGet(() ->
                    dataAttributes.isPresent() ? dataAttributes.get() : inherited.mapAttributes());
            if (!effectiveMap.containsAll(effectiveData)) {
                throw fail(statement, "DSATTS must be a subset of MAPATTS");
            }
            return new Defaults(
                    enumValue(statement, "COLOR", Color.class).or(inherited::color),
                    enumValue(statement, "HILIGHT", Highlight.class).or(inherited::highlight),
                    effectiveMap, effectiveData);
        }

        /**
         * ATTRBを実効値へ正規化する。
         *
         * <p>保護もintensityも書かなければ、公開仕様どおりASKIPとNORMを補う。保護だけを
         * 書かなければUNPROT、intensityだけを書かなければNORMを補う。ASKIPはPROTを含むので
         * 両方の指定を認める。資産には同じ語の重複もあるため、重複は1つに畳む。
         */
        private Set<BasicAttribute> attributes(Statement statement) {
            Set<BasicAttribute> written = enumList(statement, "ATTRB", BasicAttribute.class);
            EnumSet<BasicAttribute> effective = written.isEmpty()
                    ? EnumSet.noneOf(BasicAttribute.class) : EnumSet.copyOf(written);
            if (!statement.keywords().containsKey("ATTRB")) {
                effective.add(BasicAttribute.ASKIP);
            }
            boolean askip = effective.contains(BasicAttribute.ASKIP);
            boolean prot = effective.contains(BasicAttribute.PROT);
            boolean unprot = effective.contains(BasicAttribute.UNPROT);
            if (unprot && (askip || prot)) {
                throw fail(statement, "UNPROT conflicts with ASKIP or PROT");
            }
            if (!askip && !prot && !unprot) {
                effective.add(BasicAttribute.UNPROT);
            }
            int intensities = (effective.contains(BasicAttribute.BRT) ? 1 : 0)
                    + (effective.contains(BasicAttribute.NORM) ? 1 : 0)
                    + (effective.contains(BasicAttribute.DRK) ? 1 : 0);
            if (intensities > 1) {
                throw fail(statement, "BRT, NORM and DRK are mutually exclusive");
            }
            if (intensities == 0) {
                effective.add(BasicAttribute.NORM);
            }
            return effective;
        }

        private void closeMap() {
            if (mapStatement == null) {
                return;
            }
            maps.add(new BmsModel.Map(mapStatement.line(), mapStatement.label(), rows, columns,
                    origin, mapControls, mapDefaults.mapAttributes(),
                    mapDefaults.dataAttributes(), fields));
            fields.clear();
            mapStatement = null;
        }

        private void requireOpenMapset(Statement statement) {
            if (mapsetName == null || finalSeen) {
                throw fail(statement, statement.operation() + " outside DFHMSD");
            }
        }

        private String name(Statement statement, Pattern pattern, String kind) {
            String label = statement.label();
            if (label == null) {
                throw fail(statement, statement.operation() + " requires a " + kind + " name");
            }
            if (!pattern.matcher(label).matches()) {
                throw fail(statement, "invalid " + kind + " name " + label);
            }
            return label;
        }

        private Optional<String> picture(Statement statement, String key) {
            Optional<String> value = quoted(statement, key);
            if (value.isPresent() && !PICTURE.matcher(value.get()).matches()) {
                throw fail(statement, "unsupported " + key + " picture");
            }
            return value;
        }
    }

    private static void only(Statement statement, Set<String> allowed) {
        for (String key : statement.keywords().keySet()) {
            if (!allowed.contains(key)) {
                throw fail(statement,
                        "unsupported " + statement.operation() + " operand " + key);
            }
        }
        if (!statement.positionals().isEmpty()) {
            throw fail(statement, "unsupported positional operand "
                    + statement.positionals().get(0));
        }
    }

    private static String word(Statement statement, String key) {
        Value value = statement.keywords().get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Word word) {
            return word.text();
        }
        throw fail(statement, key + " must be a single value");
    }

    private static Optional<String> quoted(Statement statement, String key) {
        Value value = statement.keywords().get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Quoted quoted) {
            return Optional.of(quoted.text());
        }
        throw fail(statement, key + " must be a quoted string");
    }

    private static int number(Statement statement, String key, int fallback) {
        String text = word(statement, key);
        if (text == null) {
            return fallback;
        }
        return parseNumber(statement, key, text);
    }

    private static int parseNumber(Statement statement, String key, String text) {
        if (!text.matches("\\d{1,5}")) {
            throw fail(statement, key + " must be a number, got " + text);
        }
        return Integer.parseInt(text);
    }

    private static int[] pair(Statement statement, String key) {
        Value value = statement.keywords().get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Sublist list) || list.items().size() != 2) {
            // POS=数値 (bufferの相対位置) の形はまだ扱わない
            throw fail(statement, key + " must be written as (line,column)");
        }
        int first = parseNumber(statement, key, list.items().get(0));
        int second = parseNumber(statement, key, list.items().get(1));
        if (first < 1 || second < 1) {
            throw fail(statement, key + " values are 1-based");
        }
        return new int[] {first, second};
    }

    private static <E extends Enum<E>> Optional<E> enumValue(
            Statement statement, String key, Class<E> type) {
        String text = word(statement, key);
        if (text == null) {
            return Optional.empty();
        }
        return Optional.of(constant(statement, key, type, text));
    }

    private static <E extends Enum<E>> Set<E> enumList(
            Statement statement, String key, Class<E> type) {
        return optionalEnumList(statement, key, type).orElse(Set.of());
    }

    private static <E extends Enum<E>> Optional<Set<E>> optionalEnumList(
            Statement statement, String key, Class<E> type) {
        Value value = statement.keywords().get(key);
        if (value == null) {
            return Optional.empty();
        }
        List<String> items = switch (value) {
            case Word word -> List.of(word.text());
            case Sublist list -> list.items();
            case Quoted ignored -> throw fail(statement, key + " must not be quoted");
        };
        EnumSet<E> out = EnumSet.noneOf(type);
        for (String item : items) {
            out.add(constant(statement, key, type, item));
        }
        return Optional.of(out);
    }

    private static <E extends Enum<E>> E constant(
            Statement statement, String key, Class<E> type, String text) {
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException unknown) {
            throw fail(statement, "unsupported " + key + "=" + text);
        }
    }

    private static BmsDefinitionException fail(Statement statement, String message) {
        return new BmsDefinitionException(statement.line(), message);
    }
}
