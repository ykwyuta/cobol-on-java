package dev.cobolonjava.runtime.file;

import dev.cobolonjava.runtime.codepage.CodePage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 区分データセットのディレクトリ (要件 FR-113、暫定判断 P-056 の解消)。
 *
 * <p>ホストの区分データセットは、データの手前に<b>ディレクトリ</b>を持つ。メンバの名前と
 * 置き場所が並んだ表であり、<b>名前の昇順</b>に並んでいる。メンバを 1 つずつ名指して読む
 * だけならこの表は要らないが、{@code IEBCOPY} のように<b>全部のメンバを順に扱う</b>操作は
 * この並びで動く。並びが違えば出てくるバイト列が違う。
 *
 * <h2>並びはコードページの順である (要件 FR-053)</h2>
 * <p>ディレクトリの並びはメンバ名を<b>そのまま比べた</b>順である。ホストの文字は EBCDIC
 * なので、英字が数字より<b>前</b>に来る。Java の {@code String} の順は UTF-16 の順であり、
 * 数字が英字より前に来る。したがって {@code PAY1} と {@code PAYA} の並びが逆になる。
 *
 * <p>これは「どちらでもよい細かい違い」ではない。ライブラリを丸ごと写したジョブの出力が
 * <b>実機とここで別のバイト列になる</b>ということであり、写した先を読む後続のジョブまで
 * 巻き込む。並べる場所が増えるたびに書き分ければいずれ食い違うので、ここに 1 つだけ置く。
 *
 * <h2>メンバ名の決まりが、ディレクトリの中身を決める</h2>
 * <p>ライブラリは置き場のディレクトリ、メンバはその下のファイルである。すると覚え書きの
 * サイドカー ({@code PAYROLL.meta}) もその下に並ぶ。どこまでがメンバかを決めるのが
 * <b>メンバ名の決まり</b>である — ホストのメンバ名は 8 文字までで、使えるのは英大文字と
 * 数字と {@code @ # $}、先頭は数字ではない。{@code .} は入らないので、サイドカーは
 * メンバになりえない。名前の決まりを入れて初めてディレクトリが言えるようになる。
 *
 * <h2>別名もディレクトリの項目である (暫定判断 P-059 の解消)</h2>
 * <p>ホストのディレクトリは、同じメンバへ<b>別の名前</b>を向けられる。版を上げた
 * モジュールに古い名前を残すのに使われている形である。ここではそれを置き場の
 * <b>シンボリックリンク</b>で表す。ファイルとして置くのだから一覧に出るし、開けば
 * 中身が読める — {@code IEBGENER} も翻訳した資産も、別名だと知らないまま読める。
 * ホストの別名がそう見えるのと同じである。
 *
 * <p>覚え書きの表を別に持って「この名前は別名である」と書く手もあるが、そうすると
 * <b>その名前があるかどうかの拠り所が 2 つ</b>になる。リンクなら置き場を見れば分かる。
 * 元のメンバを消したときに<b>切れたリンクが残る</b>のもホストと同じで、実機でも
 * 別名の項目はディレクトリに残り、開こうとして初めて失敗する。
 *
 * <h2>ディレクトリには入る数がある</h2>
 * <p>{@code SPACE=(TRK,(10,5,8))} の 3 つ目がディレクトリブロックの数である。
 * 1 ブロックは 256 バイト、項目は 12 バイト + 利用者データ (統計を持てば 30 バイト)。
 * 使い切ればメンバを増やせない。読むだけで数を効かせずにいると、<b>実機では
 * 異常終了するジョブがここでは通る</b>。
 */
public final class PartitionedDataSet {

    private PartitionedDataSet() {
    }

    /** メンバ名の長さの上限。ホストのディレクトリの項目が 8 バイトである。 */
    public static final int NAME_LENGTH = 8;

    /**
     * ホストのメンバ名として通るか (要件 FR-113)。
     *
     * <p>8 文字まで、英大文字・数字・{@code @ # $}、先頭は数字でない。実機ではジョブを
     * 読む段で弾かれるので、ステップは<b>動かない</b>。ここで通してしまうと、実機なら
     * JCL 誤りで止まるジョブがここでは動き、しかも long-name のファイルができる。
     */
    public static boolean validName(String name) {
        if (name == null || name.isEmpty() || name.length() > NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean letter = c >= 'A' && c <= 'Z';
            boolean digit = c >= '0' && c <= '9';
            boolean national = c == '@' || c == '#' || c == '$';
            if (!(letter || national || (digit && i > 0))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 名前の並び順 (要件 FR-053)。
     *
     * <p>コードページのバイト列をそのまま比べる。短いほうが先に来るのは、ホストが名前を
     * 8 バイトに<b>空白で埋めて</b>持っており、空白がどの文字よりも小さいからである。
     */
    public static Comparator<String> order(CodePage codePage) {
        return (left, right) -> compare(codePage.encode(left), codePage.encode(right));
    }

    private static int compare(byte[] left, byte[] right) {
        int length = Math.min(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int difference = (left[i] & 0xFF) - (right[i] & 0xFF);
            if (difference != 0) {
                return difference;
            }
        }
        return left.length - right.length;
    }

    /**
     * ライブラリのメンバの一覧 (要件 FR-113)。
     *
     * <p>ディレクトリを別に持たず、置かれているファイルから引く。持たせれば
     * {@code OPEN OUTPUT} と削除のたびに合わせ込む手間が要り、<b>メンバがあるかどうかの
     * 拠り所が 2 つになる</b>。合わなくなったときにどちらが正しいとも言えない。
     * 移行の途中で手で置いたファイルがそのまま見えるのも、引くほうの利点である。
     *
     * @return 名前の昇順。区分データセットでなければ空
     */
    public static List<String> members(Path library, CodePage codePage) {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(library)) {
            return names;
        }
        try (var stream = Files.list(library)) {
            stream.map(path -> path.getFileName().toString())
                    .filter(PartitionedDataSet::validName)
                    .forEach(names::add);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + library, e);
        }
        names.sort(order(codePage));
        return names;
    }

    /** メンバの置き場所。名前は大文字で持つ。 */
    public static Path memberOf(Path library, String name) {
        return library.resolve(name.toUpperCase(Locale.ROOT));
    }

    // ---- 別名 (暫定判断 P-059 の解消) ----

    /**
     * その名前が別名か。
     *
     * <p>指している先が消えていても別名である。ホストでも、元のメンバを消した別名の項目は
     * ディレクトリに残る。
     */
    public static boolean alias(Path library, String name) {
        return Files.isSymbolicLink(memberOf(library, name));
    }

    /**
     * 別名が指しているメンバの名前。
     *
     * @return 別名でなければ {@code null}。指す先が同じライブラリの外なら {@code null}
     */
    public static String aliasOf(Path library, String name) {
        Path entry = memberOf(library, name);
        if (!Files.isSymbolicLink(entry)) {
            return null;
        }
        Path target;
        try {
            target = Files.readSymbolicLink(entry);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the alias " + entry, e);
        }
        // 同じライブラリの中を指しているものだけを別名と呼ぶ。ホストのディレクトリの
        // 項目はデータセットの中の位置であり、外のデータセットは指せない
        Path resolved = entry.resolveSibling(target).normalize();
        if (!library.normalize().equals(resolved.getParent())) {
            return null;
        }
        return resolved.getFileName().toString().toUpperCase(Locale.ROOT);
    }

    /**
     * そのメンバを指している別名の一覧 (要件 FR-113)。
     *
     * <p>{@code IEBCOPY} の {@code COPYGRP} が要る。メンバを写すときに別名も連れていく
     * ためであり、連れていかないと写した先で<b>古い名前から引けなくなる</b>。
     *
     * @return 名前の昇順
     */
    public static List<String> aliasesOf(Path library, String member, CodePage codePage) {
        String upper = member.toUpperCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (String name : members(library, codePage)) {
            if (upper.equals(aliasOf(library, name))) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * 別名を作る。
     *
     * <p>指す先は<b>同じライブラリの中の名前だけ</b>で書く。ライブラリごと移しても
     * 別名が生きているようにするためである。ホストのディレクトリの項目もデータセットの
     * 中の位置であって、置き場所の絶対的な指定ではない。
     */
    public static void link(Path library, String alias, String member) {
        Path entry = memberOf(library, alias);
        try {
            Files.deleteIfExists(entry);
            Files.createSymbolicLink(entry, Path.of(member.toUpperCase(Locale.ROOT)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the alias " + entry, e);
        }
    }

    /**
     * ディレクトリの項目を 1 つ消す。メンバでも別名でもよい。
     *
     * <p>覚え書きのサイドカーも一緒に消す。残しておくと、次に同じ名前で作った人が
     * <b>前の人の統計と属性</b>を引き継ぐ。
     *
     * @return 消したなら {@code true}。無ければ {@code false}
     */
    public static boolean unlink(Path library, String name) {
        Path entry = memberOf(library, name);
        try {
            // シンボリックリンクは切れていても消せる。Files.exists は切れたリンクに
            // 偽を返すので、それでは別名を消せない
            if (!Files.deleteIfExists(entry)) {
                return false;
            }
            Files.deleteIfExists(DataSetAttributes.sidecarOf(entry));
            Files.deleteIfExists(MemberStatistics.sidecarOf(entry));
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + entry, e);
        }
    }

    // ---- ディレクトリの大きさ (暫定判断 P-059 の解消) ----

    /** ディレクトリブロックの大きさ (バイト)。ホストの区分データセットは 256 である。 */
    public static final int BLOCK_SIZE = 256;

    /**
     * 1 ブロックのうち項目に使える大きさ (バイト)。
     *
     * <p>先頭 2 バイトは<b>そのブロックで使った長さ</b>である。項目はブロックをまたげない
     * ので、残りに入りきらなければ次のブロックへ送る。
     */
    public static final int BLOCK_ROOM = BLOCK_SIZE - 2;

    /**
     * ディレクトリの項目の固定部 (バイト)。
     *
     * <p>名前 8 + 位置 (TTR) 3 + 標識 1 である。標識のいちばん上の桁が別名かどうか、
     * 下の 5 桁が利用者データの<b>半語の数</b>を持つ。
     */
    public static final int ENTRY_HEAD = 12;

    /**
     * ディレクトリの項目 1 つの大きさ (バイト)。
     *
     * <p>統計を持っていれば {@link MemberStatistics#LENGTH} だけ大きい。<b>持っているか
     * どうかで入る数が変わる</b>のがホストの姿であり、だから統計を先に持たせた。
     */
    public static int entrySize(Path library, String name) {
        MemberStatistics statistics = MemberStatistics.read(memberOf(library, name));
        return ENTRY_HEAD + (statistics == null ? 0 : MemberStatistics.LENGTH);
    }

    /**
     * その項目を並べるのに要るディレクトリブロックの数。
     *
     * <p>名前の順に詰めていき、入りきらなくなったところで次のブロックへ移る。項目は
     * ブロックをまたがない。
     */
    public static int blocksNeeded(Path library, List<String> names) {
        int blocks = 0;
        int used = 0;
        for (String name : names) {
            int size = entrySize(library, name);
            if (blocks == 0 || used + size > BLOCK_ROOM) {
                blocks++;
                used = 0;
            }
            used += size;
        }
        return blocks;
    }

    /**
     * その名前をもう 1 つ入れられるか (要件 FR-113, FR-141、暫定判断 P-059 の解消)。
     *
     * <p>{@code SPACE=} の 3 つ目に書いたディレクトリブロックの数を越えないかを見る。
     * すでにある名前を書き直すだけなら項目は増えないので、いつでも入る。
     *
     * @param blocks 割り当てたディレクトリブロックの数。{@code 0} なら分からない (限りなし)
     * @param adding これから作る名前
     */
    public static boolean roomFor(Path library, CodePage codePage, int blocks, String adding) {
        if (blocks <= 0) {
            // 大きさを知らない。移行の途中で手で置いたライブラリがこれである。
            // 知らないものを勝手に決めて止めるよりは、通すほうがよい
            return true;
        }
        List<String> names = members(library, codePage);
        String upper = adding == null ? null : adding.toUpperCase(Locale.ROOT);
        if (upper != null && !names.contains(upper)) {
            names.add(upper);
            names.sort(order(codePage));
        }
        return blocksNeeded(library, names) <= blocks;
    }
}
