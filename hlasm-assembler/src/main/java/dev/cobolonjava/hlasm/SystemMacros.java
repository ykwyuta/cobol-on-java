package dev.cobolonjava.hlasm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 順編成の出力に使うシステムマクロ ({@code DCB} / {@code OPEN} / {@code PUT} / {@code CLOSE}) を、
 * 組み立て系の組み込みの命令として展開する。
 *
 * <p><b>IBM のマクロの原文 (SYS1.MACLIB) は参照していない。</b>展開の形と制御ブロックの並びは
 * この処理系が決めたものであり、z/OS の展開と同じバイト列にはならない。根拠にしたのは DFSMS Macro
 * Instructions for Data Sets が説明する<b>振る舞い</b>だけである (OPEN は R1 が指す表の DCB を開く、
 * 表の各項は 4 バイトで最後の項は上位ビットが 1、PUT は OPEN が DCB に置いた入口へ入る、
 * レジスタ 0・1・14・15 は壊れる)。DCB の中の欄を直接読み書きする資産は、並びが違うので動かない
 * (暫定判断 P-231)。
 *
 * <p>受けるのは固定長 (RECFM=F / FB) の移動方式の出力 (MACRF=PM) だけである。ほかの指定は
 * 近い振る舞いで通さずに断る。
 */
final class SystemMacros {

    /** DCB の長さ。 */
    static final int DCB_LENGTH = 96;
    /** レコード様式の欄 (F は X'80'、ブロック化は X'10' を足す)。 */
    static final int DCB_RECFM = 0x24;
    /** DD 名の欄 (8 バイト)。 */
    static final int DCB_DDNAME = 0x28;
    /** 開いた印の欄。 */
    static final int DCB_OFLGS = 0x30;
    /** 開いていれば立つビット。 */
    static final int OFLGS_OPEN = 0x10;
    /** ブロック長の欄 (半語)。 */
    static final int DCB_BLKSIZE = 0x3E;
    /** レコード長の欄 (半語)。 */
    static final int DCB_LRECL = 0x52;
    /** PUT の入口の番地。OPEN が置き、CLOSE が 0 に戻す。この処理系が決めた位置である。 */
    static final int DCB_ACCESS = 0x5C;

    /** OPEN の表の項の頭のバイト (出力)。 */
    static final int OPTION_OUTPUT = 0x0F;
    /** OPEN の表の項の頭のバイト (書き足し)。 */
    static final int OPTION_EXTEND = 0x0E;

    private SystemMacros() {
    }

    /** システムマクロの文を展開する。ほかの文はそのまま返す。 */
    static List<Statement> expand(List<Statement> statements) {
        List<Statement> out = new ArrayList<>(statements.size());
        for (Statement statement : statements) {
            switch (statement.operation()) {
                case "DCB" -> dcb(statement, out);
                case "OPEN" -> openOrClose(statement, true, out);
                case "CLOSE" -> openOrClose(statement, false, out);
                case "PUT" -> put(statement, out);
                case "GET" -> throw new AssemblyException(statement.line(),
                        "GET is not supported yet; only sequential output (PUT) is");
                default -> out.add(statement);
            }
        }
        return out;
    }

    private static void dcb(Statement statement, List<Statement> out) {
        int line = statement.line();
        Map<String, String> keywords = new LinkedHashMap<>();
        for (String operand : statement.operandList()) {
            int equals = operand.indexOf('=');
            if (equals <= 0) {
                throw new AssemblyException(line, "DCB takes only keyword operands: " + operand);
            }
            keywords.put(operand.substring(0, equals).trim().toUpperCase(Locale.ROOT),
                    unparenthesized(operand.substring(equals + 1).trim()).toUpperCase(Locale.ROOT));
        }
        String ddName = keywords.remove("DDNAME");
        if (ddName == null || !ddName.matches("[A-Z@#$][A-Z0-9@#$]{0,7}")) {
            throw new AssemblyException(line, "DCB requires DDNAME= with a name of 1 to 8 characters");
        }
        String macrf = keywords.remove("MACRF");
        if (!"PM".equals(macrf)) {
            throw new AssemblyException(line, "DCB MACRF=" + macrf
                    + " is not supported yet; only move-mode output (MACRF=PM) is");
        }
        String dsorg = keywords.remove("DSORG");
        if (dsorg != null && !dsorg.equals("PS")) {
            throw new AssemblyException(line, "DCB DSORG=" + dsorg + " is not supported yet");
        }
        String recfm = keywords.remove("RECFM");
        int recfmBits = switch (recfm == null ? "" : recfm) {
            case "F" -> 0x80;
            case "FB" -> 0x90;
            default -> throw new AssemblyException(line, "DCB RECFM=" + recfm
                    + " is not supported yet; only fixed-length records (F, FB) are");
        };
        int lrecl = number(keywords.remove("LRECL"), "LRECL", line);
        String blocks = keywords.remove("BLKSIZE");
        int blksize = blocks == null ? lrecl : number(blocks, "BLKSIZE", line);
        if (blksize % lrecl != 0 || (recfmBits == 0x80 && blksize != lrecl)) {
            throw new AssemblyException(line, "DCB BLKSIZE=" + blksize + " does not fit LRECL=" + lrecl);
        }
        if (!keywords.isEmpty()) {
            throw new AssemblyException(line, "DCB " + String.join(", ", keywords.keySet())
                    + " is not supported yet");
        }
        // 形はこの処理系が決めた 96 バイトである (クラスの説明を参照)
        out.add(new Statement(null, "DS", "0F", line));
        out.add(new Statement(statement.label(), "DS", "0XL" + DCB_LENGTH, line));
        out.add(new Statement(null, "DC", "XL" + DCB_RECFM + "'00'", line));
        out.add(new Statement(null, "DC", "XL4'" + String.format("%02X", recfmBits) + "000000'", line));
        out.add(new Statement(null, "DC", "CL8'" + ddName + "'", line));
        out.add(new Statement(null, "DC", "XL" + (DCB_BLKSIZE - DCB_OFLGS) + "'00'", line));
        out.add(new Statement(null, "DC", "AL2(" + blksize + ")", line));
        out.add(new Statement(null, "DC", "XL" + (DCB_LRECL - DCB_BLKSIZE - 2) + "'00'", line));
        out.add(new Statement(null, "DC", "AL2(" + lrecl + ")", line));
        out.add(new Statement(null, "DC", "XL" + (DCB_ACCESS - DCB_LRECL - 2) + "'00'", line));
        out.add(new Statement(null, "DC", "F'0'", line));
    }

    /**
     * {@code OPEN (dcb,(OUTPUT),...)} と {@code CLOSE (dcb,,...)}。表はリテラルに置き、R1 に番地を入れて
     * SVC で入る。表の項の下 3 バイトは DCB の番地なので、DCB は 16M より下に置く必要がある
     * (載せる側が 3 バイトに入るかを確かめる)。
     */
    private static void openOrClose(Statement statement, boolean opening, List<Statement> out) {
        int line = statement.line();
        List<String> operands = statement.operandList();
        if (operands.size() != 1) {
            throw new AssemblyException(line, statement.operation()
                    + " supports only the list of data control blocks; keywords are not supported yet");
        }
        String list = operands.get(0).trim();
        List<String> items = list.startsWith("(") && list.endsWith(")")
                ? Statement.split(list.substring(1, list.length() - 1), line) : List.of(list);
        List<String> entries = new ArrayList<>();
        for (int k = 0; k < items.size(); k += 2) {
            String dcb = items.get(k).trim();
            String option = k + 1 < items.size()
                    ? unparenthesized(items.get(k + 1).trim()).toUpperCase(Locale.ROOT) : "";
            if (dcb.isEmpty() || dcb.startsWith("(")) {
                throw new AssemblyException(line, statement.operation()
                        + " requires the name of a data control block: " + list);
            }
            int head = 0;
            if (opening) {
                head = switch (option) {
                    case "OUTPUT" -> OPTION_OUTPUT;
                    case "EXTEND" -> OPTION_EXTEND;
                    default -> throw new AssemblyException(line, "OPEN option "
                            + (option.isEmpty() ? "INPUT (the default)" : option)
                            + " is not supported yet; only OUTPUT and EXTEND are");
                };
            } else if (!option.isEmpty()) {
                throw new AssemblyException(line, "CLOSE option " + option + " is not supported yet");
            }
            entries.add(dcb + "|" + head);
        }
        StringBuilder literal = new StringBuilder("=A(");
        for (int k = 0; k < entries.size(); k++) {
            String[] parts = entries.get(k).split("\\|");
            int head = Integer.parseInt(parts[1]) | (k == entries.size() - 1 ? 0x80 : 0);
            if (k > 0) {
                literal.append(',');
            }
            literal.append(String.format("X'%02X000000'+", head)).append(parts[0]);
        }
        literal.append(')');
        out.add(new Statement(statement.label(), "LA", "1," + literal, line));
        out.add(new Statement(null, "SVC", String.valueOf(opening ? DataManagement.SVC_OPEN
                : DataManagement.SVC_CLOSE), line));
    }

    /** {@code PUT dcb,area}。R1 に DCB、R0 にレコードの番地を入れ、DCB の入口へ入る。 */
    private static void put(Statement statement, List<Statement> out) {
        int line = statement.line();
        List<String> operands = statement.operandList();
        if (operands.size() != 2 || operands.get(1).isBlank()) {
            throw new AssemblyException(line,
                    "PUT requires a data control block and a record area; locate mode is not supported yet");
        }
        out.add(load(statement.label(), 1, operands.get(0).trim(), line));
        out.add(load(null, 0, operands.get(1).trim(), line));
        out.add(new Statement(null, "L", "15," + DCB_ACCESS + "(0,1)", line));
        out.add(new Statement(null, "BASR", "14,15", line));
    }

    /** 番地を入れる。{@code (n)} はレジスタに入った番地である。 */
    private static Statement load(String label, int register, String operand, int line) {
        if (operand.startsWith("(") && operand.endsWith(")")) {
            return new Statement(label, "LR", register + "," + operand.substring(1, operand.length() - 1),
                    line);
        }
        return new Statement(label, "LA", register + "," + operand, line);
    }

    private static String unparenthesized(String text) {
        return text.startsWith("(") && text.endsWith(")") ? text.substring(1, text.length() - 1).trim() : text;
    }

    private static int number(String text, String keyword, int line) {
        if (text == null || !text.matches("\\d{1,5}") || Integer.parseInt(text) == 0
                || Integer.parseInt(text) > 32760) {
            throw new AssemblyException(line, "DCB requires " + keyword + "= with a number from 1 to 32760");
        }
        return Integer.parseInt(text);
    }
}
