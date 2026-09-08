package dev.cobolonjava.compiler.semantic;

import dev.cobolonjava.compiler.parser.CobolParser;
import dev.cobolonjava.compiler.parser.Diagnostic;
import dev.cobolonjava.compiler.source.Origin;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 環境部の {@code SPECIAL-NAMES} 段落 (要件 FR-054, FR-135)。
 *
 * <p>ここで決まるのは<b>処理系の外側との結び付け</b>である。通貨記号は PICTURE の解釈に、
 * 呼び名は {@code ACCEPT} と {@code DISPLAY} の行き先に効く。
 *
 * <p>データ部より前に読まなければならない。PICTURE の解釈が通貨記号に依るためである。
 */
public final class SpecialNames {

    /** 参照実装の既定の通貨記号。 */
    public static final char DEFAULT_CURRENCY = '$';

    private final char currency;
    private final Map<String, FunctionName> mnemonics;
    private final byte[] collating;

    private SpecialNames(char currency, Map<String, FunctionName> mnemonics, byte[] collating) {
        this.currency = currency;
        this.mnemonics = Map.copyOf(mnemonics);
        this.collating = collating;
    }

    /** 何も書かれていないときの構成。 */
    public static SpecialNames standard() {
        return new SpecialNames(DEFAULT_CURRENCY, Map.of(), null);
    }

    /**
     * このプログラムの照合順序 (要件 FR-054)。
     *
     * <p>{@code PROGRAM COLLATING SEQUENCE} が書かれ、かつそれがコードページのバイト値の
     * 並びと違うときだけ表を返す。同じ並びなら差し替える意味がないので {@code null} を
     * 返し、生成コードは既定の比較を使う。
     *
     * @return 256 個の要素からなる「バイト値 → 位置」の表。既定でよければ {@code null}
     */
    public byte[] collatingSequence() {
        return collating == null ? null : collating.clone();
    }

    /**
     * 呼び名が指せる機能名 (要件 FR-135)。
     *
     * <p>綴りは処理系ごとに別名があるので、まとめて 1 つへ寄せる。
     */
    public enum FunctionName {
        /** 端末。{@code ACCEPT} も {@code DISPLAY} も使える。 */
        CONSOLE,
        /** 標準入力。 */
        SYSIN,
        /** 標準出力。 */
        SYSOUT,
        /** 標準エラー出力。 */
        SYSERR;

        /** 機能名の綴りから読み取る。知らない綴りなら {@code null}。 */
        static FunctionName of(String text) {
            return switch (text) {
                case "CONSOLE", "TERMINAL" -> CONSOLE;
                case "SYSIN", "SYSIPT" -> SYSIN;
                case "SYSOUT", "SYSLIST", "SYSLST", "SYSPRINT" -> SYSOUT;
                case "SYSERR" -> SYSERR;
                default -> null;
            };
        }

        /** 読み取る側かどうか。 */
        public boolean isInput() {
            return this == CONSOLE || this == SYSIN;
        }
    }

    /** 組み立ての結果。 */
    public record Result(SpecialNames specialNames, List<Diagnostic> diagnostics) {

        public boolean succeeded() {
            return diagnostics.isEmpty();
        }
    }

    /** PICTURE の通貨記号。指定がなければ {@code $}。 */
    public char currency() {
        return currency;
    }

    /**
     * 呼び名が指す機能名。
     *
     * @return 書かれていなければ {@code null}
     */
    public FunctionName mnemonic(String name) {
        return mnemonics.get(name.toUpperCase(Locale.ROOT));
    }

    /** プログラム 1 本の環境部を読む。 */
    public static Result build(CobolParser.ProgramUnitContext program) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        char currency = DEFAULT_CURRENCY;
        Map<String, FunctionName> mnemonics = new LinkedHashMap<>();
        Map<String, byte[]> alphabets = new LinkedHashMap<>();

        CobolParser.SpecialNamesParagraphContext paragraph = paragraphOf(program);
        if (paragraph != null) {
            for (CobolParser.SpecialNamesEntryContext entry : paragraph.specialNamesEntry()) {
                Origin origin = ReferenceResolver.originOf(entry);
                if (entry.CURRENCY() != null) {
                    Character sign = currencyOf(entry, origin, diagnostics);
                    if (sign != null) {
                        currency = sign;
                    }
                    continue;
                }
                if (entry.DECIMAL_POINT() != null) {
                    // 小数点の入れ替えは PICTURE だけでなく数字定数の綴りにも効く。
                    // 字句の切り出しまで遡る必要がある (暫定判断 P-010)
                    diagnostics.add(new Diagnostic(origin,
                            "DECIMAL-POINT IS COMMA is not supported yet"));
                    continue;
                }
                if (entry.alphabetClause() != null) {
                    addAlphabet(entry.alphabetClause(), alphabets, origin, diagnostics);
                    continue;
                }
                addMnemonic(entry, mnemonics, origin, diagnostics);
            }
        }
        byte[] collating = collatingOf(program, alphabets, diagnostics);
        return new Result(new SpecialNames(currency, mnemonics, collating),
                List.copyOf(diagnostics));
    }

    /**
     * {@code OBJECT-COMPUTER} の {@code PROGRAM COLLATING SEQUENCE} を読む (要件 FR-054)。
     *
     * @return コードページの並びと同じなら {@code null}
     */
    private static byte[] collatingOf(CobolParser.ProgramUnitContext program,
                                      Map<String, byte[]> alphabets,
                                      List<Diagnostic> diagnostics) {
        CobolParser.ProgramCollatingSequenceContext clause = collatingClauseOf(program);
        if (clause == null) {
            return null;
        }
        Origin origin = ReferenceResolver.originOf(clause);
        String name = clause.IDENTIFIER().getText().toUpperCase(Locale.ROOT);
        byte[] table = alphabets.get(name);
        if (table == null) {
            diagnostics.add(new Diagnostic(origin, "undefined alphabet-name: " + name));
            return null;
        }
        return Alphabet.isNative(table) ? null : table;
    }

    private static CobolParser.ProgramCollatingSequenceContext collatingClauseOf(
            CobolParser.ProgramUnitContext unit) {
        if (unit.environmentDivision() == null
                || unit.environmentDivision().configurationSection() == null) {
            return null;
        }
        for (CobolParser.ConfigurationParagraphContext paragraph
                : unit.environmentDivision().configurationSection().configurationParagraph()) {
            if (paragraph.objectComputerParagraph() == null) {
                continue;
            }
            for (CobolParser.ObjectComputerPartContext part
                    : paragraph.objectComputerParagraph().objectComputerPart()) {
                if (part.programCollatingSequence() != null) {
                    return part.programCollatingSequence();
                }
            }
        }
        return null;
    }

    /** {@code ALPHABET 名前 IS ...}。 */
    private static void addAlphabet(CobolParser.AlphabetClauseContext clause,
                                    Map<String, byte[]> alphabets, Origin origin,
                                    List<Diagnostic> diagnostics) {
        String name = clause.IDENTIFIER().getText().toUpperCase(Locale.ROOT);
        byte[] table = Alphabet.of(clause.alphabetSpecification(), origin, diagnostics);
        if (table == null) {
            return;
        }
        if (alphabets.putIfAbsent(name, table) != null) {
            diagnostics.add(new Diagnostic(origin, "duplicate alphabet-name: " + name));
        }
    }

    private static CobolParser.SpecialNamesParagraphContext paragraphOf(
            CobolParser.ProgramUnitContext unit) {
        if (unit.environmentDivision() == null
                || unit.environmentDivision().configurationSection() == null) {
            return null;
        }
        for (CobolParser.ConfigurationParagraphContext paragraph
                : unit.environmentDivision().configurationSection().configurationParagraph()) {
            if (paragraph.specialNamesParagraph() != null) {
                return paragraph.specialNamesParagraph();
            }
        }
        return null;
    }

    /** {@code CURRENCY SIGN IS 定数}。1 文字でなければならない。 */
    private static Character currencyOf(CobolParser.SpecialNamesEntryContext entry, Origin origin,
                                        List<Diagnostic> diagnostics) {
        LiteralValue value;
        try {
            value = LiteralValue.of(entry.literal());
        } catch (RuntimeException e) {
            diagnostics.add(new Diagnostic(origin,
                    "invalid literal: " + entry.literal().getText()));
            return null;
        }
        if (!(value instanceof LiteralValue.Text text) || text.text().length() != 1) {
            diagnostics.add(new Diagnostic(origin,
                    "CURRENCY SIGN requires a one-character alphanumeric literal"));
            return null;
        }
        return text.text().charAt(0);
    }

    /** {@code 機能名 IS 呼び名}。 */
    private static void addMnemonic(CobolParser.SpecialNamesEntryContext entry,
                                    Map<String, FunctionName> mnemonics, Origin origin,
                                    List<Diagnostic> diagnostics) {
        String function = entry.IDENTIFIER(0).getText().toUpperCase(Locale.ROOT);
        String mnemonic = entry.IDENTIFIER(1).getText().toUpperCase(Locale.ROOT);
        FunctionName resolved = FunctionName.of(function);
        if (resolved == null) {
            diagnostics.add(new Diagnostic(origin, "unknown function name: " + function));
            return;
        }
        if (mnemonics.putIfAbsent(mnemonic, resolved) != null) {
            diagnostics.add(new Diagnostic(origin, "duplicate mnemonic name: " + mnemonic));
        }
    }
}
