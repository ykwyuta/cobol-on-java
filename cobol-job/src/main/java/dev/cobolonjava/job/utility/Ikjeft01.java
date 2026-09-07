package dev.cobolonjava.job.utility;

import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.MemberStatistics;
import dev.cobolonjava.runtime.file.PartitionedDataSet;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code IKJEFT01} — バッチの TSO (要件 FR-113, FR-137)。
 *
 * <p>{@code SYSTSIN} に書いた TSO コマンドを上から順に実行し、{@code SYSTSPRT} へ書く。
 * 実装したのは {@code LISTDS} と、区分データセットのメンバに対する {@code RENAME} /
 * {@code DELETE} である (暫定判断 P-058 の残り)。
 *
 * <h2>{@code RENAME ... ALIAS} が別名を作る (暫定判断 P-059 の解消)</h2>
 * <p>ホストで区分データセットの別名を作るのはこれと連係編集である。{@code RENAME
 * 'PAY.LIB(PAYCALC)' (PAYOLD) ALIAS} と書けば、{@code PAYOLD} が {@code PAYCALC} を指す
 * 項目になる。版を上げたモジュールに古い名前を残す使い方である。
 *
 * <p>メンバの名前を変えても<b>別名は付いてこない</b>。指す先を失った別名はそのまま残り、
 * 開こうとしたときに初めて失敗する。実機でも同じで、ディレクトリの項目は名前を変えた
 * ことを知らない。
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
        switch (command) {
            case "LISTDS" -> listds(context, operands);
            case "RENAME" -> rename(context, operands);
            case "DELETE" -> delete(context, operands);
            default -> {
                say(context, "IKJ56500I COMMAND " + command + " NOT FOUND");
                highest = Math.max(highest, 12);
            }
        }
    }

    /**
     * {@code RENAME 'ライブラリ(メンバ)' (新しい名前) [ALIAS]}。
     *
     * <p>{@code ALIAS} と書けば、名前を変えるのではなく<b>もう 1 つの名前を足す</b>。
     * 元のメンバはそのまま残る。
     *
     * <p>データセットそのものの名前を変えるほうは持っていない。{@code IDCAMS} の
     * {@code ALTER} と重なるので、そちらから先に入れる (暫定判断 P-058)。
     */
    private void rename(ProgramContext context, String operands) {
        List<String> words = words(operands);
        if (words.size() < 2) {
            say(context, "IKJ56701I MISSING DATA SET NAME");
            highest = Math.max(highest, 12);
            return;
        }
        String qualified = unquote(words.get(0)).toUpperCase(Locale.ROOT);
        String member = memberOf(qualified);
        if (member == null) {
            say(context, "IKJ56500I RENAME WITHOUT A MEMBER NAME IS NOT SUPPORTED YET");
            highest = Math.max(highest, 12);
            return;
        }
        String name = libraryOf(qualified);
        String renamed = bare(words.get(1));
        boolean alias = words.stream().skip(2)
                .anyMatch(word -> word.equalsIgnoreCase("ALIAS"));
        Path library = library(context, name);
        if (library == null) {
            return;
        }
        if (!PartitionedDataSet.validName(renamed)) {
            say(context, "IKJ56712I INVALID MEMBER NAME " + renamed);
            highest = Math.max(highest, 12);
            return;
        }
        if (!entryExists(library, member)) {
            say(context, "IKJ58012I MEMBER " + member + " NOT FOUND IN " + name);
            highest = Math.max(highest, 12);
            return;
        }
        if (entryExists(library, renamed)) {
            say(context, "IKJ58013I MEMBER " + renamed + " ALREADY EXISTS IN " + name);
            highest = Math.max(highest, 12);
            return;
        }
        room(context, library, renamed);
        if (alias) {
            PartitionedDataSet.link(library, renamed, member);
            return;
        }
        // 覚え書きも一緒に動かす。置いていくと、次に同じ名前で作った人のものになる
        move(PartitionedDataSet.memberOf(library, member),
                PartitionedDataSet.memberOf(library, renamed));
    }

    /**
     * {@code DELETE 'ライブラリ(メンバ)'}。
     *
     * <p>消えるのは<b>ディレクトリの項目 1 つ</b>である。別名を消しても指していた
     * メンバは残るし、メンバを消しても別名の項目は残る — 残った別名は指す先を失う。
     * どちらもホストの姿である。
     */
    private void delete(ProgramContext context, String operands) {
        List<String> words = words(operands);
        if (words.isEmpty()) {
            say(context, "IKJ56701I MISSING DATA SET NAME");
            highest = Math.max(highest, 12);
            return;
        }
        String qualified = unquote(words.get(0)).toUpperCase(Locale.ROOT);
        String member = memberOf(qualified);
        if (member == null) {
            // データセットそのものを消すのは IDCAMS の DELETE と重なる (暫定判断 P-058)
            say(context, "IKJ56500I DELETE WITHOUT A MEMBER NAME IS NOT SUPPORTED YET");
            highest = Math.max(highest, 12);
            return;
        }
        String name = libraryOf(qualified);
        Path library = library(context, name);
        if (library == null) {
            return;
        }
        if (!PartitionedDataSet.unlink(library, member)) {
            say(context, "IKJ58012I MEMBER " + member + " NOT FOUND IN " + name);
            highest = Math.max(highest, 12);
        }
    }

    /**
     * 名前が指すライブラリ。
     *
     * @return 区分データセットでなければ {@code null}
     */
    private Path library(ProgramContext context, String name) {
        Path path = context.catalog().resolve(name);
        if (!Files.exists(path)) {
            say(context, "IKJ58503I DATA SET " + name + " NOT IN CATALOG");
            highest = Math.max(highest, 12);
            return null;
        }
        if (!Files.isDirectory(path)) {
            say(context, "IKJ58506I DATA SET IS NOT PARTITIONED");
            highest = Math.max(highest, 12);
            return null;
        }
        return path;
    }

    /** 切れた別名も項目である。{@link Files#exists} は切れたリンクに偽を返す。 */
    private static boolean entryExists(Path library, String name) {
        return Files.exists(PartitionedDataSet.memberOf(library, name),
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    /** メンバと、その覚え書きのサイドカーを動かす。 */
    private static void move(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            moveIfPresent(DataSetAttributes.sidecarOf(from), DataSetAttributes.sidecarOf(to));
            moveIfPresent(MemberStatistics.sidecarOf(from), MemberStatistics.sidecarOf(to));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot rename " + from, e);
        }
    }

    private static void moveIfPresent(Path from, Path to) throws IOException {
        if (Files.exists(from)) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 空白で切った語。 */
    private static List<String> words(String operands) {
        List<String> out = new ArrayList<>();
        for (String word : operands.split("\\s+")) {
            String text = word.trim();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }

    /** {@code 'PAY.LIB(MEM)'} のメンバの部分。メンバを書いていなければ {@code null}。 */
    private static String memberOf(String name) {
        int open = name.indexOf('(');
        if (open < 0 || !name.endsWith(")")) {
            return null;
        }
        return name.substring(open + 1, name.length() - 1).trim();
    }

    /** {@code 'PAY.LIB(MEM)'} のライブラリの部分。 */
    private static String libraryOf(String name) {
        int open = name.indexOf('(');
        return open < 0 ? name : name.substring(0, open).trim();
    }

    /** {@code (NEW)} の括弧と引用符を外す。 */
    private static String bare(String word) {
        String text = unquote(word.trim()).trim();
        if (text.length() >= 2 && text.startsWith("(") && text.endsWith(")")) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text.toUpperCase(Locale.ROOT);
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
