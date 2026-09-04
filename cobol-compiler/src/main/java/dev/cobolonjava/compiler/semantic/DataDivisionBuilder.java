package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.parser.OriginToken;
import dev.cobolonjava.compiler.source.Origin;
import dev.cobolonjava.runtime.data.SignPosition;
import dev.cobolonjava.runtime.item.NumericItem;
import dev.cobolonjava.runtime.item.Usage;
import dev.cobolonjava.runtime.picture.Picture;
import dev.cobolonjava.runtime.picture.PictureParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

/**
 * データ部の構文木から記憶域の割り付けを作る (要件 FR-020, FR-030, FR-031)。
 *
 * <p>ここで<b>コンパイラがランタイムの意味論を使う</b>。項目のバイト長は
 * {@link NumericItem#byteLength()} と {@link Picture#size()} にそのまま任せる。
 * コンパイラが桁数から独自にバイト数を求めると、ランタイムとずれる余地ができる。
 * V2 で裏付けを取ったのはランタイムのほうであり、そちらが正である。
 *
 * <h2>レベル番号は入れ子の深さではない</h2>
 * <p>COBOL のレベル番号は「大きいほうが下位」という順序だけを表す。{@code 01} の次が
 * {@code 05} でも {@code 02} でもよく、{@code 05} の下に {@code 10} が来ても {@code 07} が
 * 来てもよい。したがって<b>開いているレベルを積んで、番号の大小で閉じる</b>。
 *
 * <h2>REDEFINES は位置を巻き戻す</h2>
 * <p>{@code REDEFINES} は記憶域を進めない。重ねる先と同じ位置から始め、
 * 群項目の長さは<b>いちばん遠くまで届いた項目</b>で決める。
 */
public final class DataDivisionBuilder {

    /** 独立項目のレベル番号。{@code 01} と同じく記憶域の先頭から始まる。 */
    private static final int INDEPENDENT_LEVEL = 77;
    /** 条件名のレベル番号。記憶域を占めない。 */
    private static final int CONDITION_LEVEL = 88;
    /** 再命名のレベル番号。 */
    private static final int RENAMES_LEVEL = 66;

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private DataSection currentSection = DataSection.WORKING_STORAGE;
    /** 指標名から、その実体の項目を引く。 */
    private final Map<String, DataItem> indexes = new LinkedHashMap<>();
    private final List<DataItem> records = new ArrayList<>();
    /** 開いている群項目。いちばん上が現在の親である。 */
    private final Deque<DataItem> open = new ArrayDeque<>();
    /** 直前に作った項目。条件名 (88) はここへ付く。 */
    private DataItem previous;
    private int totalLength;

    private DataDivisionBuilder() {
    }

    /**
     * 割り付けの結果。
     *
     * @param layout      記憶域の割り付け
     * @param diagnostics 見つかった誤り。空なら成功
     */
    public record Result(DataLayout layout, List<Diagnostic> diagnostics) {

        public boolean succeeded() {
            return diagnostics.isEmpty();
        }
    }

    /** 構文木のデータ部から割り付けを作る。 */
    public static Result build(CobolParser.CompilationUnitContext tree) {
        DataDivisionBuilder builder = new DataDivisionBuilder();
        for (CobolParser.ProgramUnitContext unit : tree.programUnit()) {
            builder.addProgramUnit(unit);
        }
        builder.addIndexItems();
        builder.layoutRecords();
        return new Result(new DataLayout(builder.records, builder.indexes, builder.totalLength),
                List.copyOf(builder.diagnostics));
    }

    private void addProgramUnit(CobolParser.ProgramUnitContext unit) {
        if (unit.dataDivision() == null) {
            return;
        }
        for (CobolParser.DataDivisionSectionContext section : unit.dataDivision().dataDivisionSection()) {
            currentSection = sectionOf(section);
            for (CobolParser.DataDescriptionEntryContext entry : entriesOf(section)) {
                addEntry(entry);
            }
        }
        currentSection = DataSection.WORKING_STORAGE;
    }

    private static DataSection sectionOf(CobolParser.DataDivisionSectionContext section) {
        if (section.workingStorageSection() != null) {
            return DataSection.WORKING_STORAGE;
        }
        return section.localStorageSection() != null
                ? DataSection.LOCAL_STORAGE
                : DataSection.LINKAGE;
    }

    private static List<CobolParser.DataDescriptionEntryContext> entriesOf(
            CobolParser.DataDivisionSectionContext section) {
        if (section.workingStorageSection() != null) {
            return section.workingStorageSection().dataDescriptionEntry();
        }
        if (section.localStorageSection() != null) {
            return section.localStorageSection().dataDescriptionEntry();
        }
        return section.linkageSection().dataDescriptionEntry();
    }

    // ---- 木の組み立て ----

    private void addEntry(CobolParser.DataDescriptionEntryContext entry) {
        Origin origin = originOf(entry);
        int level;
        try {
            level = Integer.parseInt(entry.levelNumber().getText());
        } catch (NumberFormatException e) {
            report(origin, "level number must be an integer: " + entry.levelNumber().getText());
            return;
        }

        if (level == CONDITION_LEVEL) {
            addConditionName(entry, origin);
            return;
        }
        if (level == RENAMES_LEVEL) {
            // 66 レベルは記憶域を重ねずに名前を付け替える。割り付けの規則が別物なので分けて扱う
            report(origin, "level 66 RENAMES is not supported yet");
            return;
        }

        DataItem item = new DataItem(level, nameOf(entry), origin);
        applyClauses(item, entry, origin);

        if (level == 1 || level == INDEPENDENT_LEVEL) {
            open.clear();
            item.setSection(currentSection);
            records.add(item);
        } else {
            while (!open.isEmpty() && open.peek().level() >= level) {
                open.pop();
            }
            if (open.isEmpty()) {
                report(origin, "level " + entry.levelNumber().getText()
                        + " item has no containing 01 level item");
                return;
            }
            open.peek().addChild(item);
        }
        open.push(item);
        previous = item;
    }

    private void addConditionName(CobolParser.DataDescriptionEntryContext entry, Origin origin) {
        if (previous == null) {
            report(origin, "level 88 condition-name has no item to belong to");
            return;
        }
        List<DataItem.ValueRange> values = new ArrayList<>();
        for (CobolParser.DataClauseContext clause : entry.dataClause()) {
            if (clause.valueClause() == null) {
                report(origin, "level 88 condition-name allows only a VALUE clause");
                continue;
            }
            for (CobolParser.ValueRangeContext range : clause.valueClause().valueRange()) {
                values.add(new DataItem.ValueRange(literalOf(range.literal(0), origin),
                        range.literal().size() > 1 ? literalOf(range.literal(1), origin) : null));
            }
        }
        if (values.isEmpty()) {
            report(origin, "level 88 condition-name requires a VALUE clause");
            return;
        }
        previous.addConditionName(new DataItem.ConditionName(nameOf(entry), values, origin));
    }

    private static String nameOf(CobolParser.DataDescriptionEntryContext entry) {
        if (entry.dataName() == null || entry.dataName().FILLER() != null) {
            return null;
        }
        return entry.dataName().getText().toUpperCase(Locale.ROOT);
    }

    private void applyClauses(DataItem item, CobolParser.DataDescriptionEntryContext entry,
                              Origin origin) {
        for (CobolParser.DataClauseContext clause : entry.dataClause()) {
            if (clause.pictureClause() != null) {
                applyPicture(item, clause.pictureClause(), origin);
            } else if (clause.usageClause() != null) {
                applyUsage(item, clause.usageClause(), origin);
            } else if (clause.signClause() != null) {
                item.setSignPosition(signPositionOf(clause.signClause()));
            } else if (clause.occursClause() != null) {
                applyOccurs(item, clause.occursClause(), origin);
            } else if (clause.redefinesClause() != null) {
                item.setRedefinesName(
                        clause.redefinesClause().dataName().getText().toUpperCase(Locale.ROOT));
            } else if (clause.justifiedClause() != null) {
                item.setJustified(true);
            } else if (clause.blankWhenZeroClause() != null) {
                item.setBlankWhenZero(true);
            } else if (clause.valueClause() != null) {
                applyValue(item, clause.valueClause(), origin);
            }
            // SYNCHRONIZED / GLOBAL / EXTERNAL は割り付けに効かない
        }
    }

    private void applyValue(DataItem item, CobolParser.ValueClauseContext clause, Origin origin) {
        List<CobolParser.ValueRangeContext> ranges = clause.valueRange();
        if (ranges.size() > 1 || ranges.get(0).literal().size() > 1) {
            // 値の並びと THRU の範囲は 88 レベルの条件名だけのものである
            report(origin, "a data item takes a single VALUE, not a list or a range");
            return;
        }
        LiteralValue value = literalOf(ranges.get(0).literal(0), origin);
        if (value != null) {
            item.setInitialValue(value);
        }
    }

    private LiteralValue literalOf(CobolParser.LiteralContext context, Origin origin) {
        try {
            return LiteralValue.of(context);
        } catch (RuntimeException e) {
            report(origin, "invalid literal: " + context.getText());
            return null;
        }
    }

    private void applyPicture(DataItem item, CobolParser.PictureClauseContext clause, Origin origin) {
        try {
            item.setPicture(PictureParser.parse(clause.PICTURE_STRING().getText()));
        } catch (RuntimeException e) {
            report(origin, "invalid PICTURE character-string: " + e.getMessage());
        }
    }

    private void applyUsage(DataItem item, CobolParser.UsageClauseContext clause, Origin origin) {
        String name = clause.usageName().getText().toUpperCase(Locale.ROOT);
        Usage usage = switch (name) {
            case "DISPLAY" -> Usage.DISPLAY;
            case "PACKED-DECIMAL", "COMP-3", "COMPUTATIONAL-3" -> Usage.COMP_3;
            case "BINARY", "COMP", "COMPUTATIONAL", "COMP-4", "COMPUTATIONAL-4" -> Usage.COMP;
            case "COMP-5", "COMPUTATIONAL-5" -> Usage.COMP_5;
            case "COMP-1", "COMPUTATIONAL-1" -> Usage.COMP_1;
            case "COMP-2", "COMPUTATIONAL-2" -> Usage.COMP_2;
            default -> null;
        };
        if (usage == null) {
            // INDEX / POINTER / NATIONAL / DISPLAY-1 はランタイムが未対応 (暫定判断 P-006)
            report(origin, "USAGE " + name + " is not supported yet");
            return;
        }
        item.setUsage(usage);
    }

    private static SignPosition signPositionOf(CobolParser.SignClauseContext clause) {
        String text = clause.getText().toUpperCase(Locale.ROOT);
        boolean leading = clause.LEADING() != null;
        boolean separate = text.contains("SEPARATE");
        if (leading) {
            return separate ? SignPosition.LEADING_SEPARATE : SignPosition.LEADING;
        }
        return separate ? SignPosition.TRAILING_SEPARATE : SignPosition.TRAILING;
    }

    private void applyOccurs(DataItem item, CobolParser.OccursClauseContext clause, Origin origin) {
        List<org.antlr.v4.runtime.tree.TerminalNode> numbers = clause.NUMBER();
        // OCCURS m TO n の可変長は、記憶域としては最大の n を確保する
        String text = numbers.get(numbers.size() - 1).getText();
        try {
            int times = Integer.parseInt(text);
            if (times < 1) {
                report(origin, "OCCURS requires a positive number of occurrences: " + text);
                return;
            }
            item.setOccurs(times);
        } catch (NumberFormatException e) {
            report(origin, "OCCURS requires an integer: " + text);
        }
        if (clause.occursIndexedClause() != null) {
            for (var name : clause.occursIndexedClause().IDENTIFIER()) {
                item.addIndexName(name.getText().toUpperCase(Locale.ROOT));
            }
        }
    }

    /**
     * 指標名の実体を作る (要件 FR-025)。
     *
     * <p>指標名は COBOL のデータ項目ではないが、<b>反復の番号を持つ入れ物</b>であることに
     * 変わりはない。2 進 4 バイトの項目として記憶域の後ろへ足し、添字も {@code SET} も
     * 普通のデータ項目と同じ道を通す。参照実装は変位を持つが、観測できるのは
     * 「何番目か」だけである。
     *
     * <p>名前には {@code $} を入れてある。COBOL 語に使えない文字なので、
     * <b>ソースから名前で引き当てられない</b>。指標名は専用の表からしか引けない。
     */
    private void addIndexItems() {
        List<DataItem> tables = new ArrayList<>();
        for (DataItem record : records) {
            collectTables(record, tables);
        }
        for (DataItem table : tables) {
            for (String name : table.indexNames()) {
                if (indexes.containsKey(name)) {
                    report(table.origin(), "duplicate index name: " + name);
                    continue;
                }
                DataItem item = new DataItem(INDEPENDENT_LEVEL, "IDX$" + name, table.origin());
                item.setPicture(PictureParser.parse("9(9)"));
                item.setUsage(Usage.COMP);
                item.markIndex();
                indexes.put(name, item);
                records.add(item);
            }
        }
    }

    private static void collectTables(DataItem item, List<DataItem> tables) {
        if (!item.indexNames().isEmpty()) {
            tables.add(item);
        }
        item.children().forEach(child -> collectTables(child, tables));
    }

    // ---- 割り付け ----

    /**
     * 01 レベルを記憶域へ並べる。
     *
     * <p>連絡節の項目は<b>記憶域を占めない</b>。記憶域の位置は実行時に渡されるため、
     * 位置は 0 のままにして、その中での位置だけを決める。
     */
    private void layoutRecords() {
        int base = 0;
        for (DataItem record : records) {
            layout(record, 0);
            if (record.section() == DataSection.LINKAGE) {
                continue;
            }
            if (record.redefinesName() != null) {
                // 01 レベルの REDEFINES は記憶域を進めない。重ねる先と同じ位置から始まる
                DataItem target = redefinedRecord(record);
                record.setBase(target == null ? base : target.base());
                continue;
            }
            record.setBase(base);
            base += record.totalLength();
        }
        totalLength = base;
    }

    /** 01 レベルの {@code REDEFINES} が指す、先行するレコード。 */
    private DataItem redefinedRecord(DataItem record) {
        for (DataItem previous : records) {
            if (previous == record) {
                break;
            }
            if (record.redefinesName().equals(previous.name())) {
                return previous;
            }
        }
        report(record.origin(), "REDEFINES names an item that does not precede it: "
                + record.redefinesName());
        return null;
    }

    /**
     * 項目とその下位に位置を割り当て、反復を含めたバイト長を返す。
     *
     * @param offset この項目が始まる、所属する 01 レベルからの位置
     */
    private int layout(DataItem item, int offset) {
        item.setOffset(offset);
        item.setLength(item.isElementary() ? elementaryLength(item) : groupLength(item, offset));
        return item.totalLength();
    }

    private int groupLength(DataItem item, int offset) {
        int cursor = offset;
        int end = offset;
        for (DataItem child : item.children()) {
            int start = cursor;
            if (child.redefinesName() != null) {
                DataItem target = redefinedSibling(item, child);
                if (target == null) {
                    continue;
                }
                // REDEFINES は記憶域を進めない。重ねる先と同じ位置から始める
                start = target.offset();
            }
            int used = layout(child, start);
            end = Math.max(end, start + used);
            if (child.redefinesName() == null) {
                cursor += used;
            }
        }
        return end - offset;
    }

    /** {@code REDEFINES} が指す、同じ親を持つ先行の項目。 */
    private DataItem redefinedSibling(DataItem parent, DataItem item) {
        for (DataItem sibling : parent.children()) {
            if (sibling == item) {
                break;
            }
            if (item.redefinesName().equals(sibling.name())) {
                return sibling;
            }
        }
        report(item.origin(), "REDEFINES names an item that does not precede it: "
                + item.redefinesName());
        return null;
    }

    private int elementaryLength(DataItem item) {
        Usage usage = item.usage();
        Picture picture = item.picture();

        if (usage != null && usage.isFloatingPoint()) {
            if (picture != null) {
                report(item.origin(), "USAGE " + usage + " item must not have a PICTURE clause");
            }
            return usage == Usage.COMP_1 ? 4 : 8;
        }
        if (picture == null) {
            report(item.origin(), "elementary item requires a PICTURE clause: " + describe(item));
            return 0;
        }
        if (picture.isNumeric()) {
            return numericLength(item, picture, usage == null ? Usage.DISPLAY : usage);
        }
        if (usage != null && usage != Usage.DISPLAY) {
            report(item.origin(), "USAGE " + usage + " requires a numeric PICTURE: " + describe(item));
            return picture.size();
        }
        return picture.size();
    }

    /** 数値項目の長さ。ランタイムの記述子にそのまま尋ねる。 */
    private int numericLength(DataItem item, Picture picture, Usage usage) {
        try {
            NumericItem descriptor = NumericItem.of(picture.source(), usage);
            if (item.signPosition() != SignPosition.UNSIGNED) {
                descriptor = descriptor.withSignPosition(item.signPosition());
            }
            return descriptor.byteLength();
        } catch (RuntimeException e) {
            report(item.origin(), e.getMessage());
            return 0;
        }
    }

    private static String describe(DataItem item) {
        return item.name() == null ? "FILLER" : item.name();
    }

    private void report(Origin origin, String message) {
        diagnostics.add(new Diagnostic(origin, message));
    }

    private static Origin originOf(ParserRuleContext context) {
        Token token = context.getStart();
        return token instanceof OriginToken origin ? origin.origin() : null;
    }
}
