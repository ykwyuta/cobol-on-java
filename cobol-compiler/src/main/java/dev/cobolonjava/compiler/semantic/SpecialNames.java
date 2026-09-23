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
    private final boolean commaDecimalPoint;
    private final Map<String, FunctionName> mnemonics;
    private final byte[] collating;
    private final Map<String, byte[]> alphabets;
    private final Map<String, byte[]> classes;
    private final Map<String, SwitchStatus> switches;
    private final Map<String, Integer> switchNames;
    /** {@code WITH DEBUGGING MODE} が書かれていたか (要件 FR-193)。 */
    private final boolean debuggingMode;

    private SpecialNames(char currency, Map<String, FunctionName> mnemonics, byte[] collating) {
        this(currency, false, mnemonics, collating, Map.of(), Map.of(), Map.of(), Map.of(), false);
    }

    private SpecialNames(char currency, boolean commaDecimalPoint,
                         Map<String, FunctionName> mnemonics, byte[] collating,
                         Map<String, byte[]> alphabets, Map<String, byte[]> classes,
                         Map<String, SwitchStatus> switches, Map<String, Integer> switchNames,
                         boolean debuggingMode) {
        this.debuggingMode = debuggingMode;
        this.commaDecimalPoint = commaDecimalPoint;
        this.alphabets = Map.copyOf(alphabets);
        this.classes = Map.copyOf(classes);
        this.switches = Map.copyOf(switches);
        this.switchNames = Map.copyOf(switchNames);
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
     * 図形定数 {@code HIGH-VALUE} が表すバイト (要件 FR-054)。
     *
     * <p>規格は「<b>照合順序でいちばん後ろに来る文字</b>」と決めている。並びを差し替えて
     * いなければコードページのいちばん大きいバイト値、つまり {@code 0xFF} である。
     * 差し替えていれば別の文字になる — {@code 0xFF} を {@code ALSO} で途中の位置へ
     * 動かしたなら、いちばん後ろに来るのは<b>ほかの文字</b>である。
     *
     * <p>CCVS85 の NC219A がここを見ている。{@code "F" "U" "N" ALSO HIGH-VALUE ALSO
     * LOW-VALUE "Y"} と書くと、{@code N} と {@code 0xFF} と {@code 0x00} が同じ位置に
     * 並ぶ。このとき {@code N = HIGH-VALUE} は<b>偽</b>でなければならない。
     */
    public byte highValue() {
        return collating == null ? (byte) 0xFF : extremeOf(collating, true);
    }

    /**
     * 図形定数 {@code LOW-VALUE} が表すバイト (要件 FR-054)。
     *
     * <p>{@link #highValue()} の裏返しで、<b>照合順序でいちばん前に来る文字</b>である。
     */
    public byte lowValue() {
        return collating == null ? (byte) 0x00 : extremeOf(collating, false);
    }

    /**
     * 並びの端に来る文字を探す。
     *
     * <p>同じ位置に複数の文字が並んでいたら、<b>バイト値の小さいほう</b>を採る。
     * {@code CollatingSequence} が位置から文字を引くときと同じ決め方である。
     */
    private static byte extremeOf(byte[] table, boolean highest) {
        int found = 0;
        int extreme = table[0] & 0xFF;
        for (int value = 1; value < table.length; value++) {
            int position = table[value] & 0xFF;
            if (highest ? position > extreme : position < extreme) {
                extreme = position;
                found = value;
            }
        }
        return (byte) found;
    }

    /**
     * 名前で書いた照合順序 (要件 FR-054)。{@code SORT ... SEQUENCE} が引く。
     *
     * @return 256 個の要素からなる「バイト値 → 位置」の表。知らない名前なら {@code null}
     */
    public byte[] alphabet(String name) {
        byte[] table = alphabets.get(name.toUpperCase(Locale.ROOT));
        return table == null ? null : table.clone();
    }

    /**
     * 書いて決めた級に入るバイト (要件 FR-046)。{@code CLASS} 句が決める。
     *
     * @return 知らない名前なら {@code null}
     */
    public byte[] classMembers(String name) {
        byte[] members = classes.get(name.toUpperCase(Locale.ROOT));
        return members == null ? null : members.clone();
    }

    /**
     * 外から立てる切り替えの条件名 (要件 FR-135)。
     *
     * @param index  何番目の切り替えか (0 起点)
     * @param whenOn 立っているときに真になるか
     */
    public record SwitchStatus(int index, boolean whenOn) {
    }

    /**
     * 条件名が切り替えを問うものなら、その中身。
     *
     * @return 切り替えの条件名でなければ {@code null}
     */
    public SwitchStatus switchStatus(String name) {
        return switches.get(name.toUpperCase(Locale.ROOT));
    }

    /**
     * 切り替えに付けた呼び名が指す番号 (要件 FR-135)。{@code SET ... TO ON} が引く。
     *
     * @return 呼び名でなければ {@code null}
     */
    public Integer switchIndexOfMnemonic(String name) {
        return switchNames.get(name.toUpperCase(Locale.ROOT));
    }

    /** 外から立てられる切り替えの数。参照実装と同じ 8 個である。 */
    public static final int SWITCHES = 8;

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
        SYSERR,
        /**
         * 紙送りの通路 1〜12 ({@code C01}〜{@code C12}) と、行を送らない {@code CSP}
         * (参照実装の {@code SPECIAL-NAMES} の機能名)。{@code WRITE ... ADVANCING} にだけ書ける。
         * 以前は機能名の表に無く、{@code C01 IS TOP-OF-FORM} を「知らない機能名」と断っていた
         * (z/OS probe の CBLPRNC)。
         */
        C01, C02, C03, C04, C05, C06, C07, C08, C09, C10, C11, C12, CSP;

        /** 機能名の綴りから読み取る。知らない綴りなら {@code null}。 */
        static FunctionName of(String text) {
            return switch (text) {
                case "CONSOLE", "TERMINAL" -> CONSOLE;
                case "SYSIN", "SYSIPT" -> SYSIN;
                case "SYSOUT", "SYSLIST", "SYSLST", "SYSPRINT" -> SYSOUT;
                case "SYSERR" -> SYSERR;
                case "C01", "C02", "C03", "C04", "C05", "C06", "C07", "C08", "C09", "C10",
                     "C11", "C12", "CSP" -> valueOf(text);
                default -> null;
            };
        }

        /** 紙送りの機能名か。{@code DISPLAY} と {@code ACCEPT} には書けない。 */
        public boolean isCarriageControl() {
            return ordinal() >= C01.ordinal();
        }

        /** 読み取る側かどうか。 */
        public boolean isInput() {
            return this == CONSOLE || this == SYSIN;
        }
    }

    /** 組み立ての結果。 */
    public record Result(SpecialNames specialNames, List<Diagnostic> diagnostics) {

        public boolean succeeded() {
            return !Diagnostic.blocking(diagnostics);
        }
    }

    /**
     * {@code WITH DEBUGGING MODE} が書かれていたか (要件 FR-193)。
     *
     * <p>書かれていなければ、デバッグの節も 7 桁目の {@code D} の行も注釈と同じである。
     */
    public boolean debuggingMode() {
        return debuggingMode;
    }

    /** PICTURE の通貨記号。指定がなければ {@code $}。 */
    public char currency() {
        return currency;
    }

    /**
     * 小数点として書く文字 (要件 FR-054)。
     *
     * <p>{@code DECIMAL-POINT IS COMMA} と書けばコンマになり、そのとき<b>ピリオドは
     * 桁区切り</b>になる。PICTURE の解釈と数字定数の綴りの両方に効く。
     */
    public char decimalPoint() {
        return commaDecimalPoint ? ',' : '.';
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
        boolean commaDecimalPoint = false;
        Map<String, FunctionName> mnemonics = new LinkedHashMap<>();
        Map<String, byte[]> alphabets = new LinkedHashMap<>();
        Map<String, byte[]> classes = new LinkedHashMap<>();
        Map<String, SwitchStatus> switches = new LinkedHashMap<>();
        Map<String, Integer> switchNames = new LinkedHashMap<>();

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
                    String written = entry.IDENTIFIER(0).getText().toUpperCase(Locale.ROOT);
                    if (!written.equals("COMMA")) {
                        diagnostics.add(new Diagnostic(origin,
                                "DECIMAL-POINT IS takes COMMA: " + written));
                    }
                    commaDecimalPoint = true;
                    continue;
                }
                if (entry.alphabetClause() != null) {
                    addAlphabet(entry.alphabetClause(), alphabets, origin, diagnostics);
                    continue;
                }
                if (entry.classClause() != null) {
                    addClass(entry.classClause(), classes, origin, diagnostics);
                    continue;
                }
                if (entry.switchClause() != null) {
                    addSwitch(entry.switchClause(), switches, switchNames, origin, diagnostics);
                    continue;
                }
                if (entry.symbolicCharactersClause() != null) {
                    // 名前を照合順序の位置で決める。値そのものは翻訳の結果に効くが、
                    // 名前を定数として使う道をまだ持っていない (暫定判断 P-074)
                    continue;
                }
                addMnemonic(entry, mnemonics, origin, diagnostics);
            }
        }
        byte[] collating = collatingOf(program, alphabets, diagnostics);
        return new Result(
                new SpecialNames(currency, commaDecimalPoint, mnemonics, collating, alphabets,
                        classes, switches, switchNames, debuggingModeOf(program)),
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

    /**
     * {@code CLASS 名前 IS ...} (要件 FR-046)。
     *
     * <p>級に入る文字を並べる。{@code THRU} は範囲であり、コードページの並びで数える。
     * 数字定数は<b>照合順序の何番目か</b>を表す。
     */
    private static void addClass(CobolParser.ClassClauseContext clause,
                                 Map<String, byte[]> classes, Origin origin,
                                 List<Diagnostic> diagnostics) {
        String name = clause.IDENTIFIER().getText().toUpperCase(Locale.ROOT);
        List<Byte> members = new ArrayList<>();
        for (CobolParser.ClassMemberContext member : clause.classMember()) {
            byte[] range = Alphabet.charactersOfMember(member, origin, diagnostics);
            if (range == null) {
                return;
            }
            for (byte one : range) {
                members.add(one);
            }
        }
        byte[] bytes = new byte[members.size()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = members.get(i);
        }
        if (classes.putIfAbsent(name, bytes) != null) {
            diagnostics.add(new Diagnostic(origin, "duplicate class-name: " + name));
        }
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

    /**
     * {@code WITH DEBUGGING MODE} が書かれているか (要件 FR-193)。
     *
     * <p>これが書かれていなければ、{@code USE FOR DEBUGGING} の節も 7 桁目の {@code D} の
     * 行も<b>注釈と同じ</b>である。手加減ではなく規格の決まりである。
     *
     * <p>{@code SOURCE-COMPUTER} 段落は翻訳の結果に効かないので文法では読み飛ばして
     * いる。ここだけは効くので、読み飛ばした語を見る。
     */
    private static boolean debuggingModeOf(CobolParser.ProgramUnitContext unit) {
        if (unit.environmentDivision() == null
                || unit.environmentDivision().configurationSection() == null) {
            return false;
        }
        for (CobolParser.ConfigurationParagraphContext paragraph
                : unit.environmentDivision().configurationSection().configurationParagraph()) {
            if (paragraph.sourceComputerParagraph() == null) {
                continue;
            }
            String written = paragraph.sourceComputerParagraph().getText()
                    .toUpperCase(Locale.ROOT);
            if (written.contains("WITHDEBUGGINGMODE")) {
                return true;
            }
        }
        return false;
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
                    LiteralValue.invalidLiteral(entry.literal().getText(), e)));
            return null;
        }
        // 通貨記号は PICTURE の中の文字として読む。16 進定数では文字が決まらない
        if (!(value instanceof LiteralValue.Text text) || text.isHex()
                || text.text().length() != 1) {
            diagnostics.add(new Diagnostic(origin,
                    "CURRENCY SIGN requires a one-character alphanumeric literal"));
            return null;
        }
        return text.text().charAt(0);
    }

    /**
     * {@code UPSI-0 IS SW-1 ON STATUS IS ON-1 OFF STATUS IS OFF-1} (要件 FR-135)。
     *
     * <p>外から立てる切り替えである。ジョブが立てたところをプログラムが読む。
     * 参照実装は {@code UPSI-0} から {@code UPSI-7} までの 8 個を持つ。
     */
    private static void addSwitch(CobolParser.SwitchClauseContext clause,
                                  Map<String, SwitchStatus> switches,
                                  Map<String, Integer> switchNames, Origin origin,
                                  List<Diagnostic> diagnostics) {
        String device = clause.IDENTIFIER(0).getText().toUpperCase(Locale.ROOT);
        Integer index = switchIndexOf(device);
        if (index == null) {
            diagnostics.add(new Diagnostic(origin, "unknown switch name: " + device
                    + "; write UPSI-0 through UPSI-" + (SWITCHES - 1)));
            return;
        }
        if (clause.IDENTIFIER().size() > 1) {
            switchNames.put(clause.IDENTIFIER(1).getText().toUpperCase(Locale.ROOT), index);
        }
        for (CobolParser.SwitchStatusContext status : clause.switchStatus()) {
            String name = status.IDENTIFIER().getText().toUpperCase(Locale.ROOT);
            if (switches.putIfAbsent(name,
                    new SwitchStatus(index, status.ON() != null)) != null) {
                diagnostics.add(new Diagnostic(origin, "duplicate condition-name: " + name));
            }
        }
    }

    /** {@code UPSI-n} の n。ほかの綴りなら {@code null}。 */
    private static Integer switchIndexOf(String device) {
        if (!device.startsWith("UPSI-") || device.length() != 6) {
            return null;
        }
        char digit = device.charAt(5);
        return digit >= '0' && digit < '0' + SWITCHES ? digit - '0' : null;
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
