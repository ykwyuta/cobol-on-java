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
import dev.cobolonjava.runtime.program.SpecialRegisterArea;
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
    /** いま読んでいる {@code FD} のファイル名。ファイル節の外では {@code null}。 */
    private String currentFile;
    private final SpecialNames specialNames;

    private DataDivisionBuilder(SpecialNames specialNames) {
        this.specialNames = specialNames;
    }
    /** 指標名から、その実体の項目を引く。 */
    private final Map<String, DataItem> indexes = new LinkedHashMap<>();
    private final List<DataItem> records = new ArrayList<>();
    /** {@code FD} ごとのレコード記述。書かれた順に並ぶ。 */
    private final Map<String, List<DataItem>> fileRecords = new LinkedHashMap<>();
    /** 開いている群項目。いちばん上が現在の親である。 */
    private final Deque<DataItem> open = new ArrayDeque<>();
    /** 直前に作った項目。条件名 (88) はここへ付く。 */
    private DataItem previous;
    private int totalLength;

    /**
     * 割り付けの結果。
     *
     * @param layout      記憶域の割り付け
     * @param diagnostics 見つかった誤り。空なら成功
     */
    public record Result(DataLayout layout, Map<String, List<DataItem>> fileRecords,
                         List<Diagnostic> diagnostics) {

        public boolean succeeded() {
            return diagnostics.isEmpty();
        }
    }

    /** プログラム 1 本のデータ部から割り付けを作る。 */
    public static Result build(CobolParser.ProgramUnitContext program) {
        return build(program, SpecialNames.standard());
    }

    /**
     * 環境部の指定を踏まえてデータ部から割り付けを作る。
     *
     * <p>{@code SPECIAL-NAMES} を先に読まなければならない。<b>PICTURE の解釈が通貨記号に
     * 依る</b>ためである。
     */
    public static Result build(CobolParser.ProgramUnitContext program,
                               SpecialNames specialNames) {
        DataDivisionBuilder builder = new DataDivisionBuilder(specialNames);
        builder.addProgramUnit(program);
        builder.addIndexItems();
        builder.addLinageCounters(program);
        builder.layoutRecords();
        builder.applyRenames();
        return new Result(new DataLayout(builder.records, builder.indexes,
                specialRegisters(), builder.totalLength),
                Map.copyOf(builder.fileRecords), List.copyOf(builder.diagnostics));
    }

    private void addProgramUnit(CobolParser.ProgramUnitContext unit) {
        if (unit.dataDivision() == null) {
            return;
        }
        for (CobolParser.DataDivisionSectionContext section : unit.dataDivision().dataDivisionSection()) {
            if (section.fileSection() != null) {
                addFileSection(section.fileSection());
                continue;
            }
            currentSection = sectionOf(section);
            for (CobolParser.DataDescriptionEntryContext entry : entriesOf(section)) {
                addEntry(entry);
            }
        }
        currentSection = DataSection.WORKING_STORAGE;
        currentFile = null;
    }

    /**
     * ファイル節を読む (要件 FR-100)。
     *
     * <p>{@code FD} の下に書かれた 01 レベルは、その {@code FD} の<b>レコード領域</b>である。
     * 名前で引ける普通の項目であることは作業場所の項目と変わらないので、同じ道で作る。
     * 違うのは、どのファイルのものかを覚えておくところだけである。
     */
    private void addFileSection(CobolParser.FileSectionContext section) {
        currentSection = DataSection.FILE;
        for (CobolParser.FileDescriptionEntryContext fd : section.fileDescriptionEntry()) {
            currentFile = fd.IDENTIFIER().getText().toUpperCase(Locale.ROOT);
            Origin origin = originOf(fd);
            if (fileRecords.containsKey(currentFile)) {
                report(origin, "duplicate FD for " + currentFile);
                continue;
            }
            List<DataItem> area = new ArrayList<>();
            for (CobolParser.DataDescriptionEntryContext entry : fd.dataDescriptionEntry()) {
                int before = records.size();
                addEntry(entry);
                for (int i = before; i < records.size(); i++) {
                    area.add(records.get(i));
                }
            }
            if (area.isEmpty()) {
                report(origin, "FD " + currentFile + " has no record description");
                continue;
            }
            fileRecords.put(currentFile, area);
        }
        currentFile = null;
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
            // 66 レベルは記憶域を重ねずに名前を付け替える。位置と長さが決まるのは
            // 割り付けのあとなので、ここでは控えるだけにする
            addRenames(entry, origin);
            return;
        }

        DataItem item = new DataItem(level, nameOf(entry), origin);
        applyClauses(item, entry, origin);

        if (level == 1 || level == INDEPENDENT_LEVEL) {
            open.clear();
            item.setSection(currentSection);
            item.setFileName(currentFile);
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
            item.setPicture(PictureParser.parse(clause.PICTURE_STRING().getText(),
                    specialNames.currency()));
        } catch (RuntimeException e) {
            report(origin, "invalid PICTURE character-string: " + e.getMessage());
        }
    }

    /**
     * {@code USAGE} 句を効かせる。
     *
     * <p>{@code INDEX} だけは形が違う。<b>PICTURE を持たない</b>のに数を入れる項目で
     * あり、入っているのは「表の何番目か」である。指標名と同じ持ち方にしてある
     * (暫定判断 P-035)。同じ持ち方にすれば、{@code SET} も添字も同じ道を通る。
     */
    private void applyUsage(DataItem item, CobolParser.UsageClauseContext clause, Origin origin) {
        String name = clause.usageName().getText().toUpperCase(Locale.ROOT);
        if (name.equals("INDEX")) {
            // 群に書かれることもある。実体を作るのは木ができてからである
            item.markIndexDeclared();
            return;
        }
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
            // POINTER / NATIONAL / DISPLAY-1 はランタイムが未対応 (暫定判断 P-006)
            report(origin, "USAGE " + name + " is not supported yet");
            return;
        }
        item.setUsage(usage);
    }

    /**
     * {@code USAGE INDEX} の項目 (要件 FR-025、暫定判断 P-035)。
     *
     * <p>指標データ項目である。{@code PICTURE} を書いてはならないという決まりがあり、
     * 大きさは処理系が決める。ここでは指標名と同じ 4 バイトの 2 進数にしてある。
     *
     * <p>{@code PICTURE} が書かれていれば誤りとして報せる。黙って通すと、書いた人の
     * 思った大きさと違う項目ができる。
     */
    private void applyIndexUsage(DataItem item, Origin origin) {
        if (item.picture() != null) {
            report(origin, "USAGE INDEX cannot have a PICTURE: " + item.name());
            return;
        }
        item.setPicture(PictureParser.parse(INDEX_PICTURE));
        item.setUsage(Usage.COMP);
        item.markIndex();
    }

    /** 指標が持つ数の形。4 バイトの 2 進数である。 */
    private static final String INDEX_PICTURE = "9(9)";

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
        for (CobolParser.OccursKeyClauseContext key : clause.occursKeyClause()) {
            boolean ascending = key.ASCENDING() != null;
            for (CobolParser.QualifiedDataNameContext name : key.qualifiedDataName()) {
                item.addSearchKey(new DataItem.SearchKey(ascending,
                        name.dataName(0).getText().toUpperCase(Locale.ROOT)));
            }
        }
        if (clause.occursIndexedClause() != null) {
            for (var name : clause.occursIndexedClause().IDENTIFIER()) {
                item.addIndexName(name.getText().toUpperCase(Locale.ROOT));
            }
        }
    }

    /**
     * 66 レベルの控え。位置と長さは割り付けのあとに決まる。
     *
     * @param record 直前の 01 レベル。66 はその記述の一部に別名を付ける
     */
    private record Renames(String name, DataItem record,
                           CobolParser.QualifiedDataNameContext from,
                           CobolParser.QualifiedDataNameContext through, Origin origin) {
    }

    private final List<Renames> renames = new ArrayList<>();

    /** 66 レベルを控える。 */
    private void addRenames(CobolParser.DataDescriptionEntryContext entry, Origin origin) {
        CobolParser.RenamesClauseContext clause = renamesClauseOf(entry);
        if (clause == null) {
            report(origin, "level 66 needs a RENAMES clause");
            return;
        }
        if (records.isEmpty()) {
            report(origin, "level 66 must follow a record description");
            return;
        }
        renames.add(new Renames(nameOf(entry), records.get(records.size() - 1),
                clause.qualifiedDataName(0),
                clause.qualifiedDataName().size() > 1 ? clause.qualifiedDataName(1) : null,
                origin));
    }

    private static CobolParser.RenamesClauseContext renamesClauseOf(
            CobolParser.DataDescriptionEntryContext entry) {
        for (CobolParser.DataClauseContext clause : entry.dataClause()) {
            if (clause.renamesClause() != null) {
                return clause.renamesClause();
            }
        }
        return null;
    }

    /**
     * 66 レベルを記憶域の上へ置く (要件 FR-021)。
     *
     * <p>{@code RENAMES} は<b>記憶域を重ねない</b>。すでにある記述の一部に、別の名前と
     * 別の切り方を与えるだけである。{@code REDEFINES} と違って新しい場所を取らないので、
     * 割り付けが終わったあとに位置と長さを写せばよい。
     *
     * <p>{@code THRU} を書けば、始まりの項目の先頭から<b>終わりの項目の末尾まで</b>が
     * 1 つの群項目になる。書かなければ、その項目 1 つの別名である。
     */
    private void applyRenames() {
        for (Renames one : renames) {
            DataItem from = withinRecord(one.record(), one.from(), one.origin());
            if (from == null) {
                continue;
            }
            DataItem through = one.through() == null
                    ? from
                    : withinRecord(one.record(), one.through(), one.origin());
            if (through == null) {
                continue;
            }
            int start = from.offset();
            int end = through.offset() + through.totalLength();
            if (end <= start) {
                report(one.origin(), "RENAMES must name items in order: " + one.name());
                continue;
            }
            DataItem alias = new DataItem(RENAMES_LEVEL, one.name(), one.origin());
            alias.markAlias();
            alias.setOffset(start);
            alias.setLength(end - start);
            if (one.through() == null && from.isElementary() && from.picture() != null) {
                // 1 つの項目の別名は、その項目と同じ書き方を引き継ぐ
                alias.setPicture(from.picture());
                alias.setUsage(from.usage());
                alias.setSignPosition(from.signPosition());
            } else {
                // 範囲に名前を付けたものは英数字の群である
                alias.setPicture(PictureParser.parse("X(" + (end - start) + ")"));
            }
            one.record().addChild(alias);
        }
    }

    /**
     * その記述の中から名前で項目を 1 つ選ぶ。
     *
     * <p>探す範囲を記述の中に限るのは、{@code RENAMES} が<b>直前の 01 の一部</b>にしか
     * 名前を付けられないからである。
     */
    private DataItem withinRecord(DataItem record, CobolParser.QualifiedDataNameContext context,
                                  Origin origin) {
        List<String> names = new ArrayList<>();
        for (CobolParser.DataNameContext name : context.dataName()) {
            names.add(name.getText().toUpperCase(Locale.ROOT));
        }
        List<DataItem> found = new ArrayList<>();
        collectNamed(record, names.get(0), found);
        if (found.isEmpty()) {
            report(origin, "RENAMES names an item that is not in the record: " + names.get(0));
            return null;
        }
        if (found.size() > 1 && names.size() > 1) {
            found.removeIf(candidate -> !containedIn(candidate, names.subList(1, names.size())));
        }
        if (found.size() != 1) {
            report(origin, names.get(0) + " is ambiguous; qualify it with OF or IN");
            return null;
        }
        return found.get(0);
    }

    private static void collectNamed(DataItem item, String name, List<DataItem> found) {
        for (DataItem child : item.children()) {
            if (name.equalsIgnoreCase(child.name())) {
                found.add(child);
            }
            collectNamed(child, name, found);
        }
    }

    /** 修飾子が、外へ向かう順に祖先として現れるか。 */
    private static boolean containedIn(DataItem item, List<String> qualifiers) {
        DataItem parent = item.parent();
        int at = 0;
        while (parent != null && at < qualifiers.size()) {
            if (qualifiers.get(at).equalsIgnoreCase(parent.name())) {
                at++;
            }
            parent = parent.parent();
        }
        return at == qualifiers.size();
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
                item.setPicture(PictureParser.parse(INDEX_PICTURE));
                item.setUsage(Usage.COMP);
                item.markIndex();
                indexes.put(name, item);
                records.add(item);
            }
        }
    }

    /**
     * {@code LINAGE-COUNTER} の実体を作る (要件 FR-113)。
     *
     * <p>データ部のどこにも書かれないが、{@code FD} に {@code LINAGE} を書けば存在する。
     * <b>ファイルごとに 1 つ</b>である。2 つ以上のファイルが {@code LINAGE} を持つときは
     * 同じ名前の項目が 2 つできるので、修飾せずに書けば「あいまいだ」と断ることになる。
     * これは規格の決まりどおりである。
     *
     * <p>読むだけの項目ではあるが、普通のデータ項目として置くのがいちばん素直である。
     * 添字も転記も比較も、専用の道を作らずに通る。
     */
    private void addLinageCounters(CobolParser.ProgramUnitContext program) {
        if (program.dataDivision() == null) {
            return;
        }
        for (CobolParser.DataDivisionSectionContext section
                : program.dataDivision().dataDivisionSection()) {
            if (section.fileSection() == null) {
                continue;
            }
            for (CobolParser.FileDescriptionEntryContext fd
                    : section.fileSection().fileDescriptionEntry()) {
                if (!hasLinageClause(fd)) {
                    continue;
                }
                DataItem counter = new DataItem(INDEPENDENT_LEVEL, "LINAGE-COUNTER", originOf(fd));
                counter.setPicture(PictureParser.parse(INDEX_PICTURE));
                counter.setUsage(Usage.COMP);
                records.add(counter);
            }
        }
    }

    private static boolean hasLinageClause(CobolParser.FileDescriptionEntryContext fd) {
        for (CobolParser.FileDescriptionClauseContext clause : fd.fileDescriptionClause()) {
            if (clause.linageClause() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 特殊レジスタの項目 (要件 FR-084)。
     *
     * <p>データ部には書かれないが、名前で読み書きできる。実行の全体で 1 つなので、
     * プログラムごとの記憶域ではなく<b>実行時の入口が持つ置き場</b>を指す。
     */
    private static Map<String, DataItem> specialRegisters() {
        DataItem returnCode = new DataItem(INDEPENDENT_LEVEL, "RETURN-CODE", null);
        returnCode.setPicture(PictureParser.parse(SpecialRegisterArea.RETURN_CODE_PICTURE));
        returnCode.setUsage(Usage.COMP);
        returnCode.setSection(DataSection.SPECIAL_REGISTER);
        returnCode.setOffset(SpecialRegisterArea.RETURN_CODE_OFFSET);
        returnCode.setLength(returnCode.picture().size());
        return Map.of("RETURN-CODE", returnCode);
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
        Map<String, Integer> fileBases = new LinkedHashMap<>();
        for (DataItem record : records) {
            inheritUsage(record, null, false);
            layout(record, 0);
            if (record.section() == DataSection.LINKAGE) {
                continue;
            }
            if (record.section() == DataSection.FILE) {
                // 同じ FD のレコード記述は重なる。どれも 1 つのバッファの別の切り方である
                Integer at = fileBases.get(record.fileName());
                if (at == null) {
                    fileBases.put(record.fileName(), base);
                    record.setBase(base);
                    base += record.totalLength();
                } else {
                    record.setBase(at);
                    base = Math.max(base, at + record.totalLength());
                }
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
    /**
     * 群項目に書いた {@code USAGE} を下位へ配る (要件 FR-020)。
     *
     * <p>{@code USAGE} は群項目にも書ける。書けば<b>配下の基本項目すべて</b>に効く。
     * 群項目そのものは記憶域の切り方を持たないので、効くのは下だけである。
     *
     * <p>{@code USAGE IS INDEX} を群に書くと、配下の基本項目はどれも指標データ項目に
     * なる。{@code PICTURE} は書けない決まりなので、書かれていないのは<b>正しい</b>。
     */
    private void inheritUsage(DataItem item, Usage inherited, boolean inheritedIndex) {
        boolean index = item.indexDeclared() || inheritedIndex;
        Usage usage = item.usage() == null ? inherited : item.usage();
        if (item.isElementary()) {
            if (index) {
                applyIndexUsage(item, item.origin());
            } else if (item.usage() == null && usage != null) {
                item.setUsage(usage);
            }
            return;
        }
        for (DataItem child : item.children()) {
            inheritUsage(child, usage, index);
        }
    }

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
