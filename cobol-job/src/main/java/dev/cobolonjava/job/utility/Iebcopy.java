package dev.cobolonjava.job.utility;

import dev.cobolonjava.job.jcl.JclOperands;
import dev.cobolonjava.runtime.file.DataSetAttributes;
import dev.cobolonjava.runtime.file.FileStatus;
import dev.cobolonjava.runtime.file.MemberStatistics;
import dev.cobolonjava.runtime.file.PartitionedDataSet;
import dev.cobolonjava.runtime.program.FileOperationException;
import dev.cobolonjava.runtime.program.ProgramContext;
import dev.cobolonjava.runtime.storage.DataView;
import dev.cobolonjava.runtime.storage.Storage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code IEBCOPY} (要件 FR-113, FR-137、暫定判断 P-056 の解消)。
 *
 * <p>区分データセットのメンバを別のライブラリへ写す。ライブラリを丸ごと写す・一部の
 * メンバだけを写す・名前を変えて写す、のいずれもこれで書かれている。
 *
 * <h2>これは<b>ディレクトリを要る</b>最初の道具である</h2>
 * <p>いままでの道具は DD がデータセット 1 つを指していた。{@code IEBCOPY} の
 * {@code INDD} が指すのは<b>ライブラリ</b>であり、何を写すのかはディレクトリ
 * ({@link PartitionedDataSet#members}) を引いて初めて決まる。並びもそこで決まる。
 * 実機と同じ並びで写さなければ、写した先のバイト列が実機と違う。
 *
 * <h2>すでにあるメンバは、言われなければ置き換えない</h2>
 * <p>写し先に同じ名前のメンバがあれば<b>写さずに飛ばし</b>、復帰コード 4 を立てる。
 * {@code R} を書いたときだけ置き換える。黙って上書きすると、月次で積み増していく
 * ライブラリが毎月まっさらになる。
 *
 * <h2>別名は別名のまま写す (暫定判断 P-059 の解消)</h2>
 * <p>ライブラリを丸ごと写せば、別名も<b>別名として</b>写る。中身を写して普通のメンバに
 * してしまうと、写した先で同じバイト列が 2 つになり、片方だけを書き直したときに食い違う。
 * 指している先を一緒に写さないとき ({@code SELECT} で別名だけを選んだとき) は、
 * 中身を写して普通のメンバにする — 指す先がないのだから、そうするほかない。
 *
 * <p>{@code COPYGRP} は<b>選んだメンバとその別名をひと組で</b>写す。{@code SELECT} で
 * メンバだけを選ぶと別名は置いていかれ、写した先で古い名前から引けなくなる。
 *
 * <h2>統計は持ち越す</h2>
 * <p>ISPF の統計 ({@link MemberStatistics}) は写し先へそのまま持っていく。作り直すのでは
 * ない — 写しは<b>編集ではない</b>ので、版も更新日時も動かないのがホストの姿である。
 *
 * <h2>写し元と写し先が同じなら圧縮である</h2>
 * <p>{@code COPY INDD=A,OUTDD=A} はホストでは<b>圧縮</b> (使われなくなった領域の詰め直し)
 * である。ここでは領域の断片化を持たないので何もしないが、写しとして扱ってはならない。
 * メンバを自分自身へ写せば中身が消える。
 */
public final class Iebcopy extends UtilityProgram {

    private int highest;

    @Override
    public void run(Storage storage, ProgramContext context, DataView[] arguments) {
        highest = 0;
        List<String> statements = statements(control(context, SYSIN));
        if (statements.isEmpty()) {
            print(context, "IEB110I NO CONTROL STATEMENTS WERE FOUND");
            context.setReturnCode(12);
            return;
        }
        Copy current = null;
        for (String statement : statements) {
            current = execute(context, statement, current);
        }
        if (current != null) {
            perform(context, current);
        }
        print(context, "IEB147I END OF JOB - " + highest + " WAS HIGHEST SEVERITY CODE.");
        context.setReturnCode(highest);
    }

    /** 1 つの写しの指示。{@code COPY} と、そのあとに続く {@code SELECT} / {@code EXCLUDE}。 */
    private static final class Copy {
        private final List<Source> inputs = new ArrayList<>();
        private String output;
        private final List<Selection> selected = new ArrayList<>();
        private final Set<String> excluded = new LinkedHashSet<>();
        /** {@code COPYGRP} か。選んだメンバの別名も連れていく。 */
        private boolean group;
    }

    /**
     * 写し元のライブラリ 1 つ。
     *
     * @param ddName  {@code INDD=} に書いた DD 名
     * @param replace {@code (dd,R)} と書いたか。同じ名前のメンバを置き換える
     */
    private record Source(String ddName, boolean replace) {
    }

    /**
     * {@code SELECT MEMBER=} の 1 項目。
     *
     * @param from    写し元のメンバ名
     * @param to      写し先のメンバ名。名前を変えなければ {@code from} と同じ
     * @param replace {@code (名前,,R)} と書いたか
     */
    private record Selection(String from, String to, boolean replace) {
    }

    private Copy execute(ProgramContext context, String statement, Copy current) {
        String text = statement.trim();
        if (text.isEmpty()) {
            return current;
        }
        String operation = head(text);
        String operands = tail(text);
        // 名前欄を書いた制御文は、1 語目が名札で 2 語目が操作である
        if (!known(operation) && known(head(operands))) {
            operation = head(operands);
            operands = tail(operands);
        }
        operation = operation.toUpperCase(Locale.ROOT);
        switch (operation) {
            case "COPY", "COPYGRP" -> {
                if (current != null) {
                    perform(context, current);
                }
                return copyOf(context, operands, operation.equals("COPYGRP"));
            }
            case "SELECT" -> {
                if (current == null) {
                    print(context, "IEB130I SELECT MUST FOLLOW A COPY STATEMENT");
                    fail(12);
                    return null;
                }
                current.selected.addAll(selectionsOf(members(operands)));
                return current;
            }
            case "EXCLUDE" -> {
                if (current == null) {
                    print(context, "IEB130I EXCLUDE MUST FOLLOW A COPY STATEMENT");
                    fail(12);
                    return null;
                }
                for (Selection selection : selectionsOf(members(operands))) {
                    current.excluded.add(selection.from());
                }
                return current;
            }
            default -> {
                // COPYMOD と ALTERMOD は<b>ロードモジュールを組み直す</b>ものである。
                // 単なる写しとして扱えば、動かないモジュールが写って正常終了する
                print(context, "IEB100I OPERATION NOT SUPPORTED YET: " + operation);
                fail(12);
                return current;
            }
        }
    }

    private static boolean known(String word) {
        return switch (word.toUpperCase(Locale.ROOT)) {
            case "COPY", "COPYGRP", "COPYMOD", "SELECT", "EXCLUDE", "ALTERMOD" -> true;
            default -> false;
        };
    }

    /** {@code COPY INDD=...,OUTDD=...}。 */
    private Copy copyOf(ProgramContext context, String operands, boolean group) {
        Copy copy = new Copy();
        copy.group = group;
        for (String operand : JclOperands.split(operands)) {
            String key = JclOperands.key(operand).toUpperCase(Locale.ROOT);
            String value = JclOperands.value(operand);
            switch (key) {
                case "INDD", "I" -> copy.inputs.addAll(sourcesOf(value));
                case "OUTDD", "O" -> copy.output = JclOperands.unwrap(value).trim()
                        .toUpperCase(Locale.ROOT);
                case "LIST" -> {
                    // 覚え書きを出すかどうかの指定である。ここでは常に出す
                }
                default -> {
                    print(context, "IEB100I COPY DOES NOT SUPPORT: " + key);
                    fail(12);
                }
            }
        }
        return copy;
    }

    /** {@code INDD=A} / {@code INDD=(A,B)} / {@code INDD=((A,R),B)}。 */
    private static List<Source> sourcesOf(String value) {
        List<Source> out = new ArrayList<>();
        for (String part : JclOperands.split(JclOperands.unwrap(value))) {
            List<String> fields = JclOperands.split(JclOperands.unwrap(part));
            String ddName = fields.isEmpty() ? "" : fields.get(0).trim().toUpperCase(Locale.ROOT);
            boolean replace = fields.size() >= 2 && fields.get(1).trim()
                    .equalsIgnoreCase("R");
            if (!ddName.isEmpty()) {
                out.add(new Source(ddName, replace));
            }
        }
        return out;
    }

    /** {@code MEMBER=(...)} の中身。 */
    private static List<String> members(String operands) {
        for (String operand : JclOperands.split(operands)) {
            String key = JclOperands.key(operand).toUpperCase(Locale.ROOT);
            if (key.equals("MEMBER") || key.equals("M")) {
                return JclOperands.split(JclOperands.unwrap(JclOperands.value(operand)));
            }
        }
        return List.of();
    }

    /**
     * {@code MEMBER=(A,(OLD,NEW),(B,,R))} を読む。
     *
     * <p>括弧の中は<b>写し元、写し先、置き換えるか</b>の 3 つである。2 つ目を空けたまま
     * 3 つ目だけを書けば、名前はそのままで置き換える。
     */
    private static List<Selection> selectionsOf(List<String> entries) {
        List<Selection> out = new ArrayList<>();
        for (String entry : entries) {
            List<String> fields = JclOperands.split(JclOperands.unwrap(entry));
            if (fields.isEmpty()) {
                continue;
            }
            String from = fields.get(0).trim().toUpperCase(Locale.ROOT);
            String to = fields.size() >= 2 && !fields.get(1).isBlank()
                    ? fields.get(1).trim().toUpperCase(Locale.ROOT)
                    : from;
            boolean replace = fields.size() >= 3 && fields.get(2).trim().equalsIgnoreCase("R");
            if (!from.isEmpty()) {
                out.add(new Selection(from, to, replace));
            }
        }
        return out;
    }

    // ---- 写す ----

    private void perform(ProgramContext context, Copy copy) {
        if (copy.inputs.isEmpty() || copy.output == null) {
            print(context, "IEB120I COPY NEEDS INDD AND OUTDD");
            fail(12);
            return;
        }
        Path to = library(context, copy.output, "OUTPUT");
        if (to == null) {
            return;
        }
        int copied = 0;
        Set<String> found = new LinkedHashSet<>();
        for (Source source : copy.inputs) {
            Path from = library(context, source.ddName(), "INPUT");
            if (from == null) {
                return;
            }
            if (from.equals(to)) {
                // 圧縮である。断片化を持たないので詰め直すものがない
                print(context, "IEB1013I COMPRESS SUCCESSFUL FOR " + source.ddName());
                continue;
            }
            copied += copyMembers(context, copy, source, from, to, found);
        }
        for (Selection selection : copy.selected) {
            if (!found.contains(selection.from())) {
                print(context, "IEB166I MEMBER NAME " + selection.from()
                        + " NOT FOUND IN THE INPUT DATA SET");
                fail(8);
            }
        }
        print(context, "IEB1098I " + copied + " OF " + expected(copy, found)
                + " MEMBERS WERE COPIED");
    }

    private static int expected(Copy copy, Set<String> found) {
        // COPYGRP は選んだメンバのほかに別名も連れていくので、書いた数では足りない
        return copy.selected.isEmpty() || copy.group ? found.size() : copy.selected.size();
    }

    /**
     * ライブラリ 1 つのメンバを写す。
     *
     * <p>写す順はディレクトリの並びである。{@code SELECT} を書いても<b>書いた順ではなく
     * ディレクトリの順</b>で写す。ホストが入力を順に読んで拾うからであり、書いた順に写すと
     * メンバの並びが実機と変わる。
     *
     * <p>別名は<b>あとから</b>作る (暫定判断 P-059 の解消)。ディレクトリの並びでは別名が
     * 指す先より前に来ることがあり、先に作ろうとすると指す先がまだ無い。
     */
    private int copyMembers(ProgramContext context, Copy copy, Source source, Path from, Path to,
                            Set<String> found) {
        List<Selection> wanted = selections(context, copy, from);
        // 何をどの名前で写すか。別名を別名のまま写せるかどうかがこれで決まる
        Map<String, String> destinations = new LinkedHashMap<>();
        for (String name : PartitionedDataSet.members(from, context.codePage())) {
            Selection selection = selectionFor(copy, wanted, name, source.replace());
            if (selection != null) {
                destinations.put(name, selection.to());
            }
        }
        int copied = 0;
        List<String> links = new ArrayList<>();
        for (String name : destinations.keySet()) {
            Selection selection = selectionFor(copy, wanted, name, source.replace());
            found.add(name);
            Path target = PartitionedDataSet.memberOf(to, selection.to());
            if (exists(target) && !selection.replace()) {
                print(context, "IEB167I MEMBER " + selection.to()
                        + " ALREADY EXISTS IN THE OUTPUT DATA SET AND WAS NOT COPIED");
                fail(4);
                continue;
            }
            String points = PartitionedDataSet.aliasOf(from, name);
            if (points != null && destinations.containsKey(points)) {
                // 指す先も一緒に写る。別名のまま写す
                links.add(name);
                continue;
            }
            Path member = PartitionedDataSet.memberOf(from, name);
            byte[] bytes = readSound(context, member);
            room(context, to, selection.to());
            // 写し先に同じ名前の別名があれば、その項目を落としてから書く。
            // そのまま書けばリンクをたどって<b>指す先のメンバ</b>を上書きしてしまう
            if (PartitionedDataSet.alias(to, selection.to())) {
                PartitionedDataSet.unlink(to, selection.to());
            }
            fits(context, to, target, bytes);
            writeBytes(target, bytes);
            DataSetAttributes.read(member).write(target);
            MemberStatistics.copy(member, target);
            print(context, "IEB154I " + selection.to() + " HAS BEEN SUCCESSFULLY COPIED");
            copied++;
        }
        for (String name : links) {
            String alias = destinations.get(name);
            room(context, to, alias);
            PartitionedDataSet.unlink(to, alias);
            PartitionedDataSet.link(to, alias,
                    destinations.get(PartitionedDataSet.aliasOf(from, name)));
            print(context, "IEB154I " + alias + " HAS BEEN SUCCESSFULLY COPIED AS AN ALIAS");
            copied++;
        }
        return copied;
    }

    /**
     * 指している先が消えていても項目はある。
     *
     * <p>{@link Files#exists} は切れたシンボリックリンクに偽を返す。それで置き換えの
     * 判断をすると、切れた別名の上に黙って書いてしまう。
     */
    private static boolean exists(Path path) {
        return Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * 実際に選ばれているもの。{@code COPYGRP} なら別名も連れていく。
     *
     * <p>{@code SELECT} でメンバだけを選ぶと別名は置いていかれる。それが困るときに
     * {@code COPYGRP} と書く。名前を変えて写す指定と一緒に使っても、<b>別名の名前は
     * 変わらない</b> — 変える先が書かれていないからである。
     */
    private static List<Selection> selections(ProgramContext context, Copy copy, Path from) {
        if (!copy.group || copy.selected.isEmpty()) {
            return copy.selected;
        }
        List<Selection> wanted = new ArrayList<>(copy.selected);
        for (Selection selection : copy.selected) {
            for (String alias : PartitionedDataSet.aliasesOf(from, selection.from(),
                    context.codePage())) {
                wanted.add(new Selection(alias, alias, selection.replace()));
            }
        }
        return wanted;
    }

    /**
     * そのメンバを写すか。写すなら写し先の名前と置き換えの指定を返す。
     *
     * @return 写さないなら {@code null}
     */
    private static Selection selectionFor(Copy copy, List<Selection> wanted, String name,
                                          boolean replace) {
        if (copy.excluded.contains(name)) {
            return null;
        }
        if (wanted.isEmpty()) {
            return new Selection(name, name, replace);
        }
        for (Selection selection : wanted) {
            if (selection.from().equals(name)) {
                // INDD に R を書けば、選んだメンバも置き換える
                return replace ? new Selection(name, selection.to(), true) : selection;
            }
        }
        return null;
    }

    /**
     * DD が指すライブラリ。
     *
     * @return 区分データセットでなければ {@code null}
     */
    private Path library(ProgramContext context, String ddName, String role) {
        Path path = pathOf(context, ddName);
        if (!Files.isDirectory(path)) {
            print(context, "IEB1084I " + role + " DATA SET " + ddName + " IS NOT PARTITIONED");
            fail(12);
            return null;
        }
        return path;
    }

    /**
     * 割り当てた領域に収まるか (要件 FR-141、暫定判断 P-053)。
     *
     * <p>限りが付いているのは<b>ライブラリ</b>であってメンバではない。すでに入っている
     * メンバの合計に、これから書くぶんを足して見る。置き換えるなら元のぶんは空く。
     */
    private static void fits(ProgramContext context, Path library, Path target, byte[] bytes) {
        String ddName = context.catalog().ddNameFor(library);
        long limit = ddName == null ? 0 : context.catalog().limitOf(ddName);
        if (limit <= 0) {
            return;
        }
        long occupied = 0;
        for (String name : PartitionedDataSet.members(library, context.codePage())) {
            Path member = PartitionedDataSet.memberOf(library, name);
            // 別名は場所を取らない。ホストのディレクトリの項目が同じ位置を指すだけである
            if (!member.equals(target) && !PartitionedDataSet.alias(library, name)) {
                occupied += sizeOf(member);
            }
        }
        if (occupied + bytes.length > limit) {
            throw new FileOperationException(ddName, FileStatus.NO_SPACE);
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot size " + path, e);
        }
    }

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
            current.append(current.isEmpty() ? "" : " ").append(text);
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

    /** 復帰コードはいちばん大きいものが残る。 */
    private void fail(int code) {
        highest = Math.max(highest, code);
    }
}
