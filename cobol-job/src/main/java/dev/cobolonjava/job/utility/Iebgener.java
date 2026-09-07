package dev.cobolonjava.job.utility;

import dev.cobolonjava.job.jcl.JclOperands;
import dev.cobolonjava.runtime.codepage.CodePage;
import dev.cobolonjava.runtime.decimal.Decimal;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.RecordFormat;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * {@code IEBGENER} (要件 FR-137)。
 *
 * <p>{@code SYSUT1} を {@code SYSUT2} へ写す。バッチでいちばんよく使われるユーティリティで
 * ある。制御文を書かなければ<b>そのまま</b>写し、書けば<b>組み直しながら</b>写す。
 *
 * <h2>バイトのまま写す</h2>
 * <p>制御文が無いときは、レコードへ切ってから書き直すのではなく、バイト列と属性をそのまま
 * 写す。切って書き直せば、同じ属性なら同じバイトに戻るはずだが、<b>戻るはずだ</b>という
 * 仮定を挟まずに済む。写しは写しである。
 *
 * <p>写せないものは写さない。区分データセットの無いメンバなら {@code S013}、形が壊れて
 * いれば {@code S001}、割り当てた領域に収まらなければ {@code S037} である。翻訳された資産と
 * 同じ検査を通す (暫定判断 P-053)。
 *
 * <h2>組を分けるのは「終わりの目印」である (暫定判断 P-047 の解消)</h2>
 * <p>{@code RECORD} を 2 つ以上書くと、入力は<b>組</b>に分かれる。分かれ目を言うのは
 * {@code IDENT} であり、これは組の<b>最後のレコード</b>を指す。始まりではない。
 *
 * <pre>
 *   GENERATE MAXFLDS=3,MAXGPS=1,MAXLITS=1
 *   RECORD   IDENT=(3,'END',1),FIELD=(3,1,,1),FIELD=(5,'HEAD ',,10)
 *   RECORD   FIELD=(8,1,,1)
 * </pre>
 *
 * <p>目印の付いたレコードまでが 1 つめの組で、そのレコード自身も組に入る。ホストの
 * 見出し・明細・合計という並びは、区切りの行を<b>見てから</b>切り替えるからこう書ける。
 *
 * <h2>数え上げは約束である</h2>
 * <p>{@code MAXFLDS} などは「いくつ書くか」をあらかじめ言うものである。ホストが領域を
 * 取るための数だが、<b>書いた数と合わなければ止まる</b>。読み飛ばすと、書き忘れた
 * {@code FIELD} が黙って効かないまま正常終了する。
 */
public final class Iebgener extends UtilityProgram {

    /** 写し元。 */
    private static final String SYSUT1 = "SYSUT1";
    /** 写し先。 */
    private static final String SYSUT2 = "SYSUT2";

    /**
     * 1 か所の移し替え。
     *
     * @param literal 定数を置くなら、そのバイト列。レコードから取るなら {@code null}
     * @param from 取り出す位置 (0 から数える)
     * @param to 置く位置 (0 から数える)
     */
    private record Field(int length, int from, byte[] literal, String conversion, int to) {
    }

    /**
     * 1 つの組。
     *
     * @param ident 組の終わりの目印。無ければ {@code null}
     * @param identAt 目印を見る位置 (0 から数える)
     */
    private record Group(byte[] ident, int identAt, List<Field> fields) {
    }

    /** 覚え書きを出したうえで止めるための印。 */
    private static final class Refused extends RuntimeException {

        Refused(String text) {
            super(text, null, false, false);
        }
    }

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        List<String> statements = statements(control(context, SYSIN));
        Path from = opened(context, SYSUT1);
        if (!Files.isReadable(from)) {
            print(context, "IEB000I SYSUT1 NOT FOUND");
            context.setReturnCode(12);
            return;
        }
        Path to = created(context, SYSUT2);
        byte[] bytes = readSound(context, from);
        DataSetAttributes attributes = DataSetAttributes.read(from);
        if (statements.isEmpty()) {
            writeSound(context, to, bytes);
            attributes.write(to);
            print(context, "IEB147I " + records(bytes, attributes) + " RECORDS COPIED");
            context.setReturnCode(0);
            return;
        }
        List<byte[]> out;
        try {
            List<Group> groups = groupsOf(statements, context.codePage());
            if (groups.isEmpty()) {
                // GENERATE だけなら、組み直すものが無い。そのまま写す
                writeSound(context, to, bytes);
                attributes.write(to);
                print(context, "IEB147I " + records(bytes, attributes) + " RECORDS COPIED");
                context.setReturnCode(0);
                return;
            }
            if (attributes.format() == RecordFormat.VARIABLE) {
                // RDW を組み直す道がまだない (暫定判断 P-047)
                throw new Refused("IEB000I VARIABLE RECORDS CANNOT BE EDITED YET");
            }
            out = edited(Records.split(bytes, attributes), groups, context.codePage());
        } catch (Refused refused) {
            print(context, refused.getMessage());
            context.setReturnCode(12);
            return;
        }
        Records.Framed framed = Records.join(out, attributes, context.codePage(), true);
        writeSound(context, to, framed.bytes());
        framed.attributes().write(to);
        print(context, "IEB147I " + out.size() + " RECORDS COPIED");
        context.setReturnCode(0);
    }

    // ---- 制御文を読む ----

    /**
     * 制御文を組の並びへ読む。
     *
     * <p>{@code GENERATE} が数え上げを言い、{@code RECORD} が組を 1 つ足す。
     */
    private static List<Group> groupsOf(List<String> statements, CodePage codePage) {
        int maxFields = -1;
        int maxGroups = -1;
        int maxLiterals = -1;
        List<Group> groups = new ArrayList<>();
        for (String statement : statements) {
            String name = head(statement).toUpperCase(Locale.ROOT);
            String operands = tail(statement);
            switch (name) {
                case "GENERATE" -> {
                    for (String operand : JclOperands.split(operands)) {
                        String key = JclOperands.key(operand).trim().toUpperCase(Locale.ROOT);
                        int value = number(JclOperands.value(operand), -1);
                        switch (key) {
                            case "MAXFLDS" -> maxFields = value;
                            case "MAXGPS" -> maxGroups = value;
                            case "MAXLITS" -> maxLiterals = value;
                            // MAXNAME はメンバの数である。MEMBER を読めないので数えない
                            case "MAXNAME", "" -> {
                                // 何もしない
                            }
                            default -> throw new Refused(
                                    "IEB000I GENERATE OPERAND IS NOT SUPPORTED YET: " + key);
                        }
                    }
                }
                case "RECORD" -> groups.add(groupOf(operands, codePage));
                default -> throw new Refused(
                        "IEB000I SYSIN CONTROL STATEMENT IS NOT SUPPORTED YET: " + name);
            }
        }
        promised(groups, maxFields, maxGroups, maxLiterals);
        return groups;
    }

    /**
     * 数え上げた数と、書いた数が合っているか。
     *
     * <p>{@code FIELD} を書くなら {@code MAXFLDS} が、{@code IDENT} を書くなら
     * {@code MAXGPS} が、定数を書くなら {@code MAXLITS} が要る。ホストと同じく<b>足りなければ
     * 止める</b>。
     */
    private static void promised(List<Group> groups, int maxFields, int maxGroups,
                                 int maxLiterals) {
        int fields = 0;
        int idents = 0;
        int literals = 0;
        for (Group group : groups) {
            fields += group.fields().size();
            idents += group.ident() == null ? 0 : 1;
            for (Field field : group.fields()) {
                literals += field.literal() == null ? 0 : field.literal().length;
            }
        }
        if (fields > 0 && maxFields < fields) {
            throw new Refused("IEB000I MAXFLDS IS TOO SMALL FOR " + fields + " FIELDS");
        }
        if (idents > 0 && maxGroups < idents) {
            throw new Refused("IEB000I MAXGPS IS TOO SMALL FOR " + idents + " GROUPS");
        }
        if (literals > 0 && maxLiterals < literals) {
            throw new Refused("IEB000I MAXLITS IS TOO SMALL FOR " + literals + " BYTES");
        }
    }

    /** {@code RECORD} 1 つ。 */
    private static Group groupOf(String operands, CodePage codePage) {
        byte[] ident = null;
        int identAt = 0;
        List<Field> fields = new ArrayList<>();
        for (String operand : JclOperands.split(operands)) {
            String key = JclOperands.key(operand).trim().toUpperCase(Locale.ROOT);
            List<String> parts = JclOperands.split(JclOperands.unwrap(JclOperands.value(operand)));
            switch (key) {
                case "IDENT" -> {
                    if (parts.size() < 3) {
                        throw new Refused("IEB000I IDENT IS NOT VALID: " + operand);
                    }
                    ident = quoted(parts.get(1), codePage);
                    if (ident == null) {
                        throw new Refused("IEB000I IDENT NEEDS A LITERAL: " + operand);
                    }
                    ident = shortened(ident, number(parts.get(0), ident.length), codePage);
                    identAt = number(parts.get(2), 1) - 1;
                }
                case "FIELD" -> fields.add(fieldOf(parts, operand, codePage));
                case "" -> {
                    // 何もしない
                }
                default -> throw new Refused(
                        "IEB000I RECORD OPERAND IS NOT SUPPORTED YET: " + key);
            }
        }
        return new Group(ident, identAt, List.copyOf(fields));
    }

    /**
     * {@code FIELD=(長さ,入力の位置,変換,出力の位置)}。
     *
     * <p>入力の位置のところに定数を書ける。長さを書かなければ定数の長さになる。
     */
    private static Field fieldOf(List<String> parts, String operand, CodePage codePage) {
        int at = 0;
        int length = -1;
        if (!parts.isEmpty() && quoted(parts.get(0), codePage) == null
                && !parts.get(0).isBlank()) {
            length = number(parts.get(0), -1);
            if (length < 1) {
                throw new Refused("IEB000I FIELD LENGTH IS NOT VALID: " + operand);
            }
            at = 1;
        }
        byte[] literal = null;
        int from = 0;
        if (at < parts.size() && !parts.get(at).isBlank()) {
            literal = quoted(parts.get(at), codePage);
            if (literal == null) {
                from = number(parts.get(at), 1) - 1;
            }
        }
        if (literal != null) {
            literal = shortened(literal, length < 0 ? literal.length : length, codePage);
            length = literal.length;
        } else if (length < 0) {
            throw new Refused("IEB000I FIELD LENGTH IS NOT VALID: " + operand);
        }
        String conversion = at + 1 < parts.size()
                ? parts.get(at + 1).trim().toUpperCase(Locale.ROOT)
                : "";
        if (!conversion.isEmpty() && !conversion.equals("PZ") && !conversion.equals("ZP")) {
            throw new Refused("IEB000I FIELD CONVERSION IS NOT SUPPORTED YET: " + conversion);
        }
        if (!conversion.isEmpty() && literal != null) {
            throw new Refused("IEB000I FIELD CANNOT CONVERT A LITERAL: " + operand);
        }
        int to = at + 2 < parts.size() ? number(parts.get(at + 2), 1) - 1 : 0;
        return new Field(length, from, literal, conversion, Math.max(to, 0));
    }

    // ---- 組み直す ----

    /**
     * 組ごとの指示でレコードを組み直す。
     *
     * <p>目印の付いたレコードを見たら、<b>そのレコードを組へ入れてから</b>次の組へ移る。
     * 組を使い切ったあとは最後の組のままである (暫定判断 P-047)。
     */
    private static List<byte[]> edited(List<byte[]> records, List<Group> groups,
                                       CodePage codePage) {
        List<byte[]> out = new ArrayList<>();
        int at = 0;
        for (byte[] record : records) {
            Group group = groups.get(at);
            out.add(rebuilt(record, group, codePage));
            if (group.ident() != null && at + 1 < groups.size() && marks(record, group)) {
                at++;
            }
        }
        return out;
    }

    /** 目印の付いたレコードか。 */
    private static boolean marks(byte[] record, Group group) {
        byte[] seen = SortField.slice(record, group.identAt(), group.ident().length, (byte) 0);
        return SortField.compareBytes(seen, group.ident()) == 0;
    }

    /**
     * 1 レコードを組み直す。{@code FIELD} が無ければそのままである。
     *
     * <p>置く場所は<b>前へ戻ってもよい</b>。何桁目に置くかを言うのだから、書く順は
     * 並びの順と関わりがない。
     */
    private static byte[] rebuilt(byte[] record, Group group, CodePage codePage) {
        if (group.fields().isEmpty()) {
            return record;
        }
        byte[] out = new byte[0];
        for (Field field : group.fields()) {
            byte[] bytes = field.literal() != null
                    ? field.literal()
                    : converted(field, record, codePage);
            if (field.to() + bytes.length > out.length) {
                byte[] wider = new byte[field.to() + bytes.length];
                Arrays.fill(wider, codePage.space());
                System.arraycopy(out, 0, wider, 0, out.length);
                out = wider;
            }
            System.arraycopy(bytes, 0, out, field.to(), bytes.length);
        }
        return out;
    }

    /**
     * 変換して取り出す。
     *
     * <p>{@code PZ} はパック 10 進数をゾーン 10 進数へ、{@code ZP} はその逆へ移す。
     * 桁数はホストの {@code UNPK} / {@code PACK} と同じ数え方にしてある (暫定判断 P-047)。
     */
    private static byte[] converted(Field field, byte[] record, CodePage codePage) {
        if (field.conversion().isEmpty()) {
            return SortField.slice(record, field.from(), field.length(), codePage.space());
        }
        boolean unpacking = field.conversion().equals("PZ");
        SortField source = SortField.at(field.from(), field.length(),
                unpacking ? SortField.Format.PD : SortField.Format.ZD);
        if (!source.valid(record, codePage)) {
            throw new Refused("IEB000I FIELD AT " + (field.from() + 1)
                    + " IS NOT A DECIMAL VALUE");
        }
        Decimal value = source.number(record, codePage);
        int width = unpacking ? 2 * field.length() - 1 : field.length() / 2 + 1;
        return SortField.at(0, width, unpacking ? SortField.Format.ZD : SortField.Format.PD)
                .encode(value);
    }

    // ---- 細かな道具 ----

    /** 制御文をつなぐ。コンマで終わる行は次へ続く。 */
    private static List<String> statements(List<String> lines) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            String text = line.strip();
            if (text.startsWith("/*") || text.isEmpty()) {
                continue;
            }
            boolean continued = text.endsWith(",");
            current.append(text);
            if (!continued) {
                out.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    /** 1 語目。 */
    private static String head(String text) {
        int space = text.indexOf(' ');
        return space < 0 ? text : text.substring(0, space);
    }

    /** 1 語目より後ろ。 */
    private static String tail(String text) {
        int space = text.indexOf(' ');
        return space < 0 ? "" : text.substring(space + 1).trim();
    }

    /**
     * 引用符でくくった定数。
     *
     * @return 定数でなければ {@code null}
     */
    private static byte[] quoted(String text, CodePage codePage) {
        String written = text.trim();
        if (written.length() < 2 || written.charAt(0) != '\'' || !written.endsWith("'")) {
            return null;
        }
        return codePage.encode(JclOperands.unquote(written));
    }

    /** 決まった長さへ揃える。 */
    private static byte[] shortened(byte[] bytes, int length, CodePage codePage) {
        return SortField.padded(bytes, Math.max(length, 1), codePage.space());
    }

    private static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 写したレコードの数。覚え書きに書く。 */
    private static int records(byte[] bytes, DataSetAttributes attributes) {
        return switch (attributes.format()) {
            case FIXED -> attributes.recordLength() <= 0
                    ? 0
                    : (bytes.length + attributes.recordLength() - 1) / attributes.recordLength();
            case VARIABLE -> countVariable(bytes);
            case LINE -> countLines(bytes, attributes);
        };
    }

    private static int countVariable(byte[] bytes) {
        int count = 0;
        int at = 0;
        while (at + 4 <= bytes.length) {
            int length = ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
            if (length < 4 || at + length > bytes.length) {
                break;
            }
            count++;
            at += length;
        }
        return count;
    }

    private static int countLines(byte[] bytes, DataSetAttributes attributes) {
        return lines(bytes, attributes.codePage()).size();
    }
}
