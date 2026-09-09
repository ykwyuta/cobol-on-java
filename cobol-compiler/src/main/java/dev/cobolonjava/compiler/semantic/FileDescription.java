package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.Origin;
import dev.cobolonjava.runtime.file.Organization;
import dev.cobolonjava.runtime.file.RecordFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ファイルの宣言 (要件 FR-100, FR-102, FR-103)。
 *
 * <p>{@code SELECT} 句 (環境部) と {@code FD} (データ部) の 2 か所に分かれて書かれるものを、
 * 1 つにまとめる。<b>どちらが欠けてもファイルは使えない</b> — 場所を知らなければ開けず、
 * レコードの形を知らなければ読み書きできない。
 *
 * @param name         {@code SELECT} と {@code FD} に書かれたファイル名
 * @param ddName       {@code ASSIGN TO} に書かれた DD 名
 * @param organization ファイル編成。レコードをどう探すかを決める
 * @param format       レコード様式。どこでレコードが切れるかを決める
 * @param access       アクセス様式。文の意味がこれで変わる
 * @param status       {@code FILE STATUS} の項目。書かれていなければ {@code null}
 * @param optional     {@code SELECT OPTIONAL}。ないファイルを開いてもよい
 * @param relativeKey  {@code RELATIVE KEY} の項目。相対編成以外では {@code null}
 * @param keys         索引編成の鍵。先頭が主鍵、以降が副鍵。ほかの編成では空
 * @param sort         {@code SD} で書かれた整列作業ファイルか
 * @param records      {@code FD} 配下のレコード記述。すべて同じ領域に重なる
 * @param recordLength レコード長。{@code FD} 配下の記述から決まる
 * @param varying      可変長の指定。固定長なら {@code null}
 * @param linage       {@code LINAGE} の指定。書かれていなければ {@code null}
 */
public record FileDescription(String name, String ddName, Organization organization,
                              RecordFormat format, Access access, DataReference status,
                              boolean optional, DataReference relativeKey, List<RecordKey> keys,
                              boolean sort, List<DataItem> records, int recordLength,
                              Varying varying, Linage linage, Origin origin) {

    /**
     * 論理頁の形 (要件 FR-113)。
     *
     * <p>紙 1 枚を「上の余白・本文・下の余白」に分ける。{@code LINAGE-COUNTER} が数えるのは
     * <b>本文の中の何行目か</b>だけであり、余白は数に入らない。
     *
     * <p>{@code FOOTING} は本文の中の行番号で、そこへ達した書き込みが
     * {@code AT END-OF-PAGE} を起こす。
     *
     * @param page    本文の行数
     * @param footing 脚注が始まる行。書かれていなければ 0
     * @param top     上の余白の行数
     * @param bottom  下の余白の行数
     * @param counter {@code LINAGE-COUNTER} の置き場
     * @param started この頁にもう何か置いたかどうかの置き場。開いた直後は 0 である
     */
    public record Linage(DataReference counter, Slot page, Slot footing, Slot top, Slot bottom,
                         Slot started) {

        /**
         * 頁の形の値 1 つ。
         *
         * <p>数で書かれていても項目で書かれていても、<b>開くたびに置き場へ写す</b>。
         * 項目で書かれた形は開くたびに読み直す決まりであり、数で書かれた形も同じ道を
         * 通せば場合分けが要らない。
         *
         * @param at     値の置き場 (隠し項目)
         * @param source 開くときにそこへ書く元。書かれていなければ 0
         */
        public record Slot(DataReference at, Operand source) {
        }
    }

    public FileDescription {
        records = List.copyOf(records);
        keys = List.copyOf(keys);
    }

    /**
     * 整列作業ファイルの宣言を検査する (要件 FR-120)。
     *
     * <p>{@code SD} が表すのは<b>データセットではなく作業場所</b>である。開くことも閉じることも
     * ないので、編成もアクセス様式もファイル状態も意味を持たない。
     */
    private static boolean checkSortWork(Selected one, DataReference status,
                                         DataReference relativeKey, List<RecordKey> keys,
                                         List<Diagnostic> diagnostics) {
        if (status != null || relativeKey != null || !keys.isEmpty()
                || one.organization() != Organization.SEQUENTIAL
                || one.access() != Access.SEQUENTIAL) {
            diagnostics.add(new Diagnostic(one.origin(), "a sort-merge file (SD) takes only "
                    + "ASSIGN; it is a work area, not a data set: " + one.name()));
            return false;
        }
        return true;
    }

    /**
     * 索引編成の鍵 (要件 FR-100)。
     *
     * <p>鍵は<b>レコードの中にある</b>。住所ではなく持ち物なので、位置はレコードの
     * 先頭からの変位になる。
     *
     * @param duplicates {@code WITH DUPLICATES}。同じ値を許すか。主鍵では常に {@code false}
     */
    public record RecordKey(DataReference reference, boolean duplicates) {

        /** レコードの先頭からの位置。 */
        public int offset() {
            return reference.constantOffset().orElse(0);
        }

        /** 鍵の長さ。 */
        public int length() {
            return reference.constantLength().orElse(0);
        }
    }

    /**
     * アクセス様式 (要件 FR-101)。
     *
     * <p>同じ {@code READ} でも様式で意味が変わる。順なら次のレコード、乱なら鍵で引く。
     * 動的はその両方を持ち、{@code READ ... NEXT} と書いたときだけ順になる。
     */
    public enum Access {

        /** 順アクセス。前から順にたどる。 */
        SEQUENTIAL,

        /** 乱アクセス。鍵で引く。 */
        RANDOM,

        /** 動的アクセス。順と乱の両方。 */
        DYNAMIC;

        /** 鍵で引く形かどうか。 */
        public boolean isKeyed() {
            return this != SEQUENTIAL;
        }
    }

    /** 鍵で引く編成かどうか。{@code START} と {@code DELETE} が書けるかが決まる。 */
    public boolean isKeyed() {
        return organization == Organization.RELATIVE || organization == Organization.INDEXED;
    }

    /**
     * 可変長レコードの指定 (要件 FR-106)。
     *
     * <p>{@code RECORD IS VARYING IN SIZE FROM n TO m DEPENDING ON 項目}。
     * <b>長さそのものがデータである</b>。書くときは項目の値がレコード長になり、
     * 読んだときは実際の長さが項目に入る。
     *
     * @param minimum  {@code FROM} の下限
     * @param maximum  {@code TO} の上限
     * @param depending {@code DEPENDING ON} の項目。省略されていれば {@code null}
     */
    public record Varying(int minimum, int maximum, DataReference depending) {
    }

    /** レコード領域の先頭。すべてのレコード記述が同じ位置から始まる。 */
    public DataItem area() {
        return records.get(0);
    }

    /** 組み立ての結果。 */
    public record Result(Map<String, FileDescription> files, List<Diagnostic> diagnostics) {

        public boolean succeeded() {
            return !Diagnostic.blocking(diagnostics);
        }
    }

    /**
     * {@code SELECT} 句だけを読んだ途中の形。
     *
     * <p>レコード長は {@code FD} を読まなければ決まらない。環境部の解析の時点では
     * データ部をまだ見ていないので、2 段に分ける。
     */
    record Selected(String name, String ddName, Organization organization, RecordFormat format,
                    Access access, boolean optional, CobolParser.IdentifierContext status,
                    CobolParser.IdentifierContext relativeKey,
                    List<SelectedKey> keys, Origin origin) {
    }

    /** {@code RECORD KEY} と {@code ALTERNATE RECORD KEY} を読んだ途中の形。 */
    record SelectedKey(CobolParser.IdentifierContext name, boolean duplicates) {
    }

    /** 環境部の {@code SELECT} 句を読む。 */
    public static List<Selected> select(CobolParser.ProgramUnitContext program,
                                        List<Diagnostic> diagnostics) {
        return select(program, List.of(), diagnostics);
    }

    /**
     * 囲む側から引き継ぐ {@code SELECT} も併せて読む (要件 FR-091)。
     *
     * <p>ファイルの記述は {@code SELECT} と {@code FD} の 2 か所に分かれている。
     * {@code FD ... GLOBAL} を引き継ぐなら、対になる {@code SELECT} も要る。
     *
     * <p>同じ名前を自分でも書いていれば<b>自分のほうが勝つ</b>。内側の宣言が外側を隠す。
     */
    public static List<Selected> select(CobolParser.ProgramUnitContext program,
                                        List<CobolParser.SelectEntryContext> inherited,
                                        List<Diagnostic> diagnostics) {
        List<Selected> out = new ArrayList<>();
        for (CobolParser.ProgramUnitContext unit : List.of(program)) {
            if (unit.environmentDivision() == null
                    || unit.environmentDivision().inputOutputSection() == null
                    || unit.environmentDivision().inputOutputSection()
                            .fileControlParagraph() == null) {
                continue;
            }
            for (CobolParser.SelectEntryContext entry : unit.environmentDivision()
                    .inputOutputSection().fileControlParagraph().selectEntry()) {
                Selected selected = selectedOf(entry, diagnostics);
                if (selected != null) {
                    out.add(selected);
                }
            }
        }
        for (CobolParser.SelectEntryContext entry : inherited) {
            Selected selected = selectedOf(entry, diagnostics);
            if (selected != null && !declares(out, selected.name())) {
                out.add(selected);
            }
        }
        return out;
    }

    /** その名前のファイルを、このプログラムが自分で書いているか。 */
    private static boolean declares(List<Selected> selected, String name) {
        for (Selected one : selected) {
            if (one.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static Selected selectedOf(CobolParser.SelectEntryContext entry,
                                       List<Diagnostic> diagnostics) {
        Origin origin = ReferenceResolver.originOf(entry);
        String name = entry.IDENTIFIER().getText().toUpperCase(Locale.ROOT);
        String ddName = ddNameOf(entry, name, origin, diagnostics);
        if (ddName == null) {
            return null;
        }

        Organization organization = Organization.SEQUENTIAL;
        RecordFormat format = RecordFormat.FIXED;
        Access access = Access.SEQUENTIAL;
        CobolParser.IdentifierContext status = null;
        CobolParser.IdentifierContext relativeKey = null;
        List<SelectedKey> keys = new ArrayList<>();
        for (CobolParser.SelectClauseContext clause : entry.selectClause()) {
            // RESERVE は ALTERNATE を含むので、副鍵より先に外す。
            // PASSWORD は名前を持つが鍵ではない。どちらも翻訳の結果には効かない
            if (clause.assignClause() != null || clause.RESERVE() != null
                    || clause.PASSWORD() != null || clause.PADDING() != null
                    || clause.DELIMITER() != null) {
                continue;
            }
            if (clause.organizationName() != null || clause.ORGANIZATION() != null) {
                organization = organizationOf(clause);
                // 行順編成だけが切り出し方の違う編成である
                format = organization == Organization.LINE_SEQUENTIAL
                        ? RecordFormat.LINE
                        : RecordFormat.FIXED;
            } else if (clause.ACCESS() != null) {
                access = accessOf(clause);
            } else if (clause.STATUS() != null) {
                status = clause.identifier();
            } else if (clause.RELATIVE() != null) {
                // 裸の RELATIVE は編成の指定であり、鍵の名前ではない
                if (clause.identifier() == null) {
                    organization = Organization.RELATIVE;
                    format = RecordFormat.FIXED;
                } else {
                    relativeKey = clause.identifier();
                }
            } else if (clause.ALTERNATE() != null) {
                keys.add(new SelectedKey(clause.identifier(), clause.DUPLICATES() != null));
            } else if (clause.RECORD() != null) {
                // 主鍵はいちばん前に置く。副鍵の番号は書かれた順である
                keys.add(0, new SelectedKey(clause.identifier(), false));
            } else if (clause.RECORDING() != null) {
                format = recordingOf(clause.IDENTIFIER().getText(), format, origin, diagnostics);
            }
        }
        return new Selected(name, ddName, organization, format, access, entry.OPTIONAL() != null,
                status, relativeKey, keys, origin);
    }

    /**
     * {@code ASSIGN} に書かれた名前 (要件 FR-100)。
     *
     * <p>句の順は決まっていないので、文法では位置を縛らず<b>ここで必ず 1 つあることを
     * 確かめる</b>。無ければ、そのファイルをどこへ結び付けるのかが分からない。
     *
     * <p>2 つ以上書かれていたら先頭を採る。装置の名前を並べる書き方があるが、
     * こちらでは DD 名 1 つに対応する。
     */
    private static String ddNameOf(CobolParser.SelectEntryContext entry, String name,
                                   Origin origin, List<Diagnostic> diagnostics) {
        for (CobolParser.SelectClauseContext clause : entry.selectClause()) {
            CobolParser.AssignClauseContext assign = clause.assignClause();
            if (assign == null) {
                continue;
            }
            if (!assign.LITERAL().isEmpty()) {
                return unquote(assign.LITERAL(0).getText());
            }
            return assign.IDENTIFIER(0).getText().toUpperCase(Locale.ROOT);
        }
        diagnostics.add(new Diagnostic(origin, "SELECT " + name + " has no ASSIGN clause"));
        return null;
    }

    private static Organization organizationOf(CobolParser.SelectClauseContext clause) {
        if (clause.RELATIVE() != null) {
            return Organization.RELATIVE;
        }
        CobolParser.OrganizationNameContext organization = clause.organizationName();
        if (organization == null || organization.INDEXED() != null) {
            return Organization.INDEXED;
        }
        return organization.LINE() != null
                ? Organization.LINE_SEQUENTIAL
                : Organization.SEQUENTIAL;
    }

    private static Access accessOf(CobolParser.SelectClauseContext clause) {
        if (clause.RANDOM() != null) {
            return Access.RANDOM;
        }
        return clause.DYNAMIC() != null ? Access.DYNAMIC : Access.SEQUENTIAL;
    }

    /** {@code RECORDING MODE} の綴り。不定長 (U) はまだ扱わない。 */
    private static RecordFormat recordingOf(String text, RecordFormat current, Origin origin,
                                            List<Diagnostic> diagnostics) {
        String mode = text.trim().toUpperCase(Locale.ROOT);
        if (mode.equals("F") || mode.equals("FB")) {
            return RecordFormat.FIXED;
        }
        if (mode.equals("V") || mode.equals("VB")) {
            return RecordFormat.VARIABLE;
        }
        diagnostics.add(new Diagnostic(origin, "RECORDING MODE " + mode + " is not supported yet"));
        return current;
    }

    /**
     * {@code SELECT} と {@code FD} を突き合わせる。
     *
     * @param records {@code FD} ごとのレコード領域の 01 レベル
     */
    public static Result build(CobolParser.ProgramUnitContext program, List<Selected> selected,
                               Map<String, List<DataItem>> records,
                               ReferenceResolver resolver, List<Diagnostic> diagnostics) {
        Map<String, CobolParser.FileDescriptionEntryContext> entries = entriesOf(program);
        Map<String, FileDescription> files = new LinkedHashMap<>();
        for (Selected one : selected) {
            List<DataItem> area = records.get(one.name());
            if (area == null || area.isEmpty()) {
                diagnostics.add(new Diagnostic(one.origin(),
                        "no FD for " + one.name() + "; declare it in the FILE SECTION"));
                continue;
            }
            int length = 0;
            for (DataItem record : area) {
                length = Math.max(length, record.totalLength());
            }
            CobolParser.FileDescriptionEntryContext entry = entries.get(one.name());
            RecordFormat format = one.format();
            String recording = recordingOf(entry);
            if (recording != null) {
                format = recordingOf(recording, format, one.origin(), diagnostics);
            }
            Varying varying = varyingOf(entry, length, resolver, diagnostics);
            if (varying != null) {
                // RECORD IS VARYING と書けば、様式は可変長である
                format = RecordFormat.VARIABLE;
                length = varying.maximum();
            }
            DataReference status = one.status() == null ? null : resolver.resolve(one.status());
            if (one.status() != null && status == null) {
                continue;
            }
            if (status != null && status.constantLength().orElse(0) != 2) {
                diagnostics.add(new Diagnostic(one.origin(),
                        "FILE STATUS requires a two-character item"));
                continue;
            }
            DataReference relativeKey = relativeKeyOf(one, varying, resolver, diagnostics);
            if (one.relativeKey() != null && relativeKey == null) {
                continue;
            }
            List<RecordKey> keys = recordKeysOf(one, area, resolver, diagnostics);
            if (keys == null) {
                continue;
            }
            if (entry != null && entry.SD() != null) {
                // 整列作業ファイルは編成を持たない。データセットではなく作業場所である
                if (!checkSortWork(one, status, relativeKey, keys, diagnostics)) {
                    continue;
                }
            } else if (!checkOrganization(one, relativeKey, keys, diagnostics)) {
                continue;
            }
            Linage linage = linageOf(entry, one, resolver, diagnostics);
            if (linage == null && hasLinage(entry)) {
                continue;
            }
            if (files.putIfAbsent(one.name(),
                    new FileDescription(one.name(), one.ddName(), one.organization(), format,
                            one.access(), status, one.optional(), relativeKey, keys,
                            entry != null && entry.SD() != null, area, length,
                            varying, linage, one.origin())) != null) {
                diagnostics.add(new Diagnostic(one.origin(), "duplicate SELECT for " + one.name()));
            }
        }
        for (String name : records.keySet()) {
            if (!files.containsKey(name)) {
                diagnostics.add(new Diagnostic(records.get(name).get(0).origin(),
                        "no SELECT for " + name + "; declare it in the FILE-CONTROL paragraph"));
            }
        }
        return new Result(Map.copyOf(files), List.copyOf(diagnostics));
    }

    /** {@code FD} に {@code LINAGE} が書かれているか。 */
    private static boolean hasLinage(CobolParser.FileDescriptionEntryContext entry) {
        return linageClauseOf(entry) != null;
    }

    private static CobolParser.LinageClauseContext linageClauseOf(
            CobolParser.FileDescriptionEntryContext entry) {
        if (entry == null) {
            return null;
        }
        for (CobolParser.FileDescriptionClauseContext clause : entry.fileDescriptionClause()) {
            if (clause.linageClause() != null) {
                return clause.linageClause();
            }
        }
        return null;
    }

    /**
     * {@code LINAGE} を読む (要件 FR-113)。
     *
     * <p>行数は<b>書かれた数だけ</b>を受ける。規格はデータ項目も許しており、その場合は
     * 開くたびに値を読み直す決まりである。読み直す仕掛けをまだ持っていないので、
     * 数の代わりに項目が書かれていたら<b>断る</b> (暫定判断 P-071)。黙って
     * 開いたときの値で固めると、途中で変えたつもりの頁の形が効かない。
     */
    private static Linage linageOf(CobolParser.FileDescriptionEntryContext entry, Selected one,
                                   ReferenceResolver resolver, List<Diagnostic> diagnostics) {
        CobolParser.LinageClauseContext clause = linageClauseOf(entry);
        if (clause == null) {
            return null;
        }
        Operand page = countOf(clause.linageCount(), resolver, one.origin(), diagnostics);
        if (page == null) {
            return null;
        }
        Operand footing = null;
        Operand top = null;
        Operand bottom = null;
        for (CobolParser.LinagePartContext part : clause.linagePart()) {
            Operand value = countOf(part.linageCount(), resolver, one.origin(), diagnostics);
            if (value == null) {
                return null;
            }
            if (part.FOOTING() != null) {
                footing = value;
            } else if (part.TOP() != null) {
                top = value;
            } else {
                bottom = value;
            }
        }
        DataReference counter = resolver.resolveName(LINAGE_COUNTER, one.origin());
        Linage.Slot pageSlot = slotOf(resolver, one, "LNG-PAGE$", page, diagnostics);
        Linage.Slot footingSlot = slotOf(resolver, one, "LNG-FOOT$", footing, diagnostics);
        Linage.Slot topSlot = slotOf(resolver, one, "LNG-TOP$", top, diagnostics);
        Linage.Slot bottomSlot = slotOf(resolver, one, "LNG-BOTTOM$", bottom, diagnostics);
        Linage.Slot startedSlot = slotOf(resolver, one, "LNG-START$", null, diagnostics);
        if (counter == null || pageSlot == null || footingSlot == null
                || topSlot == null || bottomSlot == null || startedSlot == null) {
            return null;
        }
        return new Linage(counter, pageSlot, footingSlot, topSlot, bottomSlot, startedSlot);
    }

    /** 頁の形の値 1 つと、その置き場を結び付ける。 */
    private static Linage.Slot slotOf(ReferenceResolver resolver, Selected one, String prefix,
                                      Operand source, List<Diagnostic> diagnostics) {
        DataReference at = resolver.resolveName(prefix + one.name(), one.origin());
        return at == null ? null : new Linage.Slot(at, source);
    }

    /** {@code LINAGE-COUNTER} の名前。データ部には書かれないが、名前で読める。 */
    public static final String LINAGE_COUNTER = "LINAGE-COUNTER";

    /**
     * 頁の形の値 1 つ。数でも項目でもよい。
     *
     * <p>項目で書かれた形は<b>開くたびに読み直す</b>決まりである。数で書かれた形も
     * 同じ道 (開くときに置き場へ写す) を通すので、ここでは区別せずに被演算子にする。
     */
    private static Operand countOf(CobolParser.LinageCountContext count,
                                   ReferenceResolver resolver, Origin origin,
                                   List<Diagnostic> diagnostics) {
        if (count.NUMBER() != null) {
            try {
                return new Operand.Literal(new LiteralValue.Number(
                        dev.cobolonjava.runtime.decimal.Decimal.parse(
                                count.NUMBER().getText())));
            } catch (NumberFormatException e) {
                diagnostics.add(new Diagnostic(origin,
                        "LINAGE takes an integer: " + count.NUMBER().getText()));
                return null;
            }
        }
        DataReference item = resolver.resolve(count.identifier());
        if (item == null) {
            return null;
        }
        if (!DataCategory.of(item).isNumeric()) {
            diagnostics.add(new Diagnostic(origin, "LINAGE requires an integer item"));
            return null;
        }
        return new Operand.Reference(item);
    }

    /**
     * {@code RELATIVE KEY} の項目 (要件 FR-101)。
     *
     * <p>相対レコード番号を持つ入れ物である。<b>符号なしの整数</b>でなければならない。
     * 番号なので小数点も符号も意味を持たない。
     */
    private static DataReference relativeKeyOf(Selected one, Varying varying,
                                               ReferenceResolver resolver,
                                               List<Diagnostic> diagnostics) {
        if (one.relativeKey() == null) {
            return null;
        }
        DataReference key = resolver.resolve(one.relativeKey());
        if (key == null) {
            return null;
        }
        if (!DataCategory.of(key).isNumeric()) {
            diagnostics.add(new Diagnostic(one.origin(),
                    "RELATIVE KEY requires an unsigned integer item"));
            return null;
        }
        return key;
    }

    /**
     * 索引編成の鍵 (要件 FR-100)。
     *
     * <p>鍵は<b>その {@code FD} のレコードの中になければならない</b>。レコードの外にある項目を
     * 鍵と言われても、書き出したバイト列のどこを見ればよいのか決まらない。
     *
     * @return 誤りがあれば {@code null}
     */
    private static List<RecordKey> recordKeysOf(Selected one, List<DataItem> area,
                                                ReferenceResolver resolver,
                                                List<Diagnostic> diagnostics) {
        List<RecordKey> keys = new ArrayList<>();
        for (SelectedKey selected : one.keys()) {
            DataReference key = resolver.resolve(selected.name());
            if (key == null) {
                return null;
            }
            if (!area.contains(key.item().record())) {
                diagnostics.add(new Diagnostic(one.origin(), "a record key must be inside the "
                        + "record area of " + one.name() + ": " + key.item().name()));
                return null;
            }
            if (key.constantOffset().isEmpty() || key.constantLength().isEmpty()) {
                diagnostics.add(new Diagnostic(one.origin(),
                        "a record key must have a fixed position and length"));
                return null;
            }
            keys.add(new RecordKey(key, selected.duplicates()));
        }
        return keys;
    }

    /**
     * 編成とアクセス様式と鍵の組み合わせを検査する (要件 FR-100, FR-101)。
     *
     * <p>組み合わせには<b>成り立たないもの</b>がある。順編成に番号で引く鍵はないし、
     * 鍵なしで乱アクセスはできない。
     */
    private static boolean checkOrganization(Selected one, DataReference relativeKey,
                                             List<RecordKey> keys, List<Diagnostic> diagnostics) {
        Origin origin = one.origin();
        boolean relative = one.organization() == Organization.RELATIVE;
        boolean indexed = one.organization() == Organization.INDEXED;
        if (!relative && relativeKey != null) {
            diagnostics.add(new Diagnostic(origin,
                    "RELATIVE KEY requires ORGANIZATION IS RELATIVE"));
            return false;
        }
        if (!indexed && !keys.isEmpty()) {
            diagnostics.add(new Diagnostic(origin,
                    "RECORD KEY requires ORGANIZATION IS INDEXED"));
            return false;
        }
        if (indexed && keys.isEmpty()) {
            // 索引編成は鍵でしか引けない。鍵がなければ何も引けない
            diagnostics.add(new Diagnostic(origin,
                    "ORGANIZATION IS INDEXED requires a RECORD KEY"));
            return false;
        }
        if (!relative && !indexed && one.access().isKeyed()) {
            diagnostics.add(new Diagnostic(origin, "ACCESS MODE " + one.access()
                    + " requires a keyed organization"));
            return false;
        }
        if (relative && one.access().isKeyed() && relativeKey == null) {
            // 鍵で引くと言われても、番号の置き場がなければ引けない
            diagnostics.add(new Diagnostic(origin, "ACCESS MODE " + one.access()
                    + " on a RELATIVE file requires a RELATIVE KEY"));
            return false;
        }
        return true;
    }

    /** ファイル名から {@code FD} を引く表。 */
    private static Map<String, CobolParser.FileDescriptionEntryContext> entriesOf(
            CobolParser.ProgramUnitContext program) {
        Map<String, CobolParser.FileDescriptionEntryContext> entries = new LinkedHashMap<>();
        for (CobolParser.ProgramUnitContext unit : List.of(program)) {
            if (unit.dataDivision() == null) {
                continue;
            }
            for (CobolParser.DataDivisionSectionContext section
                    : unit.dataDivision().dataDivisionSection()) {
                if (section.fileSection() == null) {
                    continue;
                }
                for (CobolParser.FileDescriptionEntryContext fd
                        : section.fileSection().fileDescriptionEntry()) {
                    entries.putIfAbsent(fd.IDENTIFIER().getText().toUpperCase(Locale.ROOT), fd);
                }
            }
        }
        return entries;
    }

    /**
     * {@code FD} に書かれた {@code RECORDING MODE}。
     *
     * <p>{@code SELECT} 側と {@code FD} 側の両方に書ける。あとから読む {@code FD} 側が勝つ。
     */
    private static String recordingOf(CobolParser.FileDescriptionEntryContext entry) {
        String mode = null;
        if (entry != null) {
            for (CobolParser.FileDescriptionClauseContext clause : entry.fileDescriptionClause()) {
                if (clause.RECORDING() != null) {
                    // DATA RECORDS も名前を並べるので、IDENTIFIER は複数ありうる
                    mode = clause.IDENTIFIER(0).getText();
                }
            }
        }
        return mode;
    }

    /**
     * {@code RECORD IS VARYING IN SIZE} (要件 FR-106)。
     *
     * <p>上限を書かなければ、レコード記述のうちいちばん長いものが上限になる。
     * 領域より長いレコードは受け取れないからである。
     */
    private static Varying varyingOf(CobolParser.FileDescriptionEntryContext entry, int area,
                                     ReferenceResolver resolver, List<Diagnostic> diagnostics) {
        if (entry == null) {
            return null;
        }
        for (CobolParser.FileDescriptionClauseContext clause : entry.fileDescriptionClause()) {
            CobolParser.RecordVaryingClauseContext varying = clause.recordVaryingClause();
            if (varying == null) {
                continue;
            }
            Origin origin = ReferenceResolver.originOf(varying);
            List<org.antlr.v4.runtime.tree.TerminalNode> numbers = varying.NUMBER();
            int minimum = varying.FROM() != null ? Integer.parseInt(numbers.get(0).getText()) : 0;
            int maximum = area;
            if (varying.TO() != null) {
                maximum = Integer.parseInt(numbers.get(numbers.size() - 1).getText());
            }
            if (maximum < minimum || maximum <= 0) {
                diagnostics.add(new Diagnostic(origin,
                        "RECORD VARYING requires a positive maximum not below the minimum"));
                return null;
            }
            if (maximum > area) {
                // 規格に沿わないが、意味は決まる。レコード領域はレコード記述が決めるので、
                // そこまでで頭打ちにする。黙って切らずに<b>告げて通す</b> (要件 FR-183)。
                // 止めてしまうと、その先にある本当の誤りが見えなくなる (IX401M)
                diagnostics.add(Diagnostic.warning(origin, "RECORD VARYING maximum of " + maximum
                        + " exceeds the record area of " + area + " bytes;"
                        + " the record area is used"));
                maximum = area;
            }
            DataReference depending = varying.identifier() == null
                    ? null
                    : resolver.resolve(varying.identifier());
            if (varying.identifier() != null && depending == null) {
                return null;
            }
            if (depending != null && !DataCategory.of(depending).isNumeric()) {
                diagnostics.add(new Diagnostic(origin,
                        "RECORD VARYING ... DEPENDING ON requires a numeric item"));
                return null;
            }
            return new Varying(minimum, maximum, depending);
        }
        return null;
    }

    private static String unquote(String text) {
        char quote = text.charAt(0);
        return text.substring(1, text.length() - 1).replace("" + quote + quote, "" + quote);
    }
}
