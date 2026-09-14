package dev.cobolonjava.cics.bms;

import dev.cobolonjava.cics.bms.BmsModel.ExtendedAttribute;
import dev.cobolonjava.cics.bms.BmsModel.Field;
import dev.cobolonjava.cics.bms.BmsModel.Mapset;
import dev.cobolonjava.cics.bms.BmsModel.Mode;
import java.util.Objects;
import java.util.Set;

/**
 * mapsetからCOBOLの記号マップ写し句を作る。
 *
 * <p>形はBank-of-Zに同梱された、組立て済みの記号マップ写し句で観測したものに合わせる。
 * 入力側は名前つきfieldごとに長さ (L)、flag (F)、それを再定義する属性 (A)、拡張属性の数だけの
 * FILLER、データ (I) を並べる。出力側は同じ領域を再定義し、L と F の3byteを飛ばして
 * 拡張属性byteとデータ (O) を並べる。
 *
 * <p>OCCURSの形は観測した実物がなく、公開仕様の記述から起こした推測である (暫定判断 P-112)。
 */
public final class BmsSymbolicMapWriter {

    private static final String AREA_A = "       ";
    private static final String LEVEL_02 = "           02  ";
    private static final String LEVEL_03 = "             03 ";
    private static final String LEVEL_04 = "               04 ";
    /** 固定形式の本文が使える最後の桁。 */
    private static final int LAST_COLUMN = 72;
    /** TIOAPFX=YESで先頭に置かれる領域の長さ。 */
    private static final int TIOA_PREFIX_LENGTH = 12;

    private BmsSymbolicMapWriter() {
    }

    public static String cobol(Mapset mapset) {
        Objects.requireNonNull(mapset, "mapset");
        Writer writer = new Writer();
        int groups = 0;
        for (BmsModel.Map map : mapset.maps()) {
            groups = writer.map(mapset, map, groups);
        }
        return writer.out.toString();
    }

    private static final class Writer {

        private final StringBuilder out = new StringBuilder();

        int map(Mapset mapset, BmsModel.Map map, int groups) {
            Set<ExtendedAttribute> attributes = map.dataAttributes();
            boolean input = mapset.mode() != Mode.OUT;
            boolean output = mapset.mode() != Mode.IN;
            for (Field field : map.namedFields()) {
                if (field.length() == 0) {
                    throw new BmsDefinitionException(field.line(),
                            "named field " + field.name().orElseThrow()
                                    + " must have a positive LENGTH");
                }
            }
            if (input) {
                line(AREA_A + "01  " + map.name() + "I.");
                prefix(mapset);
                for (Field field : map.namedFields()) {
                    String name = field.name().orElseThrow();
                    if (field.occurs() > 1) {
                        groups++;
                        line(LEVEL_02 + "DFHMS" + groups + " OCCURS " + field.occurs() + " TIMES.");
                        inputField(field, name, attributes, LEVEL_03, LEVEL_04);
                    } else {
                        inputField(field, name, attributes, LEVEL_02, LEVEL_03);
                    }
                }
            }
            if (output) {
                line(AREA_A + "01  " + map.name() + "O"
                        + (input ? " REDEFINES " + map.name() + "I." : "."));
                prefix(mapset);
                for (Field field : map.namedFields()) {
                    String name = field.name().orElseThrow();
                    if (field.occurs() > 1) {
                        groups++;
                        line(LEVEL_02 + "DFHMS" + groups + " OCCURS " + field.occurs() + " TIMES.");
                        outputField(field, name, attributes, LEVEL_03);
                    } else {
                        outputField(field, name, attributes, LEVEL_02);
                    }
                }
            }
            return groups;
        }

        private void prefix(Mapset mapset) {
            if (mapset.tioaPrefix()) {
                line(LEVEL_02 + "FILLER PIC X(" + TIOA_PREFIX_LENGTH + ").");
            }
        }

        private void inputField(Field field, String name, Set<ExtendedAttribute> attributes,
                                String level, String child) {
            line(level + name + "L    COMP  PIC  S9(4).", field);
            line(level + name + "F    PICTURE X.", field);
            line(level + "FILLER REDEFINES " + name + "F.", field);
            line(child + name + "A    PICTURE X.", field);
            if (!attributes.isEmpty()) {
                line(level + "FILLER   PICTURE X(" + attributes.size() + ").", field);
            }
            line(level + name + "I  PIC " + field.pictureIn()
                    .orElse("X(" + field.length() + ")") + ".", field);
        }

        private void outputField(Field field, String name, Set<ExtendedAttribute> attributes,
                                 String level) {
            line(level + "FILLER PICTURE X(3).", field);
            // EnumSetの反復順は宣言順であり、宣言順を記号マップの並びに合わせてある
            for (ExtendedAttribute attribute : attributes) {
                line(level + name + attribute.suffix() + "    PICTURE X.", field);
            }
            line(level + name + "O  PIC " + field.pictureOut()
                    .orElse("X(" + field.length() + ")") + ".", field);
        }

        private void line(String text, Field field) {
            if (text.length() > LAST_COLUMN) {
                throw new BmsDefinitionException(field.line(),
                        "generated symbolic map line exceeds column " + LAST_COLUMN);
            }
            line(text);
        }

        private void line(String text) {
            out.append(text).append('\n');
        }
    }
}
