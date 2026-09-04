package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.Origin;
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
 * @param format       レコード様式。{@code ORGANIZATION} から決まる
 * @param status       {@code FILE STATUS} の項目。書かれていなければ {@code null}
 * @param records      {@code FD} 配下のレコード記述。すべて同じ領域に重なる
 * @param recordLength レコード長。{@code FD} 配下の記述から決まる
 */
public record FileDescription(String name, String ddName, RecordFormat format,
                              DataReference status, List<DataItem> records, int recordLength,
                              Origin origin) {

    public FileDescription {
        records = List.copyOf(records);
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
    record Selected(String name, String ddName, RecordFormat format,
                    CobolParser.IdentifierContext status, Origin origin) {
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

        RecordFormat format = RecordFormat.FIXED;
        CobolParser.IdentifierContext status = null;
        for (CobolParser.SelectClauseContext clause : entry.selectClause()) {
            if (clause.ORGANIZATION() != null) {
                // 行順編成だけが切り出し方の違う編成である
                format = clause.LINE() != null ? RecordFormat.LINE : RecordFormat.FIXED;
            } else if (clause.STATUS() != null) {
                status = clause.identifier();
            } else if (clause.RECORDING() != null) {
                format = recordingOf(clause.IDENTIFIER().getText(), format, origin, diagnostics);
            }
        }
        return new Selected(name, ddName, format, status, origin);
    }

    /** {@code RECORDING MODE} の綴り。可変長は次の段である。 */
    private static RecordFormat recordingOf(String text, RecordFormat current, Origin origin,
                                            List<Diagnostic> diagnostics) {
        String mode = text.trim().toUpperCase(Locale.ROOT);
        if (mode.equals("F") || mode.equals("FB")) {
            return RecordFormat.FIXED;
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
        Map<String, String> recordingModes = recordingModesOf(tree);
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
            RecordFormat format = one.format();
            String recording = recordingModes.get(one.name());
            if (recording != null) {
                format = recordingOf(recording, format, one.origin(), diagnostics);
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
            if (files.putIfAbsent(one.name(),
                    new FileDescription(one.name(), one.ddName(), format, status, area, length,
                            one.origin())) != null) {
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
     * {@code FD} に書かれた {@code RECORDING MODE}。
     *
     * <p>{@code SELECT} 側と {@code FD} 側の両方に書ける。あとから読む {@code FD} 側が勝つ。
     */
    private static Map<String, String> recordingModesOf(CobolParser.CompilationUnitContext tree) {
        Map<String, String> modes = new LinkedHashMap<>();
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
                    String name = fd.IDENTIFIER().getText().toUpperCase(Locale.ROOT);
                    for (CobolParser.FileDescriptionClauseContext clause
                            : fd.fileDescriptionClause()) {
                        if (clause.RECORDING() != null) {
                            modes.put(name, clause.IDENTIFIER().getText());
                        }
                    }
                }
            }
        }
        return modes;
    }

    private static String unquote(String text) {
        char quote = text.charAt(0);
        return text.substring(1, text.length() - 1).replace("" + quote + quote, "" + quote);
    }
}
