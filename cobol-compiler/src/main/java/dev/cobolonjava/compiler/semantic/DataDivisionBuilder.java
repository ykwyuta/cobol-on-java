package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.cics.CicsEib;
import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.parser.OriginToken;
import dev.cobolonjava.compiler.source.Origin;
import dev.cobolonjava.runtime.data.BinaryDecimal;
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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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

    /** このプログラムの名前。自分が書いた {@code GLOBAL} 項目の持ち主になる。 */
    private String programName;

    private DataDivisionBuilder(SpecialNames specialNames) {
        this.specialNames = specialNames;
    }

    /**
     * 囲む側から引き継ぐ {@code GLOBAL} の 01 レベル 1 個 (要件 FR-091)。
     *
     * <p>配下の項目は文法の上では<b>並んだ記述項</b>であって、01 の中に入っていない。
     * だから 01 とその配下をひとまとまりで持ち運ぶ。
     *
     * @param owner   書いたプログラムの名前。実体はそちらにある
     * @param entries 01 レベルとその配下。書かれた順に並ぶ
     */
    public record InheritedGlobal(String owner,
                                  List<CobolParser.DataDescriptionEntryContext> entries) {

        public InheritedGlobal {
            entries = List.copyOf(entries);
        }
    }

    /**
     * 囲む側の {@code GLOBAL} 項目を、この割り付けにも並べる (要件 FR-091)。
     *
     * <p>同じ名前を自分でも宣言していれば<b>自分のほうが勝つ</b>。規格がそう決めている
     * ——内側の宣言が外側を隠す。
     */
    private void addInheritedGlobals(List<InheritedGlobal> inherited) {
        for (InheritedGlobal one : inherited) {
            if (one.entries().isEmpty()) {
                continue;
            }
            String name = nameOf(one.entries().get(0));
            if (name == null || declares(name)) {
                continue;
            }
            currentSection = DataSection.WORKING_STORAGE;
            currentFile = null;
            open.clear();
            previous = null;
            int before = records.size();
            for (CobolParser.DataDescriptionEntryContext entry : one.entries()) {
                addEntry(entry);
            }
            for (int at = before; at < records.size(); at++) {
                records.get(at).setGlobalOwner(one.owner());
            }
        }
        open.clear();
        previous = null;
    }

    /** その名前の 01 レベルを、このプログラムが自分で宣言しているか。 */
    private boolean declares(String name) {
        for (DataItem record : records) {
            if (name.equalsIgnoreCase(record.name())) {
                return true;
            }
        }
        return false;
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
    /** 報告書節から組み立てた記述。 */
    private final List<ReportDescription> reports = new ArrayList<>();

    /**
     * 割り付けの結果。
     *
     * @param layout      記憶域の割り付け
     * @param diagnostics 見つかった誤り。空なら成功
     */
    public record Result(DataLayout layout, Map<String, List<DataItem>> fileRecords,
                         List<ReportDescription> reports, List<Diagnostic> diagnostics) {

        public boolean succeeded() {
            return !Diagnostic.blocking(diagnostics);
        }

        public ReportDescription report(String name) {
            for (ReportDescription report : reports) {
                if (report.name().equals(name)) {
                    return report;
                }
            }
            return null;
        }

        /** その報告集団を持つ報告書。無ければ {@code null}。 */
        public ReportDescription reportOfGroup(String groupName) {
            for (ReportDescription report : reports) {
                if (report.group(groupName) != null) {
                    return report;
                }
            }
            return null;
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
        return build(program, specialNames, null, List.of());
    }

    /**
     * 囲む側の {@code GLOBAL} 項目も見えるようにして割り付けを作る (要件 FR-091)。
     *
     * <p>入れ子のプログラムでは、囲む側が {@code GLOBAL} と書いた 01 レベルが
     * <b>囲まれた側から見える</b>。囲まれた側の原文には書かれていないので、
     * 記述項をそのまま持ってきて、囲まれた側の割り付けにも並べる。
     *
     * @param programName このプログラムの名前。自分が書いた {@code GLOBAL} の持ち主になる
     * @param inherited   囲む側から引き継ぐ {@code GLOBAL} の記述項
     */
    public static Result build(CobolParser.ProgramUnitContext program,
                               SpecialNames specialNames, String programName,
                               List<InheritedGlobal> inherited) {
        return build(program, specialNames, programName, inherited, List.of());
    }

    /**
     * 囲む側の {@code GLOBAL} 項目とファイルも見えるようにして割り付けを作る
     * (要件 FR-091)。
     *
     * @param inheritedFiles 囲む側から引き継ぐ {@code FD ... GLOBAL}
     */
    public static Result build(CobolParser.ProgramUnitContext program,
                               SpecialNames specialNames, String programName,
                               List<InheritedGlobal> inherited,
                               List<InheritedFile> inheritedFiles) {
        DataDivisionBuilder builder = new DataDivisionBuilder(specialNames);
        builder.programName = programName;
        builder.addSameAreas(program);
        builder.addProgramUnit(program);
        builder.addInheritedFiles(inheritedFiles);
        builder.addInheritedGlobals(inherited);
        builder.addIndexItems();
        builder.addLinageCounters(program);
        builder.addReports(program);
        builder.addDebugItem(program);
        builder.layoutRecords();
        builder.applyRenames();
        DataLayout layout = new DataLayout(builder.records, builder.indexes,
                specialRegisters(), builder.totalLength);
        // 回数を決める項目は表よりあとに書かれていてもよい。読み終えてから結び付ける
        layout.linkOccursDepending();
        return new Result(layout,
                Map.copyOf(builder.fileRecords), List.copyOf(builder.reports),
                List.copyOf(builder.diagnostics));
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
            if (section.reportSection() != null) {
                // 報告書節は記述の形が違う。割り付けは addReports が作る
                continue;
            }
            if (section.communicationSection() != null) {
                // 支えていない (制約 C-5)。断るときは<b>何を読んだのか</b>まで書く
                report(originOf(section.communicationSection()),
                        "COMMUNICATION SECTION is not supported: "
                        + "the communication module is not implemented");
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
            addFileDescription(fd, null);
        }
        currentFile = null;
        currentSection = DataSection.WORKING_STORAGE;
    }

    /**
     * {@code FD} 1 個を読む (要件 FR-100)。
     *
     * @param globalOwner 囲む側から引き継いだものなら、そのプログラムの名前
     *                    (要件 FR-091)。自分が書いたものなら {@code null}
     */
    private void addFileDescription(CobolParser.FileDescriptionEntryContext fd,
                                    String globalOwner) {
        currentFile = fd.IDENTIFIER().getText().toUpperCase(Locale.ROOT);
        Origin origin = originOf(fd);
        if (fileRecords.containsKey(currentFile)) {
            if (globalOwner == null) {
                report(origin, "duplicate FD for " + currentFile);
            }
            // 同じ名前を自分でも宣言していれば、そちらが勝つ (要件 FR-091)
            return;
        }
        // FD ... IS EXTERNAL は、ファイル結合子とレコード領域を実行単位で 1 つに
        // する (要件 FR-014)。領域のほうは記述項に印を付けて持ち回る
        boolean external = isExternalFile(fd);
        // 自分が書いた FD ... GLOBAL は、囲まれた側から見える。持ち主はこちらである
        String owner = globalOwner != null ? globalOwner
                : (isGlobalFile(fd) ? programName : null);
        List<DataItem> area = new ArrayList<>();
        for (CobolParser.DataDescriptionEntryContext entry : fd.dataDescriptionEntry()) {
            int before = records.size();
            addEntry(entry);
            for (int i = before; i < records.size(); i++) {
                area.add(records.get(i));
                if (external) {
                    records.get(i).setExternal(true);
                }
                if (owner != null) {
                    records.get(i).setGlobalOwner(owner);
                }
            }
        }
        if (area.isEmpty() && reportNamesOf(fd).isEmpty()) {
            report(origin, "FD " + currentFile + " has no record description");
            return;
        }
        // REPORT を書いたファイルのレコードは報告書節が決める。addReports が足す
        fileRecords.put(currentFile, area);
    }

    /**
     * 囲む側の {@code FD ... GLOBAL} を、この割り付けにも並べる (要件 FR-091)。
     *
     * <p>ファイルの記述は {@code SELECT} と {@code FD} の 2 か所に分かれている。
     * こちらが持ってくるのは {@code FD} のほうで、{@code SELECT} は
     * {@link FileDescription#select} が引き継ぐ。
     */
    private void addInheritedFiles(List<InheritedFile> inherited) {
        if (inherited.isEmpty()) {
            return;
        }
        currentSection = DataSection.FILE;
        for (InheritedFile one : inherited) {
            open.clear();
            previous = null;
            addFileDescription(one.entry(), one.owner());
        }
        currentFile = null;
        currentSection = DataSection.WORKING_STORAGE;
        open.clear();
        previous = null;
    }

    /**
     * 囲む側から引き継ぐ {@code FD ... GLOBAL} 1 個 (要件 FR-091)。
     *
     * @param owner 書いたプログラムの名前。実体はそちらにある
     * @param entry {@code FD} の記述項。配下のレコード記述も入っている
     */
    public record InheritedFile(String owner,
                                CobolParser.FileDescriptionEntryContext entry) {
    }

    /** {@code FD ... GLOBAL} と書かれたか (要件 FR-091)。 */
    public static boolean isGlobalFile(CobolParser.FileDescriptionEntryContext fd) {
        for (CobolParser.FileDescriptionClauseContext clause : fd.fileDescriptionClause()) {
            if (clause.GLOBAL() != null) {
                return true;
            }
        }
        return false;
    }

    /** {@code FD ... IS EXTERNAL} と書かれたか (要件 FR-014)。 */
    private static boolean isExternalFile(CobolParser.FileDescriptionEntryContext fd) {
        for (CobolParser.FileDescriptionClauseContext clause : fd.fileDescriptionClause()) {
            if (clause.EXTERNAL() != null) {
                return true;
            }
        }
        return false;
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
            } else if (clause.externalClause() != null) {
                // 割り付けには効かないが、<b>どの領域を指すか</b>に効く (要件 FR-014)
                item.setExternal(true);
            } else if (clause.globalClause() != null && programName != null) {
                // 囲まれたプログラムから見えるようになる。実体はこちらが持つ (要件 FR-091)
                item.setGlobalOwner(programName);
            } else if (clause.synchronizedClause() != null) {
                // LEFT / RIGHT は書けるが、参照実装では<b>どちらも同じ</b>である。
                // 項目は自然な境界に置かれる (暫定判断 P-111)
                item.setAligned(true);
            }
        }
        applyBlankWhenZero(item, origin);
    }

    /**
     * {@code BLANK WHEN ZERO} を PICTURE に効かせる。
     *
     * <p>句の並びは自由なので、{@code BLANK WHEN ZERO} が {@code PICTURE} より先に
     * 書かれていることがある (NC108M がそう書いている)。だから句をすべて読み終えて
     * から効かせる。規格により、数字項目に書いたときはその項目の種別が
     * <b>数字編集</b>になる。
     */
    private void applyBlankWhenZero(DataItem item, Origin origin) {
        if (!item.blankWhenZero() || item.picture() == null) {
            return;
        }
        Picture picture = item.picture();
        if (!picture.isNumeric() && !picture.isNumericEdited()) {
            report(origin, "BLANK WHEN ZERO requires a numeric or numeric-edited PICTURE: "
                    + picture.source());
            return;
        }
        item.setPicture(picture.withBlankWhenZero(true));
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
                    specialNames.currency(), specialNames.decimalPoint()));
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
        if (name.equals("POINTER")) {
            // 大きさを決めるのは木ができてからである (暫定判断 P-123)
            item.markPointer();
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
    /**
     * {@code USAGE POINTER} の基本項目 (暫定判断 P-123)。
     *
     * <p>31 bit の番地を持つ 4 byte の領域とする。この処理系は記憶域の番地を持たないので、
     * 置ける値は NULL ({@code X'00000000'}) だけである。領域は英数字として割り付け、
     * 手続き部では SET と群の転記だけを許す。
     */
    private void applyPointerUsage(DataItem item) {
        if (item.picture() != null) {
            report(item.origin(), "USAGE POINTER cannot have a PICTURE: " + describe(item));
            return;
        }
        item.setPicture(PictureParser.parse("X(4)"));
        item.setUsage(Usage.DISPLAY);
    }

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
        if (clause.qualifiedDataName() != null) {
            // 記憶域は最大の回数で取る。この名前が決めるのは「いま何個あるか」だけである
            item.setOccursDependingName(
                    clause.qualifiedDataName().dataName(0).getText().toUpperCase(Locale.ROOT));
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
                // 頁の形も置き場を持つ。項目で書かれた形は<b>開くたびに読み直す</b>
                String file = fd.IDENTIFIER().getText().toUpperCase(Locale.ROOT);
                // LNG-START$ は「この頁にもう何か置いたか」である。LINAGE-COUNTER だけでは
                // 足りない。開いた直後も頁を送った直後も 1 だが、前者はまだ何も置いていない
                for (String prefix : List.of("LNG-PAGE$", "LNG-FOOT$", "LNG-TOP$",
                        "LNG-BOTTOM$", "LNG-START$")) {
                    DataItem slot = new DataItem(INDEPENDENT_LEVEL, prefix + file, originOf(fd));
                    slot.setPicture(PictureParser.parse(INDEX_PICTURE));
                    slot.setUsage(Usage.COMP);
                    records.add(slot);
                }
            }
        }
    }

    // ---- 報告書節 (要件 FR-214) ----

    /** 欄が何も無い報告書でも、行を 1 本は置けるだけの幅を取る。 */
    private static final int MINIMUM_REPORT_WIDTH = 1;

    /**
     * 報告書節を、普通の記述と数え札へ落とす (要件 FR-214)。
     *
     * <p>報告書作成機能に専用の実行時機構は<b>持たない</b>。行の姿は普通のレコード記述、
     * 行を置く場所の計算は普通の算術、書き出しは普通の {@code WRITE} である。要件 C-4 が
     * 言う「プリプロセッサ方式」を、意味解析の段でやっている。こうすると編集も詰め方も
     * 行送りも、<b>すでに外の基準で確かめた道</b>をそのまま通る。
     *
     * <p>作るのは次のものである。
     * <ul>
     *   <li>{@code LINE-COUNTER} / {@code PAGE-COUNTER} — 規格が決めた特殊レジスタ。
     *       報告書ごとに 1 つなので、2 つ以上あれば修飾しないと引けない</li>
     *   <li>{@code RW-TGT$r} {@code RW-ADV$r} {@code RW-ON$r} {@code RW-EJ$r} — 作業用。
     *       名前に {@code $} を含むので、書かれた名前とはぶつからない</li>
     *   <li>行ごとの 01 レベル。{@code FD} のレコード領域として置く</li>
     * </ul>
     */
    private void addReports(CobolParser.ProgramUnitContext program) {
        if (program.dataDivision() == null) {
            return;
        }
        Map<String, String> fileOfReport = reportFiles(program);
        for (CobolParser.DataDivisionSectionContext section
                : program.dataDivision().dataDivisionSection()) {
            if (section.reportSection() == null) {
                continue;
            }
            for (CobolParser.ReportDescriptionEntryContext rd
                    : section.reportSection().reportDescriptionEntry()) {
                addReport(rd, fileOfReport);
            }
        }
    }

    /** {@code FD} の {@code REPORT IS} 句から「報告書 → ファイル」の対応を作る。 */
    private Map<String, String> reportFiles(CobolParser.ProgramUnitContext program) {
        Map<String, String> files = new LinkedHashMap<>();
        for (CobolParser.DataDivisionSectionContext section
                : program.dataDivision().dataDivisionSection()) {
            if (section.fileSection() == null) {
                continue;
            }
            for (CobolParser.FileDescriptionEntryContext fd
                    : section.fileSection().fileDescriptionEntry()) {
                String file = fd.IDENTIFIER().getText().toUpperCase(Locale.ROOT);
                for (String report : reportNamesOf(fd)) {
                    files.put(report, file);
                }
            }
        }
        return files;
    }

    private static List<String> reportNamesOf(CobolParser.FileDescriptionEntryContext fd) {
        List<String> names = new ArrayList<>();
        for (CobolParser.FileDescriptionClauseContext clause : fd.fileDescriptionClause()) {
            if (clause.REPORT() == null && clause.REPORTS() == null) {
                continue;
            }
            for (org.antlr.v4.runtime.tree.TerminalNode name : clause.IDENTIFIER()) {
                names.add(name.getText().toUpperCase(Locale.ROOT));
            }
        }
        return names;
    }

    private void addReport(CobolParser.ReportDescriptionEntryContext rd,
                           Map<String, String> fileOfReport) {
        Origin origin = originOf(rd);
        String name = rd.IDENTIFIER().getText().toUpperCase(Locale.ROOT);
        String file = fileOfReport.get(name);
        if (file == null) {
            report(origin, "report " + name + " is not named in any FD REPORT clause");
            return;
        }
        for (CobolParser.ReportDescriptionClauseContext clause : rd.reportDescriptionClause()) {
            if (clause.CONTROL() != null || clause.CONTROLS() != null) {
                // 制御の切れ目で小計を出す仕組みは、まだ書けない。
                // 近いものを黙って出すより断るほうがよい
                report(origin, "a report with CONTROL breaks is not supported yet: " + name);
                return;
            }
        }
        ReportDescription.PageShape page = pageShapeOf(rd, origin);
        if (page == null) {
            return;
        }
        List<ReportGroup> groups = new ArrayList<>();
        List<PlannedLine> planned = new ArrayList<>();
        if (!collectGroups(rd, name, groups, planned, origin)) {
            return;
        }
        int width = reportWidth(planned, file);
        for (PlannedLine line : planned) {
            addLineRecord(line, width, file, origin);
        }
        reports.add(new ReportDescription(name, file, page, groups,
                addReportRegisters(name, origin), origin));
    }

    /** {@code PAGE} 句。書かれなければ紙の切れ目を持たない報告書になる。 */
    private ReportDescription.PageShape pageShapeOf(
            CobolParser.ReportDescriptionEntryContext rd, Origin origin) {
        Integer limit = null;
        Integer heading = null;
        Integer firstDetail = null;
        Integer lastDetail = null;
        Integer footing = null;
        for (CobolParser.ReportDescriptionClauseContext clause : rd.reportDescriptionClause()) {
            if (clause.PAGE() == null) {
                continue;
            }
            limit = Integer.parseInt(clause.NUMBER().getText());
            for (CobolParser.PageDetailClauseContext part : clause.pageDetailClause()) {
                int n = Integer.parseInt(part.NUMBER().getText());
                if (part.HEADING() != null) {
                    heading = n;
                } else if (part.FIRST() != null) {
                    firstDetail = n;
                } else if (part.LAST() != null) {
                    lastDetail = n;
                } else {
                    footing = n;
                }
            }
        }
        if (limit == null) {
            // 紙の大きさが分からなければ、どこで頁を改めるかも決まらない
            limit = Integer.MAX_VALUE;
        }
        if (limit <= 0) {
            report(origin, "PAGE LIMIT must be positive: " + limit);
            return null;
        }
        return ReportDescription.PageShape.of(limit, heading, firstDetail, lastDetail, footing);
    }

    /** 割り付ける前の行。まだ記憶域を持たない。 */
    private record PlannedLine(String recordName, List<PlannedField> fields) {
    }

    /** 割り付ける前の欄。 */
    private record PlannedField(String slotName, int column, Picture picture,
                                LiteralValue value, Origin origin) {
    }

    /**
     * 報告集団を集める。
     *
     * <p>集団は 01 レベルで始まる。行は {@code LINE} 句が現れるたびに始まり、続く欄は
     * その行に属する。{@code LINE} 句を持つ項目そのものが欄でもありうる。
     *
     * @return 組み立てられたら {@code true}
     */
    private boolean collectGroups(CobolParser.ReportDescriptionEntryContext rd, String report,
                                  List<ReportGroup> groups, List<PlannedLine> planned,
                                  Origin origin) {
        String groupName = null;
        ReportGroup.Type type = null;
        Origin groupOrigin = null;
        List<ReportGroup.ReportLine> lines = new ArrayList<>();
        List<ReportGroup.ReportField> fields = new ArrayList<>();
        List<PlannedField> slots = new ArrayList<>();
        ReportGroup.Placement placement = null;

        for (CobolParser.ReportGroupEntryContext entry : rd.reportGroupEntry()) {
            Origin at = originOf(entry);
            int level = Integer.parseInt(entry.levelNumber().getText());
            ReportGroup.Placement written = placementOf(entry, at);
            if (level == 1) {
                if (!closeGroup(groupName, type, groupOrigin, placement, lines, fields, slots,
                        planned, report, groups, origin)) {
                    return false;
                }
                lines = new ArrayList<>();
                fields = new ArrayList<>();
                slots = new ArrayList<>();
                placement = written;
                groupName = entry.dataName() == null
                        ? null
                        : entry.dataName().getText().toUpperCase(Locale.ROOT);
                groupOrigin = at;
                type = typeOf(entry, at);
                if (type == null) {
                    return false;
                }
                continue;
            }
            if (type == null) {
                report(at, "a report group entry appears before any 01 report group");
                return false;
            }
            if (written != null) {
                if (placement != null && !fields.isEmpty()) {
                    closeLine(placement, report, groupName, lines, fields, slots, planned);
                    fields = new ArrayList<>();
                    slots = new ArrayList<>();
                }
                placement = written;
            }
            if (!addField(entry, report, groupName, lines.size(), fields, slots, at)) {
                return false;
            }
        }
        return closeGroup(groupName, type, groupOrigin, placement, lines, fields, slots,
                planned, report, groups, origin);
    }

    private boolean closeGroup(String groupName, ReportGroup.Type type, Origin groupOrigin,
                               ReportGroup.Placement placement,
                               List<ReportGroup.ReportLine> lines,
                               List<ReportGroup.ReportField> fields, List<PlannedField> slots,
                               List<PlannedLine> planned, String report,
                               List<ReportGroup> groups, Origin origin) {
        if (type == null) {
            return true;
        }
        if (!fields.isEmpty() || placement != null) {
            if (placement == null) {
                report(origin, "a report group line needs a LINE clause: " + groupName);
                return false;
            }
            closeLine(placement, report, groupName, lines, fields, slots, planned);
        }
        groups.add(new ReportGroup(groupName, type, lines, groupOrigin));
        return true;
    }

    private void closeLine(ReportGroup.Placement placement, String report, String groupName,
                           List<ReportGroup.ReportLine> lines,
                           List<ReportGroup.ReportField> fields, List<PlannedField> slots,
                           List<PlannedLine> planned) {
        String recordName = "RW-L$" + report + "$" + groupName + "$" + lines.size();
        planned.add(new PlannedLine(recordName, List.copyOf(slots)));
        lines.add(new ReportGroup.ReportLine(placement, recordName, List.copyOf(fields)));
    }

    private ReportGroup.Placement placementOf(CobolParser.ReportGroupEntryContext entry,
                                              Origin origin) {
        for (CobolParser.ReportGroupClauseContext clause : entry.reportGroupClause()) {
            CobolParser.LineNumberClauseContext line = clause.lineNumberClause();
            if (line == null) {
                continue;
            }
            if (line.NEXT() != null) {
                return new ReportGroup.Placement(ReportGroup.Placement.Kind.NEXT_PAGE, 0);
            }
            int n = Integer.parseInt(line.NUMBER().getText());
            return new ReportGroup.Placement(line.PLUS() == null
                    ? ReportGroup.Placement.Kind.ABSOLUTE
                    : ReportGroup.Placement.Kind.RELATIVE, n);
        }
        return null;
    }

    private ReportGroup.Type typeOf(CobolParser.ReportGroupEntryContext entry, Origin origin) {
        for (CobolParser.ReportGroupClauseContext clause : entry.reportGroupClause()) {
            if (clause.typeClause() == null) {
                continue;
            }
            CobolParser.ReportGroupTypeContext type = clause.typeClause().reportGroupType();
            if (type.CONTROL() != null) {
                report(origin, "CONTROL HEADING and CONTROL FOOTING are not supported yet");
                return null;
            }
            if (type.DETAIL() != null) {
                return ReportGroup.Type.DETAIL;
            }
            if (type.REPORT() != null) {
                return type.HEADING() != null
                        ? ReportGroup.Type.REPORT_HEADING
                        : ReportGroup.Type.REPORT_FOOTING;
            }
            if (type.PAGE() != null) {
                return type.HEADING() != null
                        ? ReportGroup.Type.PAGE_HEADING
                        : ReportGroup.Type.PAGE_FOOTING;
            }
            // 略記。2 文字の語を予約語にしないでおくために、ここで見分ける
            String word = type.IDENTIFIER().getText().toUpperCase(Locale.ROOT);
            ReportGroup.Type abbreviated = switch (word) {
                case "RH" -> ReportGroup.Type.REPORT_HEADING;
                case "PH" -> ReportGroup.Type.PAGE_HEADING;
                case "DE" -> ReportGroup.Type.DETAIL;
                case "PF" -> ReportGroup.Type.PAGE_FOOTING;
                case "RF" -> ReportGroup.Type.REPORT_FOOTING;
                case "CH", "CF" -> null;
                default -> null;
            };
            if (abbreviated == null) {
                report(origin, "unknown report group TYPE: " + word);
            }
            return abbreviated;
        }
        report(origin, "a report group needs a TYPE clause");
        return null;
    }

    /** 欄 1 個。{@code COLUMN} と {@code PICTURE} を持つ項目だけが紙に文字を置く。 */
    private boolean addField(CobolParser.ReportGroupEntryContext entry, String report,
                             String groupName, int lineIndex,
                             List<ReportGroup.ReportField> fields, List<PlannedField> slots,
                             Origin origin) {
        Integer column = null;
        Picture picture = null;
        CobolParser.IdentifierContext source = null;
        LiteralValue value = null;
        for (CobolParser.ReportGroupClauseContext clause : entry.reportGroupClause()) {
            if (clause.columnNumberClause() != null) {
                column = Integer.parseInt(clause.columnNumberClause().NUMBER().getText());
            } else if (clause.pictureClause() != null) {
                picture = pictureOf(clause.pictureClause(), origin);
            } else if (clause.sourceClause() != null) {
                source = clause.sourceClause().identifier();
            } else if (clause.valueClause() != null) {
                value = literalOf(clause.valueClause().valueRange(0).literal(0), origin);
            } else if (clause.sumClause() != null) {
                report(origin, "SUM counters in a report are not supported yet");
                return false;
            }
        }
        if (column == null) {
            // COLUMN を書かない項目は紙に文字を置かない。行の入れ物にすぎない
            return true;
        }
        if (picture == null) {
            report(origin, "a report field with COLUMN needs a PICTURE");
            return false;
        }
        if (source == null && value == null) {
            report(origin, "a report field needs SOURCE or VALUE");
            return false;
        }
        String slotName = "RW-F$" + report + "$" + groupName + "$" + lineIndex
                + "$" + fields.size();
        slots.add(new PlannedField(slotName, column, picture, value, origin));
        fields.add(new ReportGroup.ReportField(slotName, source, value, origin));
        return true;
    }

    private Picture pictureOf(CobolParser.PictureClauseContext clause, Origin origin) {
        try {
            return PictureParser.parse(clause.PICTURE_STRING().getText(),
                    specialNames.currency(), specialNames.decimalPoint());
        } catch (RuntimeException e) {
            report(origin, "invalid PICTURE character-string: " + e.getMessage());
            return null;
        }
    }

    /**
     * 行の幅。
     *
     * <p>{@code FD} にレコードの長さが書かれていればそれ、書かれていなければ
     * <b>いちばん右まで届く欄</b>で決める (暫定判断 P-077)。実機の報告書ファイルの
     * レコード長を突き合わせていないので、書かれた欄が収まる幅を採っている。
     */
    private int reportWidth(List<PlannedLine> planned, String file) {
        int width = MINIMUM_REPORT_WIDTH;
        for (PlannedLine line : planned) {
            for (PlannedField field : line.fields()) {
                width = Math.max(width, field.column() + field.picture().size() - 1);
            }
        }
        for (DataItem record : fileRecords.getOrDefault(file, List.of())) {
            width = Math.max(width, record.length());
        }
        return width;
    }

    /** 行 1 本を、そのファイルのレコード領域として置く。 */
    private void addLineRecord(PlannedLine line, int width, String file, Origin origin) {
        DataItem record = new DataItem(1, line.recordName(), origin);
        record.setSection(DataSection.FILE);
        record.setFileName(file);
        List<PlannedField> sorted = new ArrayList<>(line.fields());
        sorted.sort((a, b) -> Integer.compare(a.column(), b.column()));
        int at = 1;
        for (PlannedField field : sorted) {
            if (field.column() < at) {
                report(field.origin(), "report fields overlap at column " + field.column());
                return;
            }
            if (field.column() > at) {
                record.addChild(filler(field.column() - at, origin));
            }
            DataItem slot = new DataItem(5, field.slotName(), field.origin());
            slot.setPicture(field.picture());
            slot.setSection(DataSection.FILE);
            slot.setFileName(file);
            record.addChild(slot);
            at = field.column() + field.picture().size();
        }
        if (at <= width) {
            record.addChild(filler(width - at + 1, origin));
        }
        records.add(record);
        fileRecords.computeIfAbsent(file, k -> new ArrayList<>()).add(record);
    }

    private DataItem filler(int size, Origin origin) {
        DataItem gap = new DataItem(5, null, origin);
        gap.setPicture(PictureParser.parse("X(" + size + ")"));
        gap.setSection(DataSection.FILE);
        return gap;
    }

    /**
     * 報告書ごとの数え札と作業用の項目。
     *
     * <p>{@code LINE-COUNTER} と {@code PAGE-COUNTER} は規格が決めた特殊レジスタで、
     * <b>報告書ごとに 1 つ</b>である。2 つ以上の報告書があれば同じ名前の項目が 2 つでき、
     * 修飾せずに書けば「あいまいだ」と断ることになる。規格の決まりどおりである。
     */
    private ReportDescription.Registers addReportRegisters(String report, Origin origin) {
        return new ReportDescription.Registers(
                reportSlot("LINE-COUNTER", INDEX_PICTURE, origin),
                reportSlot("PAGE-COUNTER", INDEX_PICTURE, origin),
                // 行送りの数は改頁を負の数で表すので、符号が要る
                reportSlot("RW-TGT$" + report, "S9(9)", origin),
                reportSlot("RW-ADV$" + report, "S9(9)", origin),
                reportSlot("RW-ON$" + report, "9", origin),
                reportSlot("RW-EJ$" + report, "9", origin));
    }

    private DataItem reportSlot(String name, String picture, Origin origin) {
        DataItem slot = new DataItem(INDEPENDENT_LEVEL, name, origin);
        slot.setPicture(PictureParser.parse(picture));
        slot.setUsage(Usage.COMP);
        records.add(slot);
        return slot;
    }

    /**
     * {@code DEBUG-ITEM} の実体を作る (要件 FR-193)。
     *
     * <p>データ部のどこにも書かれないが、{@code WITH DEBUGGING MODE} を書いて
     * デバッグの節を置けば存在する。桁割りは規格が決めている。
     *
     * <pre>
     * 01 DEBUG-ITEM.
     *    02 DEBUG-LINE     PIC X(6).
     *    02 FILLER         PIC X.
     *    02 DEBUG-NAME     PIC X(30).
     *    02 FILLER         PIC X.
     *    02 DEBUG-SUB-1    PIC S9(4) SIGN LEADING SEPARATE.
     *    02 FILLER         PIC X.
     *    02 DEBUG-SUB-2    PIC S9(4) SIGN LEADING SEPARATE.
     *    02 FILLER         PIC X.
     *    02 DEBUG-SUB-3    PIC S9(4) SIGN LEADING SEPARATE.
     *    02 FILLER         PIC X.
     *    02 DEBUG-CONTENTS PIC X(n).
     * </pre>
     *
     * <p>{@code DEBUG-CONTENTS} の長さは規格が決めていない。参照実装に合わせて
     * 見ていないので、印字して読めるだけの幅として 30 桁を採った (暫定判断 P-079)。
     */
    private void addDebugItem(CobolParser.ProgramUnitContext program) {
        if (!specialNames.debuggingMode() || !hasDebuggingDeclarative(program)) {
            return;
        }
        Origin origin = originOf(program);
        DataItem item = new DataItem(1, "DEBUG-ITEM", origin);
        addDebugField(item, "DEBUG-LINE", "X(6)", origin);
        addDebugField(item, null, "X", origin);
        addDebugField(item, "DEBUG-NAME", "X(30)", origin);
        addDebugField(item, null, "X", origin);
        for (int i = 1; i <= 3; i++) {
            DataItem sub = addDebugField(item, "DEBUG-SUB-" + i, "S9(4)", origin);
            sub.setSignPosition(SignPosition.LEADING_SEPARATE);
            addDebugField(item, null, "X", origin);
        }
        addDebugField(item, "DEBUG-CONTENTS", "X(" + debugContentsSize(program) + ")", origin);
        records.add(item);

        // 制御を移した文の行番号の置き場。DEBUG-LINE はここから写す。
        // 名前に $ を含むので、書かれた名前とはぶつからない
        DataItem line = new DataItem(INDEPENDENT_LEVEL, DEBUG_LINE_SLOT, origin);
        line.setPicture(PictureParser.parse("X(6)"));
        records.add(line);

        // <b>なぜその手続きへ来たか</b>の置き場。DEBUG-CONTENTS はここから写す
        DataItem reason = new DataItem(INDEPENDENT_LEVEL, DEBUG_REASON_SLOT, origin);
        reason.setPicture(PictureParser.parse("X(" + DEBUG_REASON_SIZE + ")"));
        records.add(reason);
    }

    /** なぜその手続きへ来たかの置き場の名前 (要件 FR-193)。 */
    public static final String DEBUG_REASON_SLOT = "DBG-WHY$";

    /** その桁数。いちばん長いのは {@code START PROGRAM} と {@code USE PROCEDURE} である。 */
    public static final int DEBUG_REASON_SIZE = 13;

    /** 制御を移した文の行番号を置く項目の名前 (要件 FR-193)。 */
    public static final String DEBUG_LINE_SLOT = "DBG-LINE$";

    /** {@code DEBUG-CONTENTS} の最小の桁数 (暫定判断 P-079)。 */
    private static final int DEBUG_CONTENTS_SIZE = 30;

    /**
     * {@code DEBUG-LINE} に入れる 6 桁の行番号 (要件 FR-193)。
     *
     * <p>規格は中身を「その文の識別子」としか決めていない。翻訳系が数えた原文の行を
     * 右詰めで入れる (暫定判断 P-080)。
     */
    public static String debugLine(Origin origin) {
        String text = Integer.toString(origin.line());
        return text.length() >= 6
                ? text.substring(text.length() - 6)
                : " ".repeat(6 - text.length()) + text;
    }

    /**
     * {@code DEBUG-CONTENTS} の桁数を決める (要件 FR-193)。
     *
     * <p>規格は桁数を決めていない。決めるのは翻訳系である。<b>そこへ入りうるいちばん
     * 大きいもの</b>が入る幅にする。ファイル名を見張れば {@code READ} のたびに読んだ
     * レコードが入るので、そのファイルのレコード領域が下限になる。
     *
     * <p>レコードの長さは割り付けを済ませないと分からない。ここで一度割り付けるが、
     * <b>そのとき出た診断は捨てる</b>。本番の割り付けで同じことをもう一度言うからである。
     */
    private int debugContentsSize(CobolParser.ProgramUnitContext program) {
        int size = DEBUG_CONTENTS_SIZE;
        for (String name : watchedFileNames(program)) {
            List<DataItem> area = fileRecords.get(name);
            if (area == null) {
                continue;
            }
            for (DataItem record : area) {
                int before = diagnostics.size();
                inheritUsage(record, null, false);
                size = Math.max(size, layout(record, 0));
                while (diagnostics.size() > before) {
                    diagnostics.remove(diagnostics.size() - 1);
                }
            }
        }
        return size;
    }

    /** {@code USE FOR DEBUGGING ON} に書かれた名前のうち、ファイルのもの。 */
    private Set<String> watchedFileNames(CobolParser.ProgramUnitContext program) {
        Set<String> found = new LinkedHashSet<>();
        if (program.procedureDivision() == null
                || program.procedureDivision().procedureBody() == null
                || program.procedureDivision().procedureBody().declarativesPart() == null) {
            return found;
        }
        for (CobolParser.DeclarativeSectionContext section
                : program.procedureDivision().procedureBody().declarativesPart()
                        .declarativeSection()) {
            if (section.useStatement().debugTarget() == null) {
                continue;
            }
            for (CobolParser.DebugItemContext item : section.useStatement().debugTarget()
                    .debugItem()) {
                if (item.PROCEDURES() != null || item.identifier() != null) {
                    continue;
                }
                String written = item.IDENTIFIER().getText().toUpperCase(Locale.ROOT);
                if (fileRecords.containsKey(written)) {
                    found.add(written);
                }
            }
        }
        return found;
    }

    private DataItem addDebugField(DataItem parent, String name, String picture, Origin origin) {
        DataItem field = new DataItem(2, name, origin);
        field.setPicture(PictureParser.parse(picture));
        parent.addChild(field);
        return field;
    }

    /** デバッグの節が書かれているか。 */
    private static boolean hasDebuggingDeclarative(CobolParser.ProgramUnitContext program) {
        if (program.procedureDivision() == null
                || program.procedureDivision().procedureBody() == null
                || program.procedureDivision().procedureBody().declarativesPart() == null) {
            return false;
        }
        for (CobolParser.DeclarativeSectionContext section
                : program.procedureDivision().procedureBody().declarativesPart()
                        .declarativeSection()) {
            if (section.useStatement().debugTarget() != null) {
                return true;
            }
        }
        return false;
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
        Map<String, DataItem> registers = new LinkedHashMap<>();
        registers.put("RETURN-CODE", returnCode);
        // 日時・task番号・端末のfieldは、出どころの無いときbinary zeroのままである (暫定判断 P-113)
        registers.put("EIBTIME", eibItem("EIBTIME", "S9(7)", Usage.COMP_3,
                CicsEib.EIBTIME_OFFSET));
        registers.put("EIBDATE", eibItem("EIBDATE", "S9(7)", Usage.COMP_3,
                CicsEib.EIBDATE_OFFSET));
        registers.put("EIBTRNID", eibItem("EIBTRNID", "X(4)", Usage.DISPLAY,
                CicsEib.EIBTRNID_OFFSET));
        registers.put("EIBTASKN", eibItem("EIBTASKN", "S9(7)", Usage.COMP_3,
                CicsEib.EIBTASKN_OFFSET));
        registers.put("EIBTRMID", eibItem("EIBTRMID", "X(4)", Usage.DISPLAY,
                CicsEib.EIBTRMID_OFFSET));
        registers.put("EIBCPOSN", eibItem("EIBCPOSN", "S9(4)", Usage.COMP,
                CicsEib.EIBCPOSN_OFFSET));
        registers.put("EIBCALEN", eibItem("EIBCALEN", "S9(4)", Usage.COMP,
                CicsEib.EIBCALEN_OFFSET));
        registers.put("EIBAID", eibItem("EIBAID", "X", Usage.DISPLAY,
                CicsEib.EIBAID_OFFSET));
        registers.put("EIBFN", eibItem("EIBFN", "X(2)", Usage.DISPLAY,
                CicsEib.EIBFN_OFFSET));
        registers.put("EIBRCODE", eibItem("EIBRCODE", "X(6)", Usage.DISPLAY,
                CicsEib.EIBRCODE_OFFSET));
        registers.put("EIBRESP", eibItem("EIBRESP", "S9(8)", Usage.COMP,
                CicsEib.EIBRESP_OFFSET));
        registers.put("EIBRESP2", eibItem("EIBRESP2", "S9(8)", Usage.COMP,
                CicsEib.EIBRESP2_OFFSET));
        return Map.copyOf(registers);
    }

    private static DataItem eibItem(String name, String pictureText, Usage usage, int offset) {
        DataItem item = new DataItem(INDEPENDENT_LEVEL, name, null);
        Picture picture = PictureParser.parse(pictureText);
        item.setPicture(picture);
        item.setUsage(usage);
        item.setSection(DataSection.CICS_EIB);
        item.setOffset(offset);
        item.setLength(picture.isNumeric()
                ? NumericItem.of(pictureText, usage).byteLength()
                : picture.size());
        item.markReadOnly();
        return item;
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
    /**
     * {@code SAME [RECORD] AREA} で結ばれたファイルの代表 (要件 FR-100)。
     *
     * <p>結ばれたファイルの<b>レコード領域は 1 つ</b>である。片方へ読み込めば、
     * もう片方の記述でそのまま読める。CCVS85 の SG204A / ST131A は
     * {@code READ FILE3} のあと {@code RELEASE S3} と書いており、間の転記が無い。
     * 領域を分けて取ると、releases されるのは空白のままの領域になる。
     */
    private final Map<String, String> sharedArea = new LinkedHashMap<>();

    /**
     * {@code I-O-CONTROL} の {@code SAME AREA} を読む。
     *
     * <p>{@code SAME AREA} と {@code SAME RECORD AREA} を分けていない。前者は
     * 記憶域そのものを共有し、後者はレコード領域だけを共有するという違いだが、
     * <b>プログラムから見えるのはどちらもレコード領域が 1 つであること</b>である。
     * {@code SAME SORT AREA} は整列の作業域の話であり、レコード領域には効かない。
     */
    private void addSameAreas(CobolParser.ProgramUnitContext program) {
        if (program.environmentDivision() == null
                || program.environmentDivision().inputOutputSection() == null
                || program.environmentDivision().inputOutputSection()
                        .ioControlParagraph() == null) {
            return;
        }
        for (CobolParser.IoControlEntryContext entry : program.environmentDivision()
                .inputOutputSection().ioControlParagraph().ioControlEntry()) {
            if (entry.SAME() == null || entry.SORT() != null || entry.SORT_MERGE() != null) {
                continue;
            }
            String first = null;
            for (org.antlr.v4.runtime.tree.TerminalNode name : entry.IDENTIFIER()) {
                String written = name.getText().toUpperCase(Locale.ROOT);
                if (first == null) {
                    // すでに別の組に入っていれば、その代表へ寄せる。
                    // SAME を 2 行書いて 1 本を共有させる形があるからである
                    first = sharedArea.getOrDefault(written, written);
                }
                sharedArea.put(written, first);
            }
        }
    }

    private void layoutRecords() {
        int base = 0;
        Map<String, Integer> fileBases = new LinkedHashMap<>();
        for (DataItem record : records) {
            inheritUsage(record, null, false);
            // USAGE が決まってからでないと、SIGN が効く項目かどうかを判じられない
            inheritSign(record, SignPosition.UNSIGNED);
            layout(record, 0);
            if (record.section() == DataSection.LINKAGE) {
                continue;
            }
            if (record.section() == DataSection.FILE) {
                // 同じ FD のレコード記述は重なる。どれも 1 つのバッファの別の切り方である
                String area = sharedArea.getOrDefault(record.fileName(), record.fileName());
                Integer at = fileBases.get(area);
                if (at == null) {
                    fileBases.put(area, base);
                    record.setBase(base);
                    base += record.totalLength();
                } else {
                    record.setBase(at);
                    base = Math.max(base, at + record.totalLength());
                }
                continue;
            }
            if (record.redefinesName() != null) {
                // 01 レベルの REDEFINES は重ねる先と同じ位置から始まる。ただし
                // <b>重ねる先より長くてよい</b> —— 01 レベルでファイル節の外なら、
                // 規格がそれを許している。長ければ、そのぶん記憶域を広げなければ
                // ならない。広げないと、次の 01 レベルが重なって<b>黙って壊れる</b>
                // (CCVS85 の NC107A: MOVE SPACE TO REDEF12 が次の REDEF13 を潰していた)
                DataItem target = redefinedRecord(record);
                int at = target == null ? base : target.base();
                record.setBase(at);
                base = Math.max(base, at + record.totalLength());
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
            } else if (item.isPointer()) {
                applyPointerUsage(item);
            } else if (item.usage() == null && usage != null) {
                item.setUsage(usage);
            }
            return;
        }
        if (item.isPointer()) {
            report(item.origin(), "USAGE POINTER on a group item is not supported yet: " + describe(item));
            return;
        }
        for (DataItem child : item.children()) {
            inheritUsage(child, usage, index);
        }
    }

    /**
     * 群項目に書いた {@code SIGN} を下位へ配る (要件 FR-021)。
     *
     * <p>{@code SIGN} は群項目にも書ける。書けば<b>配下の、符号つきで
     * {@code USAGE DISPLAY} の基本項目すべて</b>に効く。ただし内側の群項目や基本項目が
     * 自分の {@code SIGN} を持っていれば<b>そちらが勝つ</b> (85 規格 VI-42 5.12.4 GR2)。
     *
     * <pre>
     * 01 TEST-17-DATA  SIGN TRAILING.
     *   03 TEST-17-GROUP SIGN LEADING SEPARATE.
     *     05 TEST-17-C   PIC S9(4).
     * </pre>
     *
     * <p>TEST-17-C は 5 バイトになり、先頭が符号の文字である。外側の
     * {@code SIGN TRAILING} だけを見ると 4 バイトになり、<b>長さから違う</b>
     * (NC116A SIG-TEST-GF-17)。
     *
     * <p>効くのは符号つきの表示形式の数字項目だけである。ほかの項目に配ると
     * 長さの計算まで変わってしまう。
     */
    private void inheritSign(DataItem item, SignPosition inherited) {
        SignPosition sign = item.signPosition() == SignPosition.UNSIGNED
                ? inherited
                : item.signPosition();
        if (item.isElementary()) {
            if (item.signPosition() == SignPosition.UNSIGNED
                    && sign != SignPosition.UNSIGNED && signable(item)) {
                item.setSignPosition(sign);
            }
            return;
        }
        for (DataItem child : item.children()) {
            inheritSign(child, sign);
        }
    }

    /** {@code SIGN} が効く項目か。符号つきで、記憶域に文字で持つ数字項目だけである。 */
    private static boolean signable(DataItem item) {
        return item.picture() != null && item.picture().isNumeric()
                && item.picture().signPosition().isSigned()
                && (item.usage() == null || item.usage() == Usage.DISPLAY);
    }

    /**
     * 項目とその下位に位置を割り当て、<b>手前に入れた詰め物を含めて</b>何バイト進むかを返す。
     *
     * <p>詰め物が入るのは {@code SYNCHRONIZED} を書いた項目の手前だけである
     * (暫定判断 P-111)。入れる位置が 01 レベルの先頭から数えた変位で決まるので、
     * 左から右へ 1 回で歩けばよい。群のどこに埋まっていても同じ道を通る。
     */
    private int layout(DataItem item, int offset) {
        int at = offset + slackBefore(item, offset);
        item.setOffset(at);
        item.setLength(item.isElementary() ? elementaryLength(item) : groupLength(item, at));
        return at - offset + item.totalLength();
    }

    /**
     * {@code SYNCHRONIZED} を書いた項目の手前に入れる詰め物のバイト数 (要件 FR-021)。
     *
     * <p>{@code REDEFINES} で重ねた項目には入れない。重ねる先と<b>同じ位置から</b>
     * 始まらなければ、重ねた意味がなくなる。
     */
    private static int slackBefore(DataItem item, int offset) {
        int boundary = alignmentOf(item);
        return boundary == 1 || item.redefinesName() != null
                ? 0
                : (boundary - offset % boundary) % boundary;
    }

    /**
     * 境界に合わせる幅 (要件 FR-021、暫定判断 P-111)。合わせない項目は 1 である。
     *
     * <p>効くのは<b>2 進・浮動小数・指標</b>の項目だけである。表示形式とパック 10 進では
     * {@code SYNCHRONIZED} を書いても割り付けが変わらない。{@code SYNCHRONIZED} を
     * 書いていない項目も 1 であり、書いたときだけ位置が動く。
     *
     * <p>2 進項目の境界は<b>その項目の大きさ</b>と同じである。桁数で 2 / 4 / 8 バイトに
     * 分かれるので、境界もそれに従う。
     */
    private static int alignmentOf(DataItem item) {
        if (!item.aligned() || !item.isElementary() || item.usage() == null) {
            return 1;
        }
        return switch (item.usage()) {
            case COMP, COMP_5 -> item.picture() == null
                    ? 1
                    : BinaryDecimal.byteLength(item.picture().digits());
            case COMP_1 -> 4;
            case COMP_2 -> 8;
            default -> 1;
        };
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
        return paddedForOccurs(item, end - offset);
    }

    /**
     * 繰り返す群の 1 回分の長さを、境界へ合うように伸ばす (要件 FR-021、暫定判断 P-111)。
     *
     * <p>{@code OCCURS} を書いた群の中に {@code SYNCHRONIZED} の項目があると、
     * 2 回目以降の回が<b>ずれた位置から始まる</b>。1 回分の長さを、群の中でいちばん
     * 大きい境界の倍数まで伸ばして揃える。詰め物は 1 回分の<b>末尾</b>に入る。
     *
     * <p>繰り返さない群には入れない。入れると、そのぶん親の長さが伸びてしまう。
     */
    private static int paddedForOccurs(DataItem item, int length) {
        if (item.occurs() <= 1) {
            return length;
        }
        int boundary = widestAlignment(item);
        return boundary == 1 ? length : (length + boundary - 1) / boundary * boundary;
    }

    /** 配下でいちばん大きい境界。合わせる項目が無ければ 1 である。 */
    private static int widestAlignment(DataItem item) {
        int widest = alignmentOf(item);
        for (DataItem child : item.children()) {
            widest = Math.max(widest, widestAlignment(child));
        }
        return widest;
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

    /**
     * 数値項目の長さ。ランタイムの記述子にそのまま尋ねる。
     *
     * <p>断るときは<b>何を読んだのか</b>まで書く。ランタイムの例文は数値だけを持って
     * いて、どの項目のどの PICTURE でそうなったかを知らない。{@code PIC P} のように
     * 桁を 1 つも持たない書き方は規格が許していないが、「digits must be positive: 0」
     * だけでは原文のどこが悪いのか読み取れない。
     */
    private int numericLength(DataItem item, Picture picture, Usage usage) {
        try {
            NumericItem descriptor = NumericItem.of(picture.source(), usage);
            if (item.signPosition() != SignPosition.UNSIGNED) {
                descriptor = descriptor.withSignPosition(item.signPosition());
            }
            return descriptor.byteLength();
        } catch (RuntimeException e) {
            report(item.origin(), "PICTURE " + picture.source() + " cannot describe "
                    + (item.name() == null ? "FILLER" : item.name()) + ": " + e.getMessage());
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
