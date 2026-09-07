package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.PartitionedDataSet;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * {@code IKJEFT01} — バッチの TSO (要件 FR-113, FR-137)。
 *
 * <p>{@code SYSTSIN} に書いた TSO コマンドを上から順に実行し、{@code SYSTSPRT} へ書く。
 * 実装したのは {@code LISTDS} だけである。
 *
 * <h2>知らないコマンドは黙って飛ばさない</h2>
 * <p>実資産の {@code IKJEFT01} は DB2 の {@code DSN} やプログラムの {@code CALL} を
 * 呼ぶのに使われていることが多い。それらを読み飛ばして復帰コード 0 で終われば、
 * <b>何もしていないステップが成功したことになる</b>。知らないコマンドは 12 で知らせる。
 */
public final class Ikjeft01 extends UtilityProgram {

    /** TSO コマンドの入り口。 */
    private static final String SYSTSIN = "SYSTSIN";
    /** TSO の出し先。 */
    private static final String SYSTSPRT = "SYSTSPRT";

    private int highest;

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        highest = 0;
        for (String line : control(context, SYSTSIN)) {
            execute(context, line.strip());
        }
        context.setReturnCode(highest);
    }

    private void execute(ProgramContext context, String line) {
        if (line.isEmpty()) {
            return;
        }
        int space = line.indexOf(' ');
        String command = (space < 0 ? line : line.substring(0, space)).toUpperCase(Locale.ROOT);
        String operands = space < 0 ? "" : line.substring(space + 1).trim();
        if (command.equals("LISTDS")) {
            listds(context, operands);
            return;
        }
        say(context, "IKJ56500I COMMAND " + command + " NOT FOUND");
        highest = Math.max(highest, 12);
    }

    /**
     * {@code LISTDS 'ライブラリ' MEMBERS}。
     *
     * <p>メンバの一覧はディレクトリの並びで出る (要件 FR-053)。並べ直さない — 並べ方を
     * 持っているのは {@link PartitionedDataSet} 1 か所だけである。
     *
     * <p>引用符で囲まない名前には TSO の接頭辞が付くが、接頭辞を持たないのでどちらも
     * 同じに扱う (暫定判断 P-058)。
     */
    private void listds(ProgramContext context, String operands) {
        String name = null;
        boolean members = false;
        for (String word : operands.split("\\s+")) {
            String text = word.trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.toUpperCase(Locale.ROOT).startsWith("MEMBER")) {
                members = true;
            } else if (name == null) {
                name = unquote(text).toUpperCase(Locale.ROOT);
            }
        }
        if (name == null) {
            say(context, "IKJ56701I MISSING DATA SET NAME");
            highest = Math.max(highest, 12);
            return;
        }
        Path path = context.catalog().resolve(name);
        if (!Files.exists(path)) {
            say(context, "IKJ58503I DATA SET " + name + " NOT IN CATALOG");
            highest = Math.max(highest, 12);
            return;
        }
        boolean library = Files.isDirectory(path);
        say(context, name);
        say(context, "--RECFM-LRECL-BLKSIZE-DSORG");
        say(context, "  " + attributes(path, library) + (library ? "PO" : "PS"));
        if (!members) {
            return;
        }
        if (!library) {
            say(context, "IKJ58506I DATA SET IS NOT PARTITIONED");
            highest = Math.max(highest, 12);
            return;
        }
        say(context, "--MEMBERS--");
        for (String member : PartitionedDataSet.members(path, context.codePage())) {
            say(context, "  " + member);
        }
    }

    /**
     * {@code RECFM LRECL BLKSIZE} の欄。
     *
     * <p>ライブラリそのものは覚え書きを持たない — 持っているのはメンバである。ホストの
     * ライブラリは属性を持つが、ここではメンバごとに持つ (暫定判断 P-056 の残り)。
     * 分からない欄は {@code **NONE**} と書く。ホストも分からない欄はそう書く。
     */
    private static String attributes(Path path, boolean library) {
        if (library) {
            return "**NONE** **NONE** **NONE** ";
        }
        DataSetAttributes read = DataSetAttributes.read(path);
        String format = switch (read.format()) {
            case FIXED -> "F";
            case VARIABLE -> "V";
            case LINE -> "VB";
        };
        return format + " " + read.recordLength() + " **NONE** ";
    }

    /** 覚え書きの行き先は {@code SYSTSPRT} である。 */
    private static void say(ProgramContext context, String text) {
        print(context, SYSTSPRT, text);
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}
