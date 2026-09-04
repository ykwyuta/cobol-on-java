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
 * @param records      {@code FD} 配下のレコード記述。すべて同じ領域に重なる
 * @param recordLength レコード長。{@code FD} 配下の記述から決まる
 * @param varying      可変長の指定。固定長なら {@code null}
 */
public record FileDescription(String name, String ddName, Organization organization,
                              RecordFormat format, Access access, DataReference status,
                              boolean optional, DataReference relativeKey,
                              List<DataItem> records, int recordLength, Varying varying,
                              Origin origin) {

    public FileDescription {
        records = List.copyOf(records);
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
            return diagnostics.isEmpty();
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
                    CobolParser.IdentifierContext relativeKey, Origin origin) {
    }

    /** 環境部の {@code SELECT} 句を読む。 */
    public static List<Selected> select(CobolParser.CompilationUnitContext tree,
                                        List<Diagnostic> diagnostics) {
        List<Selected> out = new ArrayList<>();
        for (CobolParser.ProgramUnitContext unit : tree.programUnit()) {
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
        return out;
    }

    private static Selected selectedOf(CobolParser.SelectEntryContext entry,
                                       List<Diagnostic> diagnostics) {
        Origin origin = ReferenceResolver.originOf(entry);
        List<org.antlr.v4.runtime.tree.TerminalNode> names = entry.IDENTIFIER();
        String name = names.get(0).getText().toUpperCase(Locale.ROOT);
        String ddName = entry.LITERAL() != null
                ? unquote(entry.LITERAL().getText())
                : names.get(1).getText().toUpperCase(Locale.ROOT);

        Organization organization = Organization.SEQUENTIAL;
        RecordFormat format = RecordFormat.FIXED;
        Access access = Access.SEQUENTIAL;
        CobolParser.IdentifierContext status = null;
        CobolParser.IdentifierContext relativeKey = null;
        for (CobolParser.SelectClauseContext clause : entry.selectClause()) {
            if (clause.ORGANIZATION() != null) {
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
                relativeKey = clause.identifier();
            } else if (clause.RECORD() != null) {
                diagnostics.add(new Diagnostic(origin,
                        "RECORD KEY is not supported yet; INDEXED files are the next increment"));
            } else if (clause.RECORDING() != null) {
                format = recordingOf(clause.IDENTIFIER().getText(), format, origin, diagnostics);
            }
        }
        return new Selected(name, ddName, organization, format, access, entry.OPTIONAL() != null,
                status, relativeKey, origin);
    }

    private static Organization organizationOf(CobolParser.SelectClauseContext clause) {
        if (clause.RELATIVE() != null) {
            return Organization.RELATIVE;
        }
        if (clause.INDEXED() != null) {
            return Organization.INDEXED;
        }
        return clause.LINE() != null ? Organization.LINE_SEQUENTIAL : Organization.SEQUENTIAL;
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
    public static Result build(CobolParser.CompilationUnitContext tree, List<Selected> selected,
                               Map<String, List<DataItem>> records,
                               ReferenceResolver resolver, List<Diagnostic> diagnostics) {
        Map<String, CobolParser.FileDescriptionEntryContext> entries = entriesOf(tree);
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
            if (one.organization() == Organization.RELATIVE && format == RecordFormat.VARIABLE) {
                diagnostics.add(new Diagnostic(one.origin(),
                        "a RELATIVE file cannot have variable-length records"));
                continue;
            }
            if (varying != null && one.organization() == Organization.RELATIVE) {
                // 相対編成のスロットは固定長である。長さが違えば番号が住所にならない
                diagnostics.add(new Diagnostic(one.origin(),
                        "a RELATIVE file cannot have variable-length records"));
                continue;
            }
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
            if (!checkOrganization(one, relativeKey, diagnostics)) {
                continue;
            }
            if (files.putIfAbsent(one.name(),
                    new FileDescription(one.name(), one.ddName(), one.organization(), format,
                            one.access(), status, one.optional(), relativeKey, area, length,
                            varying, one.origin())) != null) {
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
     * 編成とアクセス様式と鍵の組み合わせを検査する (要件 FR-100, FR-101)。
     *
     * <p>組み合わせには<b>成り立たないもの</b>がある。順編成に番号で引く鍵はないし、
     * 鍵なしで乱アクセスはできない。
     */
    private static boolean checkOrganization(Selected one, DataReference relativeKey,
                                             List<Diagnostic> diagnostics) {
        Origin origin = one.origin();
        if (one.organization() == Organization.INDEXED) {
            diagnostics.add(new Diagnostic(origin,
                    "ORGANIZATION INDEXED is not supported yet"));
            return false;
        }
        boolean relative = one.organization() == Organization.RELATIVE;
        if (!relative && relativeKey != null) {
            diagnostics.add(new Diagnostic(origin,
                    "RELATIVE KEY requires ORGANIZATION IS RELATIVE"));
            return false;
        }
        if (!relative && one.access().isKeyed()) {
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
            CobolParser.CompilationUnitContext tree) {
        Map<String, CobolParser.FileDescriptionEntryContext> entries = new LinkedHashMap<>();
        for (CobolParser.ProgramUnitContext unit : tree.programUnit()) {
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
                    mode = clause.IDENTIFIER().getText();
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
                // 領域より長いレコードは受け取れない。読めば領域の外へはみ出す
                diagnostics.add(new Diagnostic(origin, "RECORD VARYING maximum of " + maximum
                        + " exceeds the record area of " + area + " bytes"));
                return null;
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
