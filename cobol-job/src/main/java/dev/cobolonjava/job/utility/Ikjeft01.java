package dev.cobolonjava.job.utility;

import dev.cobolonjava.job.SystemCatalog;
import dev.cobolonjava.runtime.codepage.CodePages;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.MemberStatistics;
import dev.cobolonjava.runtime.file.PartitionedDataSet;
import dev.cobolonjava.runtime.file.RecordFormat;
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
import java.util.Map;

/**
 * {@code IKJEFT01} — バッチの TSO (要件 FR-113, FR-137)。
 *
 * <p>{@code SYSTSIN} に書いた TSO コマンドを上から順に実行し、{@code SYSTSPRT} へ書く。
 * 実装したのは {@code LISTDS}、{@code ALLOCATE} / {@code FREE}、{@code LISTCAT ENTRIES}、
 * データセットとメンバの {@code RENAME} / {@code DELETE} である (暫定判断 P-058)。行末の {@code +} と
 * {@code -} は次の行へ続く。
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
        allocations.clear();
        generated = 0;
        for (String line : joined(control(context, SYSTSIN))) {
            execute(context, line.strip());
        }
        // 解放しないまま終われば、終わるところで解放する (TSO を抜けるときと同じ)
        for (Allocation allocation : List.copyOf(allocations)) {
            release(context, allocation, allocation.disposition());
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
            case "LISTDS", "LISTD" -> listds(context, operands);
            case "ALLOCATE", "ALLOC" -> allocate(context, operands);
            case "FREE" -> free(context, operands);
            case "LISTCAT", "LISTC" -> listcat(context, operands);
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
     * <p>メンバを書かなければ、データセットそのものの名前を変える ({@link #renameDataSet})。
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
            renameDataSet(context, qualified, unquote(words.get(1)).toUpperCase(Locale.ROOT));
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
            deleteDataSet(context, qualified, false);
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
            } else {
                // STATUS / HISTORY / LABEL の出し方は確かめていない。黙って読み飛ばすと、書いた人は
                // 出ない行を探すことになる
                say(context, "IKJ56500I LISTDS " + text.toUpperCase(Locale.ROOT)
                        + " IS NOT SUPPORTED YET");
                highest = Math.max(highest, 12);
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


    // ---- データセットの割当て (暫定判断 P-058) ----

    /**
     * {@code ALLOCATE} で取った割当て 1 つ。
     *
     * @param dataSet     データセットの名前
     * @param ddName      結び付けた DD 名。{@code FILE(dd)} を書かなければ作った名前
     * @param disposition 解放するときの始末 ({@code KEEP} / {@code CATALOG} / {@code DELETE} /
     *                    {@code UNCATALOG})。書かなければ {@code null}
     */
    private record Allocation(String dataSet, String ddName, String disposition) {
    }

    /** このステップの間に {@code ALLOCATE} で取った割当て。{@code FREE} するか、終わりに解放する。 */
    private final List<Allocation> allocations = new ArrayList<>();
    /** DD 名を書かなかった割当てに付ける番号。ホストの {@code SYS00001} にならう。 */
    private int generated;

    /** 3390 のトラックは 56664 バイト、シリンダは 15 トラックである (JCL の SPACE と同じ)。 */
    private static final long TRACK = 56664L;

    /**
     * {@code ALLOCATE DATASET('名前') NEW|OLD|SHR|MOD ...}。
     *
     * <p>{@code NEW} なら空のデータセットを作り、属性 ({@code RECFM} / {@code LRECL}) をサイドカーへ
     * 残す。{@code DIR(n)} か {@code DSORG(PO)} なら区分データセットにする。{@code CATALOG} なら
     * 目録へ載せ、{@code KEEP} なら置き場にだけ置く。{@code FILE(dd)} を書けば、あとで
     * {@code CALL} するプログラムがその DD 名で開ける。{@code SPACE} は {@code FILE} を書いたときだけ
     * その DD の限りになる (JCL と同じく 16 エクステント)。
     *
     * <p>知らない語は断る。装置 ({@code UNIT} / {@code VOLUME}) も断る。置き場は 1 つしかない。
     */
    private void allocate(ProgramContext context, String operands) {
        Map<String, String> words = keywords(operands);
        String name = firstOf(words, "DATASET", "DSNAME", "DA", "DSN");
        String dd = firstOf(words, "FILE", "DDNAME", "FI", "DD");
        for (String key : words.keySet()) {
            if (!ALLOCATE_WORDS.contains(key)) {
                say(context, "IKJ56712I INVALID KEYWORD, " + key);
                highest = Math.max(highest, 12);
                return;
            }
        }
        if (name == null) {
            say(context, "IKJ56701I MISSING DATA SET NAME");
            highest = Math.max(highest, 12);
            return;
        }
        name = unquote(name).toUpperCase(Locale.ROOT);
        String status = words.containsKey("NEW") ? "NEW"
                : words.containsKey("MOD") ? "MOD"
                : words.containsKey("OLD") ? "OLD" : "SHR";
        Path path = context.catalog().resolve(name);
        SystemCatalog system = new SystemCatalog(context.catalog().directory());
        boolean exists = Files.exists(path) && system.isCataloged(name);
        if (status.equals("NEW") && Files.exists(path)) {
            say(context, "IKJ56893I DATA SET " + name + " NOT ALLOCATED, IT ALREADY EXISTS");
            highest = Math.max(highest, 12);
            return;
        }
        if (!status.equals("NEW") && !status.equals("MOD") && !exists) {
            say(context, "IKJ56228I DATA SET " + name + " NOT IN CATALOG");
            highest = Math.max(highest, 12);
            return;
        }
        if (status.equals("NEW") || (status.equals("MOD") && !exists)) {
            create(path, words);
            if (words.containsKey("KEEP") || words.containsKey("UNCATALOG")) {
                system.uncatalog(name);
            } else {
                // ALLOCATE の NEW は、書かなければ CATALOG である (解放するときに載る)
                system.catalog(name);
            }
        }
        String ddName = dd == null ? String.format("SYS%05d", ++generated)
                : dd.toUpperCase(Locale.ROOT);
        context.catalog().assign(ddName, path);
        long limit = spaceOf(words);
        if (limit > 0) {
            context.catalog().limit(ddName, limit);
            context.catalog().secondary(ddName, secondaryOf(words) > 0);
        }
        String disposition = words.containsKey("DELETE") ? "DELETE"
                : words.containsKey("UNCATALOG") ? "UNCATALOG" : null;
        allocations.add(new Allocation(name, ddName, disposition));
    }

    /** {@code ALLOCATE} が受け取る語。 */
    private static final java.util.Set<String> ALLOCATE_WORDS = java.util.Set.of(
            "DATASET", "DSNAME", "DA", "DSN", "FILE", "DDNAME", "FI", "DD",
            "NEW", "OLD", "SHR", "MOD", "CATALOG", "KEEP", "DELETE", "UNCATALOG",
            "SPACE", "TRACKS", "CYLINDERS", "BLOCK", "RECFM", "LRECL", "BLKSIZE", "DSORG",
            "DIR", "REUSE");

    /** 空のデータセットを作る。区分なら空のライブラリ。 */
    private static void create(Path path, Map<String, String> words) {
        String recfm = words.getOrDefault("RECFM", "F B").toUpperCase(Locale.ROOT);
        RecordFormat format = recfm.startsWith("V") ? RecordFormat.VARIABLE : RecordFormat.FIXED;
        int lrecl = (int) number(words.getOrDefault("LRECL", "80"));
        int directory = (int) number(words.getOrDefault("DIR", "0"));
        boolean partitioned = directory > 0
                || "PO".equalsIgnoreCase(words.getOrDefault("DSORG", "PS").trim());
        DataSetAttributes attributes = new DataSetAttributes(format, lrecl > 0 ? lrecl : 80,
                CodePages.DEFAULT);
        try {
            if (partitioned) {
                Files.createDirectories(path);
                attributes.withDirectoryBlocks(directory).write(path);
            } else {
                Files.createDirectories(path.getParent());
                Files.write(path, new byte[0]);
                attributes.write(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot allocate " + path, e);
        }
    }

    /** {@code SPACE(一次,二次)} と単位から、書ける大きさ。書かなければ 0 (限りなし)。 */
    private static long spaceOf(Map<String, String> words) {
        String space = words.get("SPACE");
        if (space == null) {
            return 0;
        }
        long unit = words.containsKey("CYLINDERS") ? TRACK * 15
                : words.containsKey("BLOCK") ? number(words.get("BLOCK"))
                : TRACK;
        String[] parts = space.split(",");
        long primary = number(parts[0]);
        long secondary = parts.length > 1 ? number(parts[1]) : 0;
        return (primary + 15 * secondary) * unit;
    }

    private static long secondaryOf(Map<String, String> words) {
        String space = words.get("SPACE");
        String[] parts = space == null ? new String[0] : space.split(",");
        return parts.length > 1 ? number(parts[1]) : 0;
    }

    /**
     * {@code FREE FILE(dd)} / {@code FREE DATASET('名前')} / {@code FREE ALL}。
     *
     * <p>{@code ALLOCATE} で取っていないものを解放しようとすれば 12 で知らせる。
     * {@code DELETE} を書けば (または取ったときに書いてあれば) データセットを消す。
     */
    private void free(ProgramContext context, String operands) {
        Map<String, String> words = keywords(operands);
        String name = firstOf(words, "DATASET", "DSNAME", "DA", "DSN");
        String dd = firstOf(words, "FILE", "DDNAME", "FI", "DD");
        boolean all = words.containsKey("ALL");
        if (name == null && dd == null && !all) {
            say(context, "IKJ56701I MISSING DATA SET NAME OR FILE NAME");
            highest = Math.max(highest, 12);
            return;
        }
        String wanted = name == null ? null : unquote(name).toUpperCase(Locale.ROOT);
        String wantedDd = dd == null ? null : dd.toUpperCase(Locale.ROOT);
        List<Allocation> freed = new ArrayList<>();
        for (Allocation allocation : allocations) {
            if (all || allocation.dataSet().equals(wanted) || allocation.ddName().equals(wantedDd)) {
                freed.add(allocation);
            }
        }
        if (freed.isEmpty()) {
            say(context, "IKJ56247I " + (wanted != null ? "DATA SET " + wanted : "FILE " + wantedDd)
                    + " NOT FREED, IS NOT ALLOCATED");
            highest = Math.max(highest, 12);
            return;
        }
        for (Allocation allocation : freed) {
            release(context, allocation, words.containsKey("DELETE") ? "DELETE"
                    : words.containsKey("UNCATALOG") ? "UNCATALOG" : allocation.disposition());
        }
    }

    /** 割当てを解き、始末をつける。 */
    private void release(ProgramContext context, Allocation allocation, String disposition) {
        allocations.remove(allocation);
        context.catalog().release(allocation.ddName());
        if ("DELETE".equals(disposition)) {
            deleteDataSet(context, allocation.dataSet(), false);
        } else if ("UNCATALOG".equals(disposition)) {
            new SystemCatalog(context.catalog().directory()).uncatalog(allocation.dataSet());
        }
    }

    /**
     * {@code LISTCAT ENTRIES('名前' ...)}。{@code IDCAMS} の {@code LISTCAT} と同じ行を出す。
     *
     * <p>{@code LEVEL} などほかの選び方は持たない。載っていない名前は 4 で知らせる。
     */
    private void listcat(ProgramContext context, String operands) {
        Map<String, String> words = keywords(operands);
        String entries = firstOf(words, "ENTRIES", "ENT", "ENTRY");
        for (String key : words.keySet()) {
            if (!List.of("ENTRIES", "ENT", "ENTRY", "ALL", "NAME", "NAMES").contains(key)) {
                say(context, "IKJ56712I INVALID KEYWORD, " + key);
                highest = Math.max(highest, 12);
                return;
            }
        }
        if (entries == null) {
            say(context, "IKJ56500I LISTCAT WITHOUT ENTRIES IS NOT SUPPORTED YET");
            highest = Math.max(highest, 12);
            return;
        }
        SystemCatalog system = new SystemCatalog(context.catalog().directory());
        for (String entry : words(entries.replace(",", " "))) {
            String name = unquote(entry).toUpperCase(Locale.ROOT);
            if (Files.exists(context.catalog().resolve(name)) && system.isCataloged(name)) {
                say(context, "NONVSAM ------- " + name);
            } else {
                say(context, "IDC3012I ENTRY " + name + " NOT FOUND");
                highest = Math.max(highest, 4);
            }
        }
    }

    /**
     * {@code RENAME '古い名前' '新しい名前'} (データセットそのもの)。
     *
     * <p>置き場のファイル (区分ならディレクトリ) とサイドカーを動かし、目録の項目を付け替える。
     * 新しい名前が既にあれば断る。{@code ALLOCATE} で取っている最中のデータセットの名前を変えて
     * よいかは確かめていない (z/OS probe の {@code JCLTSO})。ここでは変えられる。
     */
    private void renameDataSet(ProgramContext context, String from, String to) {
        Path source = context.catalog().resolve(from);
        Path target = context.catalog().resolve(to);
        SystemCatalog system = new SystemCatalog(context.catalog().directory());
        if (!Files.exists(source) || !system.isCataloged(from)) {
            say(context, "IKJ58503I DATA SET " + from + " NOT IN CATALOG");
            highest = Math.max(highest, 12);
            return;
        }
        if (Files.exists(target)) {
            say(context, "IKJ58509I DATA SET " + to + " NOT RENAMED, IT ALREADY EXISTS");
            highest = Math.max(highest, 12);
            return;
        }
        move(source, target);
        system.forget(from);
        system.catalog(to);
    }

    /**
     * データセットそのものを消す ({@code DELETE '名前'})。{@code IDCAMS} の {@code DELETE} と同じ
     * 覚え書きを出す。無ければ 8 である。
     *
     * @param quiet 見つからなくても知らせない ({@code FREE} の {@code DELETE})
     */
    private void deleteDataSet(ProgramContext context, String name, boolean quiet) {
        Path path = context.catalog().resolve(name);
        SystemCatalog system = new SystemCatalog(context.catalog().directory());
        if (!Files.exists(path) || !system.isCataloged(name)) {
            if (!quiet) {
                say(context, "IDC3012I ENTRY " + name + " NOT FOUND");
                highest = Math.max(highest, 8);
            }
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (var walk = Files.walk(path)) {
                    for (Path each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(each);
                    }
                }
            } else {
                Files.deleteIfExists(path);
            }
            Files.deleteIfExists(DataSetAttributes.sidecarOf(path));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + path, e);
        }
        system.forget(name);
        say(context, "IDC0550I ENTRY (A) " + name + " DELETED");
    }

    /**
     * {@code 語(値)} と {@code 語} の並びを読む。括弧の中の空白は値の一部である
     * ({@code RECFM(F B)})。
     */
    private static Map<String, String> keywords(String operands) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        int i = 0;
        while (i < operands.length()) {
            while (i < operands.length() && operands.charAt(i) == ' ') {
                i++;
            }
            int start = i;
            while (i < operands.length() && operands.charAt(i) != ' ' && operands.charAt(i) != '(') {
                i++;
            }
            if (start == i) {
                break;
            }
            String key = operands.substring(start, i).toUpperCase(Locale.ROOT);
            String value = null;
            if (i < operands.length() && operands.charAt(i) == '(') {
                int depth = 0;
                int open = i;
                boolean quoted = false;
                for (; i < operands.length(); i++) {
                    char c = operands.charAt(i);
                    if (c == '\'') {
                        quoted = !quoted;
                    } else if (!quoted && c == '(') {
                        depth++;
                    } else if (!quoted && c == ')' && --depth == 0) {
                        break;
                    }
                }
                value = operands.substring(open + 1, Math.min(i, operands.length())).trim();
                i++;
            }
            out.put(key, value);
        }
        return out;
    }

    private static String firstOf(Map<String, String> words, String... keys) {
        for (String key : keys) {
            if (words.get(key) != null) {
                return words.get(key);
            }
        }
        return null;
    }

    private static long number(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return 0;
        }
    }

    /**
     * 続きの行をつなぐ。行末の {@code +} は次の行の頭の空白を落としてつなぎ、{@code -} は
     * そのままつなぐ (TSO の続きの書き方)。
     */
    private static List<String> joined(List<String> lines) {
        List<String> out = new ArrayList<>();
        StringBuilder pending = null;
        for (String raw : lines) {
            String line = raw.stripTrailing();
            String piece = pending == null ? line.strip() : line;
            if (pending != null && pending.charAt(pending.length() - 1) == '+') {
                pending.setLength(pending.length() - 1);
                piece = line.strip();
            } else if (pending != null) {
                pending.setLength(pending.length() - 1);
            }
            StringBuilder current = pending == null ? new StringBuilder() : pending;
            current.append(piece);
            if (!current.isEmpty() && (current.charAt(current.length() - 1) == '+'
                    || current.charAt(current.length() - 1) == '-')) {
                pending = current;
                continue;
            }
            out.add(current.toString());
            pending = null;
        }
        if (pending != null) {
            out.add(pending.toString());
        }
        return out;
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
